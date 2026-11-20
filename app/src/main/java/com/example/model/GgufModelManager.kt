package com.example.model

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

data class GgufModelVariant(
  val fileName: String,
  val displayName: String,
  val estimatedSizeMb: Int,
  val downloadUrl: String,
  val isMultimodalProjector: Boolean = false
) {
  val sizeFormatted: String
    get() = if (estimatedSizeMb >= 1000) {
      "${estimatedSizeMb} MB (${String.format(java.util.Locale.US, "%.2f", estimatedSizeMb / 1024f)} GB)"
    } else {
      "${estimatedSizeMb} MB"
    }
}

data class LocalModelFile(
  val file: File,
  val name: String,
  val sizeMb: Float,
  val isProjector: Boolean,
  val isAudioDecoder: Boolean = false,
  val isAudioTokenizer: Boolean = false,
  val isValidGguf: Boolean = true,
  val isComplete: Boolean = true,
  val validationMessage: String = "Gültig"
)

object GgufModelCatalog {
  const val PRIMARY_HF_REPO = "abliter8-ai/lfm3-2b-omni-abliter8-gguf"

  val mainModelVariants = listOf(
    GgufModelVariant(
      fileName = "LFM3-Omni-2B-Abliter8-Q4_K_M.gguf",
      displayName = "LFM3-Omni-2B-Abliter8 (Q4_K_M • Empfohlen)",
      estimatedSizeMb = 1049,
      downloadUrl = "https://huggingface.co/abliter8-ai/lfm3-2b-omni-abliter8-gguf/resolve/main/LFM3-Omni-2B-Abliter8-Q4_K_M.gguf"
    ),
    GgufModelVariant(
      fileName = "LFM3-Omni-2B-Abliter8-Q6_K.gguf",
      displayName = "LFM3-Omni-2B-Abliter8 (Q6_K • Hohe Präzision)",
      estimatedSizeMb = 1362,
      downloadUrl = "https://huggingface.co/abliter8-ai/lfm3-2b-omni-abliter8-gguf/resolve/main/LFM3-Omni-2B-Abliter8-Q6_K.gguf"
    ),
    GgufModelVariant(
      fileName = "LFM3-Omni-2B-Abliter8-Q8_0.gguf",
      displayName = "LFM3-Omni-2B-Abliter8 (Q8_0 • Max. Qualität)",
      estimatedSizeMb = 1761,
      downloadUrl = "https://huggingface.co/abliter8-ai/lfm3-2b-omni-abliter8-gguf/resolve/main/LFM3-Omni-2B-Abliter8-Q8_0.gguf"
    ),
    GgufModelVariant(
      fileName = "LFM3-Omni-2B-Abliter8-Q4_0.gguf",
      displayName = "LFM3-Omni-2B-Abliter8 (Q4_0 • Schnell)",
      estimatedSizeMb = 1004,
      downloadUrl = "https://huggingface.co/abliter8-ai/lfm3-2b-omni-abliter8-gguf/resolve/main/LFM3-Omni-2B-Abliter8-Q4_0.gguf"
    )
  )

  val mainModelVariant: GgufModelVariant = mainModelVariants.first()

  val projectorVariants = listOf(
    GgufModelVariant(
      fileName = "mmproj-LFM3-Omni-2B-Abliter8-BF16.gguf",
      displayName = "Omni-Projektor BF16 (Abliter8 • Vision + 16kHz Audio In + 24kHz Audio Out)",
      estimatedSizeMb = 906,
      downloadUrl = "https://huggingface.co/abliter8-ai/lfm3-2b-omni-abliter8-gguf/resolve/main/mmproj-LFM3-Omni-2B-Abliter8-BF16.gguf",
      isMultimodalProjector = true
    )
  )

  val defaultVariants = mainModelVariants + projectorVariants

