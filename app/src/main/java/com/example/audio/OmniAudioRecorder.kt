package com.example.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import kotlin.math.sqrt

/**
 * Intelligent hands-free audio recorder with real-time Voice Activity Detection (VAD).
 * Recognizes speech start, accumulates duration, verifies validity thresholds (intensity + length),
 * and dispatches completed utterances when the user finishes speaking.
 */
class OmniAudioRecorder(
  private val sampleRate: Int = 16000
) {
  private val tag = "OmniAudioRecorder"
  private var audioRecord: AudioRecord? = null
  private var recordingJob: Job? = null
  private val scope = CoroutineScope(Dispatchers.IO)

  // Real-time audio amplitude [0.0 .. 1.0]
  private val _audioRms = MutableStateFlow(0f)
  val audioRms: StateFlow<Float> = _audioRms.asStateFlow()

  // True as soon as user speech reaches validity thresholds (min intensity & min duration)
  private val _isSpeechValid = MutableStateFlow(false)
  val isSpeechValid: StateFlow<Boolean> = _isSpeechValid.asStateFlow()

  // True while actively recording from microphone
  private val _isRecording = MutableStateFlow(false)
  val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

  // Mute state
  var isMuted: Boolean = false

  // Emits complete valid utterances when speech ends
  private val _validUtteranceFlow = MutableSharedFlow<ByteArray>(extraBufferCapacity = 1)
  val validUtteranceFlow: SharedFlow<ByteArray> = _validUtteranceFlow.asSharedFlow()

  // VAD Thresholds
  private val speechIntensityThreshold = 0.075f
  private val minSpeechDurationMs = 500L      // Minimum speech duration to qualify as valid utterance
  private val silenceTimeoutMs = 850L         // Silence required after speech to trigger end-of-speech

  // Pre-roll audio ring buffer (~500ms) to capture audio before VAD volume threshold is reached
  private val preRollRingBuffer = java.util.ArrayDeque<ByteArray>()
  private val preRollMaxMs = 500L
  private var preRollBufferedMs = 0L

  private val currentUtteranceStream = ByteArrayOutputStream()
  private var speechActiveDurationMs = 0L
  private var silenceDurationMs = 0L
  private var isSpeechActive = false

  @SuppressLint("MissingPermission")
  fun startRecording(onUtteranceComplete: ((ByteArray) -> Unit)? = null) {
    if (_isRecording.value) return

    val minBufferSize = AudioRecord.getMinBufferSize(
      sampleRate,
      AudioFormat.CHANNEL_IN_MONO,
      AudioFormat.ENCODING_PCM_16BIT
    )

    try {
      audioRecord = AudioRecord(
        MediaRecorder.AudioSource.MIC,
        sampleRate,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
        minBufferSize * 2
      )

      if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
        Log.e(tag, "AudioRecord could not initialize")
        return
      }

      audioRecord?.startRecording()
      _isRecording.value = true
      resetUtteranceState()

      recordingJob = scope.launch {
        val buffer = ByteArray(minBufferSize)
        while (isActive && _isRecording.value) {
          val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
          if (read > 0) {
            if (isMuted) {
              _audioRms.value = 0f
              _isSpeechValid.value = false
              resetUtteranceState()
              continue
            }

            val chunk = buffer.copyOf(read)
            val chunkDurationMs = ((read / 2).toFloat() / sampleRate * 1000f).toLong()

            // Calculate chunk RMS
            var sumSquares = 0.0
            val samples = read / 2
            for (i in 0 until read step 2) {
              val low = chunk[i].toInt() and 0xFF
              val high = chunk[i + 1].toInt()
              val sample = (high shl 8) or low
              sumSquares += (sample.toDouble() * sample.toDouble())
            }

            val rms = if (samples > 0) {
              val rawRms = sqrt(sumSquares / samples) / 32768.0
              (rawRms * 3.2).toFloat().coerceIn(0f, 1f)
            } else 0f

            _audioRms.value = rms

            // Intelligent VAD logic with pre-roll ring buffer
            if (rms >= speechIntensityThreshold) {
              if (!isSpeechActive) {
                // Speech onset detected! Flush pre-roll buffer into currentUtteranceStream
                isSpeechActive = true
                while (preRollRingBuffer.isNotEmpty()) {
                  val preChunk = preRollRingBuffer.poll()
                  currentUtteranceStream.write(preChunk)
                }
                preRollBufferedMs = 0L
              }
              silenceDurationMs = 0L
              speechActiveDurationMs += chunkDurationMs
              currentUtteranceStream.write(chunk)

              // Check if valid utterance threshold is reached
              if (speechActiveDurationMs >= minSpeechDurationMs) {
                _isSpeechValid.value = true
              }
            } else {
              // Sound below intensity threshold (ambient noise or silence)
              if (isSpeechActive) {
                // User was speaking; now pausing
                currentUtteranceStream.write(chunk)
                silenceDurationMs += chunkDurationMs

                if (silenceDurationMs >= silenceTimeoutMs) {
                  // End of speech reached
                  val wasValid = _isSpeechValid.value
                  val pcmBytes = currentUtteranceStream.toByteArray()

                  resetUtteranceState()

                  if (wasValid && pcmBytes.isNotEmpty()) {
                    Log.d(tag, "Valid utterance completed with pre-roll buffer: ${pcmBytes.size} bytes. Sending to model.")
                    _validUtteranceFlow.tryEmit(pcmBytes)
                    onUtteranceComplete?.invoke(pcmBytes)
                  } else {
                    Log.d(tag, "Discarded short noise (< ${minSpeechDurationMs}ms).")
                  }
                }
              } else {
                // Silence before speech onset: add chunk to pre-roll ring buffer
                preRollRingBuffer.add(chunk)
                preRollBufferedMs += chunkDurationMs
                while (preRollBufferedMs > preRollMaxMs && preRollRingBuffer.isNotEmpty()) {
                  val removed = preRollRingBuffer.poll()
                  val size = removed?.size ?: 0
                  val removedMs = ((size / 2).toFloat() / sampleRate * 1000f).toLong()
                  preRollBufferedMs = (preRollBufferedMs - removedMs).coerceAtLeast(0L)
                }
              }
            }
          }
        }
      }
    } catch (e: Exception) {
      Log.e(tag, "Error starting AudioRecord", e)
      stopRecording()
    }
  }

  private fun resetUtteranceState() {
    currentUtteranceStream.reset()
    preRollRingBuffer.clear()
    preRollBufferedMs = 0L
    speechActiveDurationMs = 0L
    silenceDurationMs = 0L
    isSpeechActive = false
    _isSpeechValid.value = false
  }

  fun stopRecording(): ByteArray {
    _isRecording.value = false
    recordingJob?.cancel()
    recordingJob = null
    _audioRms.value = 0f
    _isSpeechValid.value = false

    try {
      if (audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
        audioRecord?.stop()
      }
      audioRecord?.release()
      audioRecord = null
    } catch (e: Exception) {
      Log.w(tag, "Error stopping AudioRecord", e)
    }

    val result = currentUtteranceStream.toByteArray()
    resetUtteranceState()
    return result
  }

  fun release() {
    stopRecording()
  }
}
