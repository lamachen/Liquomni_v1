package com.example.audio

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Asynchrone Hintergrund-Transkriptions-Pipeline für den Chat-Verlauf (History):
 *
 * Architektur:
 * 1. Sobald der Nutzer fertig gesprochen hat, geht das rohe 16 kHz PCM-Audio SOFORT und
 *    ohne Umweg nativ an `LFM3-Omni` (`mtmd_bitmap_init_from_audio`), damit das Modell
 *    mit minimaler Latenz und vollem akustischen Verständnis im Speech-to-Speech-Modus antwortet.
 * 2. Parallel dazu wird dasselbe 16 kHz PCM-ByteArray über eine `ParcelFileDescriptor.createPipe()`
 *    (`RecognizerIntent.EXTRA_AUDIO_SOURCE`, API 33+) im Hintergrund an Androids
 *    `SpeechRecognizer.createOnDeviceSpeechRecognizer` (bzw. Offline-SpeechRecognizer) gestreamt.
 * 3. Sobald das Text-Transkript vorliegt, wird der Platzhalter in der `ChatHistory` (`messages`)
 *    durch diesen kompakten Text-String ersetzt. Dadurch enthält der Gesprächskontext für
 *    Folgefragen echten semantischen Text statt leerer Audio-Platzhalter oder speicherhungriger
 *    Audio-Blobs.
 */
class AsyncHistoryTranscriber(
    private val context: Context
) {
    companion object {
        private const val TAG = "AsyncHistoryTranscriber"
        private const val SAMPLE_RATE_HZ = 16000
    }

    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private var activeRecognizer: SpeechRecognizer? = null
    private var activeReadPfd: ParcelFileDescriptor? = null

    /**
     * Startet die asynchrone Hintergrund-Transkription eines bereits aufgezeichneten
     * 16 kHz 16-Bit Mono PCM-Arrays über eine `ParcelFileDescriptor`-Pipe.
     */
    fun transcribePcm16kAsync(
        messageId: String,
        pcm16kBytes: ByteArray,
        onLog: ((String) -> Unit)? = null,
        onPartialTranscript: ((messageId: String, partialText: String) -> Unit)? = null,
        onFinalTranscript: (messageId: String, transcriptText: String?) -> Unit
    ) {
        if (pcm16kBytes.size < 3200) {
            onFinalTranscript(messageId, null)
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            // EXTRA_AUDIO_SOURCE via ParcelFileDescriptor erfordert Android 13+ (API 33+)
            onFinalTranscript(messageId, null)
            return
        }

        scope.launch {
            var readSide: ParcelFileDescriptor? = null
            var writeSide: ParcelFileDescriptor? = null
            try {
                cancelActiveSession()

                val pipe = withContext(Dispatchers.IO) {
                    ParcelFileDescriptor.createPipe()
                }
                readSide = pipe[0]
                writeSide = pipe[1]
                activeReadPfd = readSide

                val appCtx = context.applicationContext
                val useOnDevice = try {
                    SpeechRecognizer.isOnDeviceRecognitionAvailable(appCtx)
                } catch (_: Throwable) {
                    false
                }

                val recognizer = try {
                    if (useOnDevice) {
                        SpeechRecognizer.createOnDeviceSpeechRecognizer(appCtx)
                    } else if (SpeechRecognizer.isRecognitionAvailable(appCtx)) {
                        SpeechRecognizer.createSpeechRecognizer(appCtx)
                    } else {
                        null
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "SpeechRecognizer konnte nicht erstellt werden: ${e.message}")
                    null
                }

                if (recognizer == null) {
                    closeQuietly(readSide)
                    closeQuietly(writeSide)
                    onFinalTranscript(messageId, null)
                    return@launch
                }

                activeRecognizer = recognizer
                var latestPartial = ""
                var finished = false

                fun completeOnce(resultText: String?) {
                    if (finished) return
                    finished = true
                    val clean = resultText?.trim()?.takeIf { it.isNotEmpty() }
                    closeQuietly(activeReadPfd)
                    activeReadPfd = null
                    try {
                        recognizer.destroy()
                    } catch (_: Throwable) {
                    }
                    if (activeRecognizer === recognizer) {
                        activeRecognizer = null
                    }
                    if (clean != null) {
                        onLog?.invoke("HISTORY-STT: Sprachaufnahme im Hintergrund transkribiert -> \"$clean\"")
                    }
                    onFinalTranscript(messageId, clean)
                }

                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}

                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val candidate = matches?.firstOrNull()?.trim().orEmpty()
                        if (candidate.isNotEmpty()) {
                            latestPartial = candidate
                            onPartialTranscript?.invoke(messageId, candidate)
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val best = matches?.firstOrNull()?.trim().orEmpty().ifBlank { latestPartial }
                        completeOnce(best.ifBlank { null })
                    }

                    override fun onError(error: Int) {
                        Log.d(TAG, "Hintergrund-STT Hinweis (Code=$error), fallbackPartial='$latestPartial'")
                        completeOnce(latestPartial.ifBlank { null })
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.GERMANY.toLanguageTag())
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, readSide)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, SAMPLE_RATE_HZ)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                }

                recognizer.startListening(intent)

                // Schreibe das aufgezeichnete 16 kHz PCM asynchron auf dem IO-Pool in die Pipe
                val capturedWriteSide = writeSide
                writeSide = null
                launch(Dispatchers.IO) {
                    try {
                        ParcelFileDescriptor.AutoCloseOutputStream(capturedWriteSide).use { out ->
                            val chunkSize = 1024 // 32 ms @ 16 kHz 16-bit Mono
                            var offset = 0
                            while (offset < pcm16kBytes.size) {
                                val len = (pcm16kBytes.size - offset).coerceAtMost(chunkSize)
                                out.write(pcm16kBytes, offset, len)
                                offset += len
                            }
                            out.flush()
                        }
                    } catch (e: Throwable) {
                        Log.d(TAG, "Pipe-Write für Hintergrund-Transkription beendet: ${e.message}")
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Fehler in AsyncHistoryTranscriber: ${e.message}")
                closeQuietly(readSide)
                closeQuietly(writeSide)
                onFinalTranscript(messageId, null)
            }
        }
    }

    private fun cancelActiveSession() {
        closeQuietly(activeReadPfd)
        activeReadPfd = null
        activeRecognizer?.let { rec ->
            try {
                rec.cancel()
                rec.destroy()
            } catch (_: Throwable) {
            }
        }
        activeRecognizer = null
    }

    private fun closeQuietly(pfd: ParcelFileDescriptor?) {
        if (pfd == null) return
        try {
            pfd.close()
        } catch (_: Throwable) {
        }
    }

    fun release() {
        scope.launch {
            cancelActiveSession()
        }
    }
}