  fun getModelsDir(context: Context): File {
    val dir = File(context.getExternalFilesDir(null), "models")
    if (!dir.exists()) {
      dir.mkdirs()
    }
    return dir
  }

  fun validateGgufFile(file: File, isProjector: Boolean): Triple<Boolean, Boolean, String> {
    if (!file.exists()) return Triple(false, false, "Datei existiert nicht")
    val sizeMb = file.length() / (1024f * 1024f)
    
    val minExpectedMb = if (isProjector) 50f else 500f
    if (sizeMb < minExpectedMb) {
      return Triple(false, false, "Unvollständig (${String.format(java.util.Locale.US, "%.1f", sizeMb)} MB / min ${minExpectedMb.toInt()} MB)")
    }
    
    return try {
      java.io.RandomAccessFile(file, "r").use { raf ->
        val headerBuf = java.nio.ByteBuffer.allocate(32).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        raf.readFully(headerBuf.array())
        val magic = headerBuf.getInt(0)
        if (magic != 0x46554747) {
          return Triple(false, false, "Ungültiger GGUF Header (Magic: 0x${Integer.toHexString(magic)})")
        }
        val version = headerBuf.getInt(4)
        val tensorCount = headerBuf.getLong(8)
        val metadataCount = headerBuf.getLong(16)
        Triple(true, true, "GGUF v$version • $tensorCount Tensoren")
      }
    } catch (e: Exception) {
      Triple(true, true, "GGUF Dateigröße: ${String.format(java.util.Locale.US, "%.1f", sizeMb)} MB")
    }
  }

  private fun readGgufUint32(raf: java.io.RandomAccessFile): Int {
    val buf = ByteArray(4)
    raf.readFully(buf)
    return java.nio.ByteBuffer.wrap(buf).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
  }

  private fun readGgufUint64(raf: java.io.RandomAccessFile): Long {
    val buf = ByteArray(8)
    raf.readFully(buf)
    return java.nio.ByteBuffer.wrap(buf).order(java.nio.ByteOrder.LITTLE_ENDIAN).long
  }

  private fun skipGgufValue(raf: java.io.RandomAccessFile, valType: Int, maxScanBytes: Long): Boolean {
    val skipBytes = when (valType) {
      0, 1, 7 -> 1L // UINT8, INT8, BOOL
      2, 3 -> 2L // UINT16, INT16
      4, 5, 6 -> 4L // UINT32, INT32, FLOAT32
      10, 11, 12 -> 8L // UINT64, INT64, FLOAT64
      8 -> { // STRING
        val len = readGgufUint64(raf)
        if (len < 0 || len > 1024) return false
        len
      }
      else -> return false // Complex types like ARRAY - stop KV scan
    }
    if (raf.filePointer + skipBytes > maxScanBytes) return false
    raf.seek(raf.filePointer + skipBytes)
    return true
  }

  /**
   * Fast scan that only checks filenames and sizes without full GGUF header validation.
   * Suitable for frequent background updates.
   */
  fun scanLocalModelsFast(context: Context): List<LocalModelFile> {
    val list = mutableListOf<LocalModelFile>()
    val scanFile = { file: File ->
      if (file.isFile && file.name.endsWith(".gguf", ignoreCase = true) && file.length() > 5 * 1024 * 1024) {
        val fileName = file.name.lowercase()
        val isProj = fileName.contains("mmproj")
        val isDec = fileName.contains("audio_decoder") || fileName.contains("audio-decoder")
        val isTok = fileName.contains("audio_tokenizer") || fileName.contains("audio-tokenizer")
        list.add(
          LocalModelFile(
            file = file,
            name = file.name,
            sizeMb = file.length() / (1024f * 1024f),
            isProjector = isProj,
            isAudioDecoder = isDec,
            isAudioTokenizer = isTok,
            isValidGguf = true, // Assume valid for fast scan
            isComplete = true
          )
        )
      }
    }
    try {
      getModelsDir(context).listFiles()?.forEach { scanFile(it) }
      val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
      if (downloadsDir != null && downloadsDir.exists() && downloadsDir.canRead()) {
        downloadsDir.listFiles()?.filter { it.name.endsWith(".gguf", ignoreCase = true) }?.forEach { scanFile(it) }
      }
    } catch (e: Exception) {
      Log.w("GgufModelCatalog", "Fast scan encountered error: ${e.message}")
    }
    return list.distinctBy { it.file.name }
  }

