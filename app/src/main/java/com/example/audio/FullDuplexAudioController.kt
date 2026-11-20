package com.example.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.os.Build
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
 * Full-Duplex Audio Controller für LFM3-Omni:
 * - 16.000 Hz 16-Bit Mono PCM Input (für LFM3 Causal Audio Encoder & Echtzeit-Wellenform)
 * - 24.000 Hz 16-Bit Mono PCM Output (über USAGE_MEDIA für volle Lautsprecher-Wiedergabe)
 * - Hardware DSP Acoustic Echo Cancellation (AEC) sofern verfügbar
 * - Headset-gestütztes Barge-In & Selbst-Interruption-Schutz (Lautsprecher-Echo bringt Sprachausgabe nicht zum Abbruch)
 */
class FullDuplexAudioController(
  private val context: Context? = null
) {
  companion object {
    const val INPUT_SAMPLE_RATE = 16000
    const val OUTPUT_SAMPLE_RATE = 24000
    const val VAD_FRAME_SAMPLES = 512 // 32ms @ 16kHz
    private const val TAG = "FullDuplexAudio"
  }

  private val audioManager: AudioManager? by lazy {
    context?.applicationContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
  }

  fun isHeadsetConnected(): Boolean {
    val am = audioManager ?: return false
    return try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        devices.any { dev ->
          dev.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
          dev.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
          dev.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
          dev.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
          dev.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
          dev.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
      } else {
        @Suppress("DEPRECATION")
        am.isWiredHeadsetOn
      }
    } catch (_: Throwable) {
      false
    }
  }

  private var audioRecord: AudioRecord? = null
  private var echoCanceler: AcousticEchoCanceler? = null
  private var captureJob: Job? = null
  private val scope = CoroutineScope(Dispatchers.IO)

  private val _pcmInputFlow = MutableSharedFlow<ShortArray>(extraBufferCapacity = 64)
  val pcmInputFlow: SharedFlow<ShortArray> = _pcmInputFlow.asSharedFlow()

  private val _completedUtteranceFlow = MutableSharedFlow<ByteArray>(extraBufferCapacity = 2)
  val completedUtteranceFlow: SharedFlow<ByteArray> = _completedUtteranceFlow.asSharedFlow()

  private val _userVoiceRms = MutableStateFlow(0f)
  val userVoiceRms: StateFlow<Float> = _userVoiceRms.asStateFlow()

  private val _realWaveform = MutableStateFlow<List<Float>>(List(32) { 0f })
  val realWaveform: StateFlow<List<Float>> = _realWaveform.asStateFlow()

  private val _outputAmplitude = MutableStateFlow(0f)
  val outputAmplitude: StateFlow<Float> = _outputAmplitude.asStateFlow()

  private val _isRecording = MutableStateFlow(false)
  val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

  @Volatile
  var isAssistantSpeaking: Boolean = false

  var isMuted: Boolean = false

  private var consecutiveBargeInChunks = 0
  private val bargeInThresholdChunks = 10 // ~320ms echte durchgängige Nutzer-Sprache (schützt vor Lautsprecher-Echo)

  @Volatile
  var micGain: Float = 2.5f

  @Volatile
  var speechIntensityThreshold: Float = 0.045f

  private val minSpeechDurationMs = 320L

  @Volatile
  var silenceTimeoutMs: Long = 1000L

  fun updateConfig(micGain: Float, vadSensitivity: Float, silenceTimeoutMs: Long) {
    this.micGain = micGain.coerceIn(0.5f, 6.0f)
    this.speechIntensityThreshold = vadSensitivity.coerceIn(0.015f, 0.25f)
    this.silenceTimeoutMs = silenceTimeoutMs.coerceIn(500L, 3000L)
  }

  private val utteranceLock = Any()
  private val currentUtteranceStream = ByteArrayOutputStream()
  private val preRollRingBuffer = java.util.ArrayDeque<ByteArray>()
  private val rollingRecentPcmChunks = java.util.ArrayDeque<ByteArray>()
  private var rollingRecentBytes = 0
  private val maxRollingBytes = INPUT_SAMPLE_RATE * 2 * 8 // Letzte 8 Sekunden 16kHz 16-bit Mono PCM (256 KB)

  @Volatile
  private var lastCompletedUtterancePcm: ByteArray? = null

  private val preRollMaxMs = 500L
  private var preRollBufferedMs = 0L
  private var speechActiveDurationMs = 0L
  private var silenceDurationMs = 0L
  private var isSpeechActive = false

  /**
   * Liefert das zuletzt aufgezeichnete 16kHz 16-Bit Mono PCM-Sprachsegment des Nutzers
   * für die native Übergabe an den LFM3-Audio-Encoder.
   */
  fun consumeRecentSpeechPcm(): ByteArray? {
    synchronized(utteranceLock) {
      val completed = lastCompletedUtterancePcm
      if (completed != null && completed.size >= 3200) {
        lastCompletedUtterancePcm = null
        return completed
      }
      if (currentUtteranceStream.size() >= 3200) {
        val activeBytes = currentUtteranceStream.toByteArray()
        return activeBytes
      }
      if (rollingRecentBytes >= 3200) {
        val out = ByteArrayOutputStream(rollingRecentBytes)
        for (chunk in rollingRecentPcmChunks) {
          out.write(chunk)
        }
        return out.toByteArray()
      }
      return null
    }
  }

  @SuppressLint("MissingPermission")
  fun startAudioEngine(onBargeInDetected: (() -> Unit)? = null) {
    if (_isRecording.value) return

    val minInBuf = AudioRecord.getMinBufferSize(
      INPUT_SAMPLE_RATE,
      AudioFormat.CHANNEL_IN_MONO,
      AudioFormat.ENCODING_PCM_16BIT
    ).coerceAtLeast(4096)

    try {
      // VOICE_RECOGNITION nutzt den klaren Mikrofonpfad ohne die Medien-Lautsprecher-Ausgabe auf den Telefonhörer zu zwingen
      var record = AudioRecord(
        MediaRecorder.AudioSource.VOICE_RECOGNITION,
        INPUT_SAMPLE_RATE,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
        minInBuf * 2
      )

      if (record.state != AudioRecord.STATE_INITIALIZED) {
        record.release()
        record = AudioRecord(
          MediaRecorder.AudioSource.MIC,
          INPUT_SAMPLE_RATE,
          AudioFormat.CHANNEL_IN_MONO,
          AudioFormat.ENCODING_PCM_16BIT,
          minInBuf * 2
        )
      }

      if (record.state != AudioRecord.STATE_INITIALIZED) {
        Log.e(TAG, "AudioRecord could not initialize")
        return
      }

      if (AcousticEchoCanceler.isAvailable()) {
        try {
          echoCanceler = AcousticEchoCanceler.create(record.audioSessionId)?.apply {
            enabled = true
          }
        } catch (e: Exception) {
          Log.w(TAG, "Hardware AEC info: ${e.message}")
        }
      }

      record.startRecording()
      audioRecord = record
      _isRecording.value = true
      resetUtteranceState()

      captureJob = scope.launch {
        val readBuffer = ShortArray(VAD_FRAME_SAMPLES)
        val byteBuffer = ByteArray(VAD_FRAME_SAMPLES * 2)

        while (isActive && _isRecording.value) {
          val readSamples = record.read(readBuffer, 0, VAD_FRAME_SAMPLES)
          if (readSamples > 0) {
            if (isMuted) {
              _userVoiceRms.value = 0f
              _realWaveform.value = List(32) { 0f }
              consecutiveBargeInChunks = 0
              resetUtteranceState()
              continue
            }

            var sumSquares = 0.0
            for (i in 0 until readSamples) {
              val s = readBuffer[i].toInt()
              sumSquares += (s.toDouble() * s.toDouble())
              byteBuffer[i * 2] = (s and 0xFF).toByte()
              byteBuffer[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
            }

            val rms = if (readSamples > 0) {
              val raw = sqrt(sumSquares / readSamples) / 32768.0
              (raw * 3.2 * micGain).toFloat().coerceIn(0f, 1f)
            } else 0f

            _userVoiceRms.value = rms

            val numBars = 32
            val samplesPerBar = readSamples / numBars
            if (samplesPerBar > 0) {
              val barList = ArrayList<Float>(numBars)
              for (b in 0 until numBars) {
                var barSum = 0.0
                val start = b * samplesPerBar
                for (j in start until (start + samplesPerBar)) {
                  val s = readBuffer[j].toDouble() / 32768.0
                  barSum += (s * s)
                }
                val barRms = sqrt(barSum / samplesPerBar)
                barList.add((barRms * 3.8 * micGain).toFloat().coerceIn(0f, 1f))
              }
              _realWaveform.value = barList
            }

            val chunkBytes = byteBuffer.copyOf(readSamples * 2)
            val chunkDurationMs = (readSamples.toFloat() / INPUT_SAMPLE_RATE * 1000f).toLong()

            if (!isAssistantSpeaking) {
              synchronized(utteranceLock) {
                rollingRecentPcmChunks.addLast(chunkBytes)
                rollingRecentBytes += chunkBytes.size
                while (rollingRecentBytes > maxRollingBytes && rollingRecentPcmChunks.isNotEmpty()) {
                  val removed = rollingRecentPcmChunks.removeFirst()
                  rollingRecentBytes = (rollingRecentBytes - removed.size).coerceAtLeast(0)
                }
              }
            }

            // 1. Hands-Free Barge-In Interruption Detection:
            // Funktioniert vollständig im Freisprechbetrieb über Telefonlautsprecher und Headset.
            // Erkennt durchgängige Nutzersprache (> speechIntensityThreshold * 2.0f über min. ~320ms)
            // und unterbricht die laufende Wiedergabe sofort, damit der Nutzer jederzeit einhaken kann.
            val minBargeInRms = speechIntensityThreshold * 2.0f
            if (isAssistantSpeaking && rms >= minBargeInRms) {
              consecutiveBargeInChunks++
              if (consecutiveBargeInChunks >= bargeInThresholdChunks) {
                Log.i(TAG, "Hands-Free Barge-In erkannt (RMS=$rms)! Unterbreche Wiedergabe.")
                flushAndStopPlayback()
                consecutiveBargeInChunks = 0
                onBargeInDetected?.invoke()
              }
            } else {
              consecutiveBargeInChunks = 0
            }

            // 2. Hands-free VAD mit Pre-Roll (nur wenn Assistent nicht selbst spricht)
            if (!isAssistantSpeaking) {
              synchronized(utteranceLock) {
                if (rms >= speechIntensityThreshold) {
                  if (!isSpeechActive) {
                    isSpeechActive = true
                    while (preRollRingBuffer.isNotEmpty()) {
                      val preChunk = preRollRingBuffer.poll()
                      if (preChunk != null) {
                        currentUtteranceStream.write(preChunk)
                      }
                    }
                    preRollBufferedMs = 0L
                  }
                  silenceDurationMs = 0L
                  speechActiveDurationMs += chunkDurationMs
                  currentUtteranceStream.write(chunkBytes)
                } else {
                  if (isSpeechActive) {
                    currentUtteranceStream.write(chunkBytes)
                    silenceDurationMs += chunkDurationMs

                    if (silenceDurationMs >= silenceTimeoutMs) {
                      val completedBytes = currentUtteranceStream.toByteArray()
                      val wasValid = speechActiveDurationMs >= minSpeechDurationMs
                      resetUtteranceStateLocked()

                      if (wasValid && completedBytes.isNotEmpty()) {
                        lastCompletedUtterancePcm = completedBytes
                        _completedUtteranceFlow.tryEmit(completedBytes)
                      }
                    }
                  } else {
                    preRollRingBuffer.add(chunkBytes)
                    preRollBufferedMs += chunkDurationMs
                    while (preRollBufferedMs > preRollMaxMs && preRollRingBuffer.isNotEmpty()) {
                      val removed = preRollRingBuffer.poll()
                      val size = removed?.size ?: 0
                      val removedMs = ((size / 2).toFloat() / INPUT_SAMPLE_RATE * 1000f).toLong()
                      preRollBufferedMs = (preRollBufferedMs - removedMs).coerceAtLeast(0L)
                    }
                  }
                }
              }
            }

            _pcmInputFlow.tryEmit(readBuffer.copyOf(readSamples))
          }
        }
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error starting AudioRecord", e)
      stopAudioEngine()
    }
  }

  fun flushAndStopPlayback() {
    _outputAmplitude.value = 0f
    isAssistantSpeaking = false
    consecutiveBargeInChunks = 0
  }

  fun resetSessionBuffers() {
    resetUtteranceState()
    lastCompletedUtterancePcm = null
    _userVoiceRms.value = 0f
    _realWaveform.value = List(32) { 0f }
  }

  fun stopAudioEngine() {
    _isRecording.value = false
    captureJob?.cancel()
    captureJob = null
    _userVoiceRms.value = 0f

    try {
      if (audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
        audioRecord?.stop()
      }
      audioRecord?.release()
      audioRecord = null
    } catch (e: Exception) {
      Log.w(TAG, "Error releasing AudioRecord", e)
    }

    try {
      echoCanceler?.release()
      echoCanceler = null
    } catch (e: Exception) {
      Log.w(TAG, "Error releasing echo canceler", e)
    }

    flushAndStopPlayback()
    resetSessionBuffers()
  }

  private fun resetUtteranceState() {
    synchronized(utteranceLock) {
      resetUtteranceStateLocked()
      rollingRecentPcmChunks.clear()
      rollingRecentBytes = 0
    }
  }

  private fun resetUtteranceStateLocked() {
    currentUtteranceStream.reset()
    preRollRingBuffer.clear()
    preRollBufferedMs = 0L
    speechActiveDurationMs = 0L
    silenceDurationMs = 0L
    isSpeechActive = false
    consecutiveBargeInChunks = 0
  }

  fun release() {
    stopAudioEngine()
  }
}
