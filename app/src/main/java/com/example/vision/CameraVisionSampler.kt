package com.example.vision

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

data class TemporalVideoFrame(
  val jpegBytes: ByteArray,
  val capturedAtMs: Long,
  val relativeSecondsAgo: Float,
  val width: Int,
  val height: Int
)

data class VisionStreamTelemetry(
  val isSampling: Boolean = false,
  val targetFps: Float = 2.0f,
  val bufferedFramesCount: Int = 0,
  val selectedKeyframesCount: Int = 0,
  val windowDurationSeconds: Float = 0f,
  val frameResolution: Int = 384
)

class CameraVisionSampler(
  @Volatile var sampleIntervalMs: Long = 500L,
  @Volatile var targetDimension: Int = 384,
  @Volatile var maxRingBufferFrames: Int = 12,
  @Volatile var maxKeyframesPerTurn: Int = 4
) {
  private val tag = "CameraVisionSampler"
  private var samplingJob: Job? = null
  private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

  @Volatile
  var latestRawBitmap: Bitmap? = null

  private data class RawTimestampedFrame(
    val jpegBytes: ByteArray,
    val capturedAtMs: Long,
    val width: Int,
    val height: Int
  )

  private val frameRingBuffer = java.util.Collections.synchronizedList(mutableListOf<RawTimestampedFrame>())

  private val _telemetry = MutableStateFlow(
    VisionStreamTelemetry(
      isSampling = false,
      targetFps = 1000f / sampleIntervalMs.toFloat(),
      frameResolution = targetDimension
    )
  )
  val telemetry: StateFlow<VisionStreamTelemetry> = _telemetry.asStateFlow()

  fun updateConfig(fps: Float, keyframesCount: Int, resolution: Int) {
    val safeFps = fps.coerceIn(0.5f, 4.0f)
    sampleIntervalMs = (1000f / safeFps).toLong().coerceIn(250L, 2000L)
    maxKeyframesPerTurn = keyframesCount.coerceIn(1, 6)
    targetDimension = resolution.coerceIn(256, 512)
    publishTelemetry()
  }

  fun updateFrame(bitmap: Bitmap) {
    latestRawBitmap = bitmap
    if (frameRingBuffer.isEmpty()) {
      processFrameForModel(bitmap)?.let { firstFrame ->
        synchronized(frameRingBuffer) {
          if (frameRingBuffer.isEmpty()) {
            frameRingBuffer.add(firstFrame)
            publishTelemetry()
          }
        }
      }
    }
  }

  fun startContinuousSampling(onSampledFrame: ((ByteArray) -> Unit)? = null) {
    if (samplingJob?.isActive == true) return
    _telemetry.value = _telemetry.value.copy(isSampling = true)
    samplingJob = scope.launch {
      while (isActive) {
        val startTick = System.currentTimeMillis()
        latestRawBitmap?.let { bmp ->
          val processed = processFrameForModel(bmp)
          if (processed != null) {
            synchronized(frameRingBuffer) {
              while (frameRingBuffer.size >= maxRingBufferFrames) {
                frameRingBuffer.removeAt(0)
              }
              frameRingBuffer.add(processed)
            }
            publishTelemetry()
            onSampledFrame?.invoke(processed.jpegBytes)
          }
        }
        val elapsed = System.currentTimeMillis() - startTick
        val waitMs = (sampleIntervalMs - elapsed).coerceAtLeast(80L)
        delay(waitMs)
      }
    }
  }

  fun sampleCurrentFrameNow(): ByteArray? {
    return latestRawBitmap?.let { processFrameForModel(it)?.jpegBytes }
  }

  fun getTemporalFramesSequence(maxKeyframes: Int = maxKeyframesPerTurn): List<TemporalVideoFrame> {
    val now = System.currentTimeMillis()
    val snapshot: List<RawTimestampedFrame> = synchronized(frameRingBuffer) {
      if (frameRingBuffer.isNotEmpty()) {
        val newestAge = now - frameRingBuffer.last().capturedAtMs
        if (newestAge > 250L) {
          latestRawBitmap?.let { bmp ->
            processFrameForModel(bmp)?.let { fresh ->
              while (frameRingBuffer.size >= maxRingBufferFrames) {
                frameRingBuffer.removeAt(0)
              }
              frameRingBuffer.add(fresh)
            }
          }
        }
        frameRingBuffer.toList()
      } else {
        latestRawBitmap?.let { bmp ->
          processFrameForModel(bmp)?.let { listOf(it) }
        } ?: emptyList()
      }
    }

    if (snapshot.isEmpty()) return emptyList()

    val selectedRaw = if (snapshot.size <= maxKeyframes || maxKeyframes <= 1) {
      if (maxKeyframes <= 1) listOf(snapshot.last()) else snapshot
    } else {
      val indices = (0 until maxKeyframes).map { i ->
        ((i.toFloat() / (maxKeyframes - 1).toFloat()) * (snapshot.size - 1)).roundToInt()
          .coerceIn(0, snapshot.size - 1)
      }.distinct()
      indices.map { snapshot[it] }
    }

    val latestTimestamp = selectedRaw.last().capturedAtMs
    val result = selectedRaw.map { raw ->
      val relSec = ((latestTimestamp - raw.capturedAtMs).coerceAtLeast(0L)) / 1000f
      TemporalVideoFrame(
        jpegBytes = raw.jpegBytes,
        capturedAtMs = raw.capturedAtMs,
        relativeSecondsAgo = relSec,
        width = raw.width,
        height = raw.height
      )
    }

    publishTelemetry(selectedCount = result.size)
    return result
  }

  fun getRecentFramesSequence(): List<ByteArray> {
    return getTemporalFramesSequence().map { it.jpegBytes }
  }

  private fun publishTelemetry(selectedCount: Int? = null) {
    synchronized(frameRingBuffer) {
      val count = frameRingBuffer.size
      val spanSec = if (count >= 2) {
        ((frameRingBuffer.last().capturedAtMs - frameRingBuffer.first().capturedAtMs).coerceAtLeast(0L)) / 1000f
      } else if (count == 1) {
        sampleIntervalMs / 1000f
      } else {
        0f
      }
      val keyframes = selectedCount ?: count.coerceAtMost(maxKeyframesPerTurn)
      _telemetry.value = VisionStreamTelemetry(
        isSampling = samplingJob?.isActive == true,
        targetFps = 1000f / sampleIntervalMs.toFloat(),
        bufferedFramesCount = count,
        selectedKeyframesCount = keyframes,
        windowDurationSeconds = spanSec,
        frameResolution = targetDimension
      )
    }
  }

  private fun processFrameForModel(source: Bitmap): RawTimestampedFrame? {
    return try {
      val width = source.width
      val height = source.height
      if (width <= 0 || height <= 0) return null

      val scale = targetDimension.toFloat() / maxOf(width, height)
      val matrix = Matrix().apply { postScale(scale, scale) }

      val scaledBitmap = Bitmap.createBitmap(source, 0, 0, width, height, matrix, true)
      val outW = scaledBitmap.width
      val outH = scaledBitmap.height
      val outputStream = ByteArrayOutputStream()
      scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 84, outputStream)

      if (scaledBitmap != source) {
        scaledBitmap.recycle()
      }

      RawTimestampedFrame(
        jpegBytes = outputStream.toByteArray(),
        capturedAtMs = System.currentTimeMillis(),
        width = outW,
        height = outH
      )
    } catch (e: Exception) {
      Log.e(tag, "Fehler beim Skalieren/Komprimieren des Kamera-Frames", e)
      null
    }
  }

  fun stopSampling() {
    samplingJob?.cancel()
    samplingJob = null
    latestRawBitmap = null
    frameRingBuffer.clear()
    _telemetry.value = VisionStreamTelemetry(
      isSampling = false,
      targetFps = 1000f / sampleIntervalMs.toFloat(),
      bufferedFramesCount = 0,
      selectedKeyframesCount = 0,
      windowDurationSeconds = 0f,
      frameResolution = targetDimension
    )
  }
}