  fun scanLocalModels(context: Context): List<LocalModelFile> {
    val list = mutableListOf<LocalModelFile>()
    val scanFile = { file: File ->
      if (file.isFile && file.name.endsWith(".gguf", ignoreCase = true) && file.length() > 5 * 1024 * 1024) {
        val fileName = file.name.lowercase()
        val isProj = fileName.contains("mmproj")
        val isDec = fileName.contains("audio_decoder") || fileName.contains("audio-decoder")
        val isTok = fileName.contains("audio_tokenizer") || fileName.contains("audio-tokenizer")
        
        // Deep validation only for files that look like our model variants
        val isOurModel = defaultVariants.any { it.fileName.equals(file.name, ignoreCase = true) } || 
                         fileName.contains("lfm", ignoreCase = true) ||
                         fileName.contains("patched", ignoreCase = true)
        
        val (isValid, isComplete, valMsg) = if (isOurModel) validateGgufFile(file, isProj || isDec || isTok) 
                                           else Triple(true, true, "GGUF")

        list.add(
          LocalModelFile(
            file = file,
            name = file.name,
            sizeMb = file.length() / (1024f * 1024f),
            isProjector = isProj,
            isAudioDecoder = isDec,
            isAudioTokenizer = isTok,
            isValidGguf = isValid,
            isComplete = isComplete,
            validationMessage = valMsg
          )
        )
      }
    }

    try {
      getModelsDir(context).listFiles()?.forEach { scanFile(it) }
      val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
      if (downloadsDir != null && downloadsDir.exists() && downloadsDir.canRead()) {
        downloadsDir.listFiles()?.filter { it.name.endsWith(".gguf", ignoreCase = true) }?.forEach { scanFile(it) }
      }
    } catch (e: Exception) {
      Log.w("GgufModelCatalog", "Full scan encountered error: ${e.message}")
    }

    return list.distinctBy { it.file.name }
  }

