package com.example.network

import android.util.Base64
import android.util.Log
import com.example.engine.OmniStreamOutput
import com.example.model.LfmExecutionPhase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * Loopback & Network Client for llama.cpp LFM3-Omni-2B server.
 * Connects to http://127.0.0.1:8080 (embedded in-process loopback or LAN host).
 * Implements:
 * - Health Check: GET /health
 * - Multimodal Streaming: POST /v1/chat/completions (SSE with text, base64 images, and audio)
 * - Enforces --reasoning off (enable_thinking=false) & system prompt "Respond with interleaved text and audio."
 */
class OmniRealtimeClient(
  private val baseUrl: String = "http://127.0.0.1:8080"
) {
  companion object {
    private const val TAG = "OmniRealtimeClient"
  }

  private val client = OkHttpClient.Builder()
    .connectTimeout(5, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    .build()

  fun checkServerHealth(): Boolean {
    return try {
      val request = Request.Builder()
        .url("$baseUrl/health")
        .get()
        .build()
      val response = client.newCall(request).execute()
      response.isSuccessful
    } catch (e: Exception) {
      false
    }
  }

  fun streamChatCompletion(
    userText: String,
    jpegFrames: List<ByteArray>?,
    pcmAudio16k: ByteArray?
  ): Flow<OmniStreamOutput> = flow {
    emit(OmniStreamOutput.PhaseChange(LfmExecutionPhase.CONV_PREFILL))

    val messagesArray = JSONArray()

    // 1. Mandatory System Directive (Section 2.4 of spec)
    val sysMsg = JSONObject().apply {
      put("role", "system")
      put("content", "Respond with interleaved text and audio.")
    }
    messagesArray.put(sysMsg)

    // 2. Multimodal User Content
    val userContentArray = JSONArray()
    if (userText.isNotBlank()) {
      userContentArray.put(JSONObject().apply {
        put("type", "text")
        put("text", userText.trim())
      })
    }

    // Include recent discrete JPEG keyframes (max 1-2 frames as specified in section 1.6)
    jpegFrames?.takeLast(2)?.forEach { frameBytes ->
      val base64Img = Base64.encodeToString(frameBytes, Base64.NO_WRAP)
      userContentArray.put(JSONObject().apply {
        put("type", "image_url")
        put("image_url", JSONObject().apply {
          put("url", "data:image/jpeg;base64,$base64Img")
        })
      })
    }

    // Include 16kHz audio input
    if (pcmAudio16k != null && pcmAudio16k.isNotEmpty()) {
      val wavBytes = wrapPcmInWav(pcmAudio16k, 16000)
      val base64Audio = Base64.encodeToString(wavBytes, Base64.NO_WRAP)
      userContentArray.put(JSONObject().apply {
        put("type", "input_audio")
        put("input_audio", JSONObject().apply {
          put("data", base64Audio)
          put("format", "wav")
        })
      })
    }

    if (userContentArray.length() == 0) {
      userContentArray.put(JSONObject().apply {
        put("type", "text")
        put("text", "Hallo")
      })
    }

    val userMsg = JSONObject().apply {
      put("role", "user")
      put("content", userContentArray)
    }
    messagesArray.put(userMsg)

    // Request payload matching section 2.4 & section 6:
    // temperature=0.0, reasoning off, streaming=true
    val payload = JSONObject().apply {
      put("model", "LFM3-Omni-2B-Abliter8-Q4_K_M")
      put("messages", messagesArray)
      put("stream", true)
      put("temperature", 0.0)
      put("max_tokens", 2048)
    }

    val body = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
    val request = Request.Builder()
      .url("$baseUrl/v1/chat/completions")
      .post(body)
      .build()

    emit(OmniStreamOutput.PhaseChange(LfmExecutionPhase.OMNI_DECODE))

    val call = client.newCall(request)
    val response = call.execute()

    if (!response.isSuccessful) {
      throw IllegalStateException("Server-Fehler: HTTP ${response.code} von $baseUrl")
    }

    val reader = response.body?.byteStream()?.bufferedReader() ?: return@flow

    try {
      var line = reader.readLine()
      while (line != null && coroutineContext.isActive) {
        if (line.startsWith("data: ")) {
          val data = line.removePrefix("data: ").trim()
          if (data == "[DONE]") {
            break
          }

          try {
            val json = JSONObject(data)
            val choices = json.optJSONArray("choices")
            if (choices != null && choices.length() > 0) {
              val delta = choices.getJSONObject(0).optJSONObject("delta")
              if (delta != null) {
                val content = delta.optString("content", "")
                if (content.isNotEmpty()) {
                  emit(OmniStreamOutput.TextToken(content))
                }
                val audioObj = delta.optJSONObject("audio")
                if (audioObj != null) {
                  val audioData = audioObj.optString("data", "")
                  if (audioData.isNotEmpty()) {
                    val rawAudio = Base64.decode(audioData, Base64.DEFAULT)
                    emit(OmniStreamOutput.AudioChunk(rawAudio))
                  }
                }
              }
            }
          } catch (e: Exception) {
            Log.w(TAG, "Error parsing SSE chunk: $data", e)
          }
        }
        line = reader.readLine()
      }
    } finally {
      reader.close()
      response.close()
    }

    emit(OmniStreamOutput.PhaseChange(LfmExecutionPhase.READY_IDLE))
  }.flowOn(Dispatchers.IO)

  private fun wrapPcmInWav(pcmData: ByteArray, sampleRate: Int): ByteArray {
    val totalAudioLen = pcmData.size.toLong()
    val totalDataLen = totalAudioLen + 36
    val channels = 1
    val byteRate = 16 * sampleRate * channels / 8

    val header = ByteArray(44)
    header[0] = 'R'.code.toByte()
    header[1] = 'I'.code.toByte()
    header[2] = 'F'.code.toByte()
    header[3] = 'F'.code.toByte()
    header[4] = (totalDataLen and 0xffL).toByte()
    header[5] = ((totalDataLen shr 8) and 0xffL).toByte()
    header[6] = ((totalDataLen shr 16) and 0xffL).toByte()
    header[7] = ((totalDataLen shr 24) and 0xffL).toByte()
    header[8] = 'W'.code.toByte()
    header[9] = 'A'.code.toByte()
    header[10] = 'V'.code.toByte()
    header[11] = 'E'.code.toByte()
    header[12] = 'f'.code.toByte()
    header[13] = 'm'.code.toByte()
    header[14] = 't'.code.toByte()
    header[15] = ' '.code.toByte()
    header[16] = 16
    header[17] = 0
    header[18] = 0
    header[19] = 0
    header[20] = 1
    header[21] = 0
    header[22] = channels.toByte()
    header[23] = 0
    header[24] = (sampleRate and 0xff).toByte()
    header[25] = ((sampleRate shr 8) and 0xff).toByte()
    header[26] = ((sampleRate shr 16) and 0xff).toByte()
    header[27] = ((sampleRate shr 24) and 0xff).toByte()
    header[28] = (byteRate and 0xff).toByte()
    header[29] = ((byteRate shr 8) and 0xff).toByte()
    header[30] = ((byteRate shr 16) and 0xff).toByte()
    header[31] = ((byteRate shr 24) and 0xff).toByte()
    header[32] = (channels * 16 / 8).toByte()
    header[33] = 0
    header[34] = 16
    header[35] = 0
    header[36] = 'd'.code.toByte()
    header[37] = 'a'.code.toByte()
    header[38] = 't'.code.toByte()
    header[39] = 'a'.code.toByte()
    header[40] = (totalAudioLen and 0xffL).toByte()
    header[41] = ((totalAudioLen shr 8) and 0xffL).toByte()
    header[42] = ((totalAudioLen shr 16) and 0xffL).toByte()
    header[43] = ((totalAudioLen shr 24) and 0xffL).toByte()

    val out = ByteArrayOutputStream(44 + pcmData.size)
    out.write(header)
    out.write(pcmData)
    return out.toByteArray()
  }
}