  fun enqueueDownload(context: Context, variant: GgufModelVariant): Long {
    return try {
      val modelsDir = getModelsDir(context)
      val destFile = File(modelsDir, variant.fileName)
      if (destFile.exists()) {
        destFile.delete()
      }

      val request = DownloadManager.Request(Uri.parse(variant.downloadUrl))
        .setTitle(variant.fileName)
        .setDescription("LFM3-Omni Modelldownload")
        .setDestinationUri(Uri.fromFile(destFile))
        .setAllowedOverMetered(true)
        .setAllowedOverRoaming(true)
        .setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE)
        .setRequiresCharging(false)

      try {
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
      } catch (e: Exception) {
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
      }

      val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
      manager?.enqueue(request) ?: -1L
    } catch (e: Exception) {
      Log.e("GgufModelCatalog", "Download enqueue fehlgeschlagen für ${variant.fileName}", e)
      -1L
    }
  }

  fun cancelDownload(context: Context, downloadId: Long) {
    if (downloadId <= 0) return
    try {
      val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
      manager?.remove(downloadId)
    } catch (e: Exception) {
      Log.w("GgufModelCatalog", "Fehler beim Abbrechen von Download $downloadId", e)
    }
  }

  fun deleteLocalModel(model: LocalModelFile): Boolean {
    return try {
      if (model.file.exists()) {
        model.file.delete()
      } else true
    } catch (e: Exception) {
      false
    }
  }

  fun queryDownloadStatus(
    context: Context,
    downloadId: Long,
    fileName: String,
    estimatedSizeMb: Int,
    previousDownloadedBytes: Long,
    previousTimeMs: Long
  ): DownloadProgressInfo {
    val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
      ?: return DownloadProgressInfo(downloadId = downloadId, fileName = fileName, isFailed = true, statusMessage = "DownloadManager nicht verfügbar")

    val query = DownloadManager.Query().setFilterById(downloadId)
    val cursor = manager.query(query)

    if (cursor == null || !cursor.moveToFirst()) {
      cursor?.close()
      return DownloadProgressInfo(downloadId = downloadId, fileName = fileName, isDownloading = false)
    }

    val bytesDownloadedIdx = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
    val bytesTotalIdx = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
    val statusIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
    val reasonIdx = cursor.getColumnIndex(DownloadManager.COLUMN_REASON)

    val bytesDownloaded = if (bytesDownloadedIdx >= 0) cursor.getLong(bytesDownloadedIdx) else 0L
    var totalBytes = if (bytesTotalIdx >= 0) cursor.getLong(bytesTotalIdx) else 0L
    val status = if (statusIdx >= 0) cursor.getInt(statusIdx) else DownloadManager.STATUS_FAILED
    val reason = if (reasonIdx >= 0) cursor.getInt(reasonIdx) else 0

    cursor.close()

    if (totalBytes <= 0) {
      totalBytes = estimatedSizeMb.toLong() * 1024L * 1024L
    }

    val nowMs = System.currentTimeMillis()
    val deltaTimeSec = (nowMs - previousTimeMs) / 1000.0
    val deltaBytes = (bytesDownloaded - previousDownloadedBytes).coerceAtLeast(0L)

    val rawSpeedBytesPerSec = if (deltaTimeSec > 0) (deltaBytes / deltaTimeSec).toLong() else 0L
    val remainingBytes = (totalBytes - bytesDownloaded).coerceAtLeast(0L)
    val etaSeconds = if (rawSpeedBytesPerSec > 0) remainingBytes / rawSpeedBytesPerSec else 0L

    val isDownloading = (status == DownloadManager.STATUS_RUNNING || status == DownloadManager.STATUS_PENDING || status == DownloadManager.STATUS_PAUSED)
    val isCompleted = (status == DownloadManager.STATUS_SUCCESSFUL)
    val isFailed = (status == DownloadManager.STATUS_FAILED)

    val failedReasonText = when {
      reason in 400..599 -> "HTTP $reason (Datei nicht gefunden oder Server-Fehler)"
      reason == DownloadManager.ERROR_INSUFFICIENT_SPACE -> "Nicht genügend Speicherplatz auf dem Gerät"
      reason == DownloadManager.ERROR_DEVICE_NOT_FOUND -> "Speichermedium nicht gefunden"
      reason == DownloadManager.ERROR_FILE_ERROR -> "Dateisystemfehler beim Schreiben"
      reason == DownloadManager.ERROR_HTTP_DATA_ERROR -> "Netzwerkdaten-Übertragungsfehler"
      reason == DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "Zu viele HTTP-Umleitungen"
      reason == DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> "Unbehandelter HTTP-Code ($reason)"
      else -> "Fehlgeschlagen (Code $reason)"
    }

    return DownloadProgressInfo(
      downloadId = downloadId,
      fileName = fileName,
      downloadedBytes = bytesDownloaded,
      totalBytes = totalBytes,
      speedBytesPerSec = rawSpeedBytesPerSec,
      etaSeconds = etaSeconds,
      isDownloading = isDownloading,
      isCompleted = isCompleted,
      isFailed = isFailed,
      statusMessage = when (status) {
        DownloadManager.STATUS_PENDING -> "Warte auf Start..."
        DownloadManager.STATUS_RUNNING -> "Herunterladen..."
        DownloadManager.STATUS_SUCCESSFUL -> "Fertiggestellt"
        DownloadManager.STATUS_FAILED -> failedReasonText
        DownloadManager.STATUS_PAUSED -> when (reason) {
          DownloadManager.PAUSED_WAITING_FOR_NETWORK -> "Warte auf Netzwerk..."
          DownloadManager.PAUSED_WAITING_TO_RETRY -> "Wiederholung..."
          DownloadManager.PAUSED_QUEUED_FOR_WIFI -> "Warte auf WLAN..."
          else -> "Pausiert"
        }
        else -> ""
      }
    )
  }

  /**
   * Robust direct downloader using OkHttp.
   * Directly streams to the models directory with real-time speed, progress and ETA.
   * Automatically handles HTTP redirects (HuggingFace CDN).
   */
  suspend fun downloadDirectly(
    context: Context,
    variant: GgufModelVariant,
    onProgress: (DownloadProgressInfo) -> Unit
  ): Boolean = withContext(Dispatchers.IO) {
    val client = OkHttpClient.Builder()
      .followRedirects(true)
      .followSslRedirects(true)
      .connectTimeout(30, TimeUnit.SECONDS)
      .readTimeout(60, TimeUnit.SECONDS)
      .build()

    val request = Request.Builder()
      .url(variant.downloadUrl)
      .header("User-Agent", "Mozilla/5.0 LFM3-Omni-Android")
      .build()

    val modelsDir = getModelsDir(context)
    val destFile = File(modelsDir, variant.fileName)
    val tempFile = File(modelsDir, "${variant.fileName}.tmp")

    try {
      client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) {
          throw java.io.IOException("HTTP-Fehler ${response.code}: ${response.message}")
        }
        val body = response.body ?: throw java.io.IOException("Leere Server-Antwort")
        val totalBytes = if (body.contentLength() > 0) body.contentLength() else variant.estimatedSizeMb.toLong() * 1024L * 1024L

        var downloadedBytes = 0L
        var lastTimeMs = System.currentTimeMillis()
        var lastDownloadedBytes = 0L
        var speedBytesPerSec = 0L

        body.byteStream().use { input ->
          java.io.FileOutputStream(tempFile).use { output ->
            val buffer = ByteArray(64 * 1024)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
              output.write(buffer, 0, bytesRead)
              downloadedBytes += bytesRead

              val nowMs = System.currentTimeMillis()
              val dtSec = (nowMs - lastTimeMs) / 1000.0
              if (dtSec >= 0.25) {
                val deltaBytes = downloadedBytes - lastDownloadedBytes
                speedBytesPerSec = (deltaBytes / dtSec).toLong()
                lastTimeMs = nowMs
                lastDownloadedBytes = downloadedBytes

                val remainingBytes = (totalBytes - downloadedBytes).coerceAtLeast(0L)
                val eta = if (speedBytesPerSec > 0) remainingBytes / speedBytesPerSec else 0L

                onProgress(
                  DownloadProgressInfo(
                    downloadId = -999L,
                    fileName = variant.fileName,
                    downloadedBytes = downloadedBytes,
                    totalBytes = totalBytes,
                    speedBytesPerSec = speedBytesPerSec,
                    etaSeconds = eta,
                    isDownloading = true,
                    statusMessage = "Herunterladen..."
                  )
                )
              }
            }
          }
        }

        if (destFile.exists()) destFile.delete()
        if (!tempFile.renameTo(destFile)) {
          tempFile.copyTo(destFile, overwrite = true)
          tempFile.delete()
        }

        onProgress(
          DownloadProgressInfo(
            downloadId = -999L,
            fileName = variant.fileName,
            downloadedBytes = totalBytes,
            totalBytes = totalBytes,
            isCompleted = true,
            statusMessage = "Fertiggestellt"
          )
        )
        true
      }
    } catch (e: Exception) {
      if (tempFile.exists()) tempFile.delete()
      throw e
    }
  }
}
