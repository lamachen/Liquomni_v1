package com.example.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Hybrider Echtzeit-Audio-Player für LFM3-Omni:
 *
 * 1. **Satzweiser Android System-TTS Stream (`SYSTEM_TTS`)**:
 *    Nimmt gestreamte Text-Tokens des Modells entgegen, zerlegt sie noch während der Generierung
 *    satzweise an Satzgrenzen und übergibt jeden fertigen Satz sofort per `QUEUE_ADD` an die
 *    auf dem Android-System als Standard eingestellte System-TTS-Engine (`TextToSpeech`).
 *
 * 2. **Nativer 24 kHz PCM Stream (`MIXED` / `AUDIO` / `AUTO`)**:
 *    Spielt die direkt vom LFM3-Omni Depthformer + LiquidCodec generierten 24 kHz PCM-Chunks
 *    mit adaptivem Jitter-Pre-Buffer über `AudioTrack` ab.
 */
class OmniAudioPlayer(
  private val context: Context? = null,
  private val defaultSampleRate: Int = 24000
) {
  companion object {
    const val BASE_PREBUFFER_BYTES = 19200
    const val ADAPTIVE_MAX_PREBUFFER_BYTES = 72000
    const val REBUFFER_MIN_BYTES = 28800

    private val COMMON_ABBREVIATIONS = setOf(
      "z.b.", "bzw.", "ca.", "usw.", "etc.", "dr.", "prof.", "nr.", "inkl.", "vgl.", "ggf.", "d.h.", "u.a.",
      "mrd.", "mio.", "str.", "st.", "abb.", "kap.", "abs.", "art.", "tel.", "std.", "min.", "sek."
    )
  }

  private val tag = "OmniAudioPlayer"
  private var audioTrack: AudioTrack? = null
  private var activeSampleRate: Int = defaultSampleRate
  private var audioManager: AudioManager? = null
  private var currentAudioFocusRequest: AudioFocusRequest? = null

  @Volatile
  private var hasAudioFocus: Boolean = false

  private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

  private sealed class PlaybackCommand {
    data class PlayChunk(
      val pcmData: ByteArray,
      val sampleRate: Int,
      val totalTextLenAtEmit: Int,
      val generationEpoch: Long
    ) : PlaybackCommand()

    data class EndTurn(val generationEpoch: Long) : PlaybackCommand()
  }

  private val playbackQueue = Channel<PlaybackCommand>(Channel.UNLIMITED)

  @Volatile
  private var currentEpoch: Long = 0L

  // Consumer-Thread Zustand für Jitter-Pre-Buffering und Hardware-Drain-Tracking
  private var consumerTurnEpoch: Long = -1L
  private var turnStartedPlayback: Boolean = false
  private var turnTotalFramesWritten: Long = 0L
  private var turnPlaybackHeadStart: Long = 0L
  private var turnInitialUnderrunCount: Int = 0
  private var needsEdgeFadeIn: Boolean = false
  private var currentPreBufferTargetBytes: Int = BASE_PREBUFFER_BYTES
  private val preBufferStream = ByteArrayOutputStream(ADAPTIVE_MAX_PREBUFFER_BYTES)
  private var pendingLastTextLen: Int = 0
  private var pendingSampleRate: Int = defaultSampleRate

  // =========================================================================
  // Android System-TTS (Standard-Systemstimme) Satz-Streaming-Zustand
  // =========================================================================
  @Volatile
  private var systemTts: TextToSpeech? = null
  @Volatile
  private var boundTtsEnginePackage: String? = null
  private val isSystemTtsReady = AtomicBoolean(false)
  private val isSystemTtsInitializing = AtomicBoolean(false)
  private val pendingTtsSentencesBeforeInit = mutableListOf<Pair<String, Long>>()
  private val ttsSentenceLock = Any()
  private val ttsSentenceBuffer = StringBuilder()
  private val activeTtsUtterances = AtomicInteger(0)
  private val ttsTurnStreamFinished = AtomicBoolean(false)
  private val ttsUtteranceCounter = AtomicInteger(0)
  private val utteranceEndCharMap = java.util.concurrent.ConcurrentHashMap<String, Int>()
  private var ttsTotalQueuedChars: Int = 0
  private var ttsAmplitudeAnimatorJob: Job? = null

  @Volatile
  private var ttsSpeechRate: Float = 1.0f
  @Volatile
  private var ttsPitch: Float = 1.0f

  private val _activeSystemTtsEngine = MutableStateFlow("Android Standard-System-TTS wird erkannt...")
  val activeSystemTtsEngine: StateFlow<String> = _activeSystemTtsEngine.asStateFlow()

  private val _audioAmplitude = MutableStateFlow(0f)
  val audioAmplitude: StateFlow<Float> = _audioAmplitude.asStateFlow()

  private val _isSpeaking = MutableStateFlow(false)
  val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

  private val _spokenCharCount = MutableStateFlow(0)
  val spokenCharCount: StateFlow<Int> = _spokenCharCount.asStateFlow()

  private val _availableVoices = MutableStateFlow(listOf("female", "male"))
  val availableVoices: StateFlow<List<String>> = _availableVoices.asStateFlow()

  private val turnAudioBufferLock = Any()
  private val currentTurnAudioBuffer = ByteArrayOutputStream()
  private val maxBufferedBytes = 24000 * 2 * 60 // max 60 Sekunden 24 kHz PCM für Replay

  @Volatile
  var lastTurnSampleRate: Int = defaultSampleRate
    private set

  var onSpeechCompleted: (() -> Unit)? = null

  init {
    audioManager = context?.applicationContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    initAudioTrack(defaultSampleRate)
    initSystemTts(forceReload = false)
    startPlaybackConsumer()
  }

  /**
   * Liest die vom Nutzer in den Android-Systemeinstellungen festgelegte Standard-TTS-Engine
   * (`Settings.Secure.TTS_DEFAULT_SYNTH` bzw. `tts.defaultEngine`) aus.
   */
  fun querySystemDefaultTtsPackage(): String? {
    val appCtx = context?.applicationContext ?: return null
    val fromSecureSettings = try {
      android.provider.Settings.Secure.getString(appCtx.contentResolver, "tts_default_synth")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
    } catch (_: Throwable) {
      null
    }
    if (fromSecureSettings != null) return fromSecureSettings
    return try {
      systemTts?.defaultEngine?.trim()?.takeIf { it.isNotEmpty() }
    } catch (_: Throwable) {
      null
    }
  }

  private fun formatEngineDisplayLabel(enginePkg: String?): String {
    if (enginePkg.isNullOrBlank()) return "Android System-Standard"
    val appCtx = context?.applicationContext ?: return enginePkg
    return try {
      val pm = appCtx.packageManager
      val appInfo = pm.getApplicationInfo(enginePkg, 0)
      val appLabel = pm.getApplicationLabel(appInfo).toString().trim()
      if (appLabel.isNotEmpty() && !appLabel.equals(enginePkg, ignoreCase = true)) {
        "$appLabel ($enginePkg)"
      } else {
        enginePkg
      }
    } catch (_: Throwable) {
      try {
        val matched = systemTts?.engines?.firstOrNull { it.name == enginePkg }
        if (matched != null && !matched.label.isNullOrBlank()) {
          "${matched.label} ($enginePkg)"
        } else {
          enginePkg
        }
      } catch (_: Throwable) {
        enginePkg
      }
    }
  }

  /**
   * Prüft vor jedem Sprach-Turn, ob der Nutzer in den Android-Einstellungen die Standard-TTS-Engine
   * gewechselt hat oder ob die Bindung erneuert werden muss.
   */
  @Synchronized
  fun ensureSystemDefaultTtsBound() {
    // Wenn gerade gesprochen wird oder Sätze in der Warteschlange liegen, Verbindung keinesfalls unterbrechen!
    if (_isSpeaking.value || activeTtsUtterances.get() > 0) return

    val desiredPkg = querySystemDefaultTtsPackage()
    val currentBound = boundTtsEnginePackage
    if (systemTts == null || (!isSystemTtsReady.get() && !isSystemTtsInitializing.get())) {
      initSystemTts(forceReload = true)
      return
    }
    if (desiredPkg != null && currentBound != null && desiredPkg != currentBound && !isSystemTtsInitializing.get()) {
      Log.i(tag, "System-Standard-TTS hat sich geändert ($currentBound -> $desiredPkg). Verbinde neu...")
      initSystemTts(forceReload = true)
    }
  }

  /**
   * Erzwingt eine sofortige Neu-Initialisierung der Android Standard-System-TTS-Engine
   * (z.B. nach Wechsel der bevorzugten Engine in den Android-Einstellungen).
   */
  @Synchronized
  fun refreshSystemDefaultTtsEngine() {
    initSystemTts(forceReload = true)
  }

  @Synchronized
  private fun initSystemTts(forceReload: Boolean = false, explicitFallbackDefault: Boolean = false) {
    val appCtx = context?.applicationContext ?: return
    if (forceReload) {
      try {
        systemTts?.stop()
        systemTts?.shutdown()
      } catch (_: Throwable) {}
      systemTts = null
      isSystemTtsReady.set(false)
      boundTtsEnginePackage = null
    } else if (systemTts != null && (isSystemTtsReady.get() || isSystemTtsInitializing.get())) {
      return
    }

    val preferredEnginePkg = if (explicitFallbackDefault) null else querySystemDefaultTtsPackage()
    isSystemTtsInitializing.set(true)

    try {
      val initListener = TextToSpeech.OnInitListener { status ->
        isSystemTtsInitializing.set(false)
        if (status == TextToSpeech.SUCCESS) {
          val tts = systemTts ?: return@OnInitListener
          val engineDefaultPkg = try {
            tts.defaultEngine?.trim()?.takeIf { it.isNotEmpty() }
          } catch (_: Throwable) {
            null
          }

          // Falls beim ersten Aufruf `preferredEnginePkg` noch null war, aber `tts.defaultEngine`
          // eine spezifische Custom-Engine nennt, direkt explizit an diese Custom-Engine binden:
          if (preferredEnginePkg == null && !explicitFallbackDefault && !engineDefaultPkg.isNullOrBlank()) {
            boundTtsEnginePackage = engineDefaultPkg
          } else {
            boundTtsEnginePackage = preferredEnginePkg ?: engineDefaultPkg
          }

          val activePkg = boundTtsEnginePackage
          val displayLabel = formatEngineDisplayLabel(activePkg)
          _activeSystemTtsEngine.value = displayLabel

          // WICHTIG FÜR CUSTOM-TTS-ENGINES (z.B. Piper, Sherpa-ONNX, RHVoice, Coqui, ElevenLabs):
          // Custom-TTS-Engines bringen ihre im System / in der Custom-TTS-App gewählte Stimme & Sprache
          // bereits fertig konfiguriert mit. Ein erzwungenes `tts.setLanguage(Locale.GERMANY)` überschreibt
          // bei vielen Custom-TTS-Engines die gewählte Custom-Voice oder liefert LANG_NOT_SUPPORTED!
          // Daher Sprache NUR setzen, wenn die Engine überhaupt noch keine aktive Stimme/Sprache besitzt.
          val hasConfiguredVoiceOrLang = try {
            val currentVoice = tts.voice
            val currentLang = try {
              tts.language
            } catch (_: Throwable) {
              null
            }
            currentVoice != null || currentLang != null
          } catch (_: Throwable) {
            false
          }

          if (!hasConfiguredVoiceOrLang) {
            try {
              tts.setLanguage(Locale.getDefault())
            } catch (_: Throwable) {}
          }

          // Sprechtempo und Pitch nur überschreiben, wenn vom Nutzer explizit von 1.0x abweichend eingestellt,
          // damit die nativen Einstellungen der Custom-System-TTS unangetastet bleiben.
          if (kotlin.math.abs(ttsSpeechRate - 1.0f) > 0.01f) {
            try {
              tts.setSpeechRate(ttsSpeechRate)
            } catch (_: Throwable) {}
          }
          if (kotlin.math.abs(ttsPitch - 1.0f) > 0.01f) {
            try {
              tts.setPitch(ttsPitch)
            } catch (_: Throwable) {}
          }

          tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
              _isSpeaking.value = true
              startTtsAmplitudeAnimation()
            }

            override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
              if (utteranceId == null) return
              val sentenceEndChar = utteranceEndCharMap[utteranceId] ?: return
              val approx = (sentenceEndChar - 16 + end).coerceAtMost(sentenceEndChar).coerceAtLeast(0)
              if (approx > _spokenCharCount.value) {
                _spokenCharCount.value = approx
              }
            }

            override fun onDone(utteranceId: String?) {
              handleUtteranceFinished(utteranceId)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
              handleUtteranceFinished(utteranceId)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
              Log.w(tag, "System-TTS ($displayLabel) onError code=$errorCode für utterance=$utteranceId")
              handleUtteranceFinished(utteranceId)
            }
          })

          isSystemTtsReady.set(true)
          Log.i(tag, "Android System-TTS erfolgreich gebunden an Standard-Engine: $displayLabel")

          // Falls bereits Sätze vor Abschluss von onInit eingereiht wurden, jetzt sofort über die Custom-TTS sprechen
          val queuedToFlush: List<Pair<String, Long>>
          synchronized(ttsSentenceLock) {
            queuedToFlush = pendingTtsSentencesBeforeInit.toList()
            pendingTtsSentencesBeforeInit.clear()
          }
          for ((sentence, epoch) in queuedToFlush) {
            if (epoch == currentEpoch) {
              speakSentenceInternal(sentence, epoch, alreadyIncremented = true)
            } else {
              activeTtsUtterances.decrementAndGet()
            }
          }
        } else {
          Log.w(tag, "System-TTS Initialisierung mit Engine '$preferredEnginePkg' meldete Status=$status")
          if (!explicitFallbackDefault && preferredEnginePkg != null) {
            Log.i(tag, "Versuche Fallback auf Standard-TextToSpeech-Konstruktor...")
            initSystemTts(forceReload = true, explicitFallbackDefault = true)
          } else {
            _activeSystemTtsEngine.value = "System-TTS Fehler (Status $status)"
          }
        }
      }

      // Wenn eine bevorzugte System-TTS-Engine (`tts_default_synth`) konfiguriert ist,
      // übergeben wir deren Paketnamen explizit an den 3-Argument-Konstruktor, damit Android
      // zwingend diese Custom-TTS-Engine bindet.
      systemTts = if (!preferredEnginePkg.isNullOrBlank()) {
        Log.i(tag, "Binde explizit an Android System-Standard-TTS Engine: $preferredEnginePkg")
        TextToSpeech(appCtx, initListener, preferredEnginePkg)
      } else {
        TextToSpeech(appCtx, initListener)
      }
    } catch (e: Throwable) {
      isSystemTtsInitializing.set(false)
      Log.w(tag, "Android System-TTS konnte nicht initialisiert werden (z.B. JVM-Testumgebung): ${e.message}")
    }
  }

  private fun handleUtteranceFinished(utteranceId: String?) {
    if (utteranceId != null) {
      val endChars = utteranceEndCharMap.remove(utteranceId)
      if (endChars != null && endChars > _spokenCharCount.value) {
        _spokenCharCount.value = endChars
      }
    }
    val remaining = activeTtsUtterances.decrementAndGet().coerceAtLeast(0)
    activeTtsUtterances.set(remaining)
    val hasPending = synchronized(ttsSentenceLock) { pendingTtsSentencesBeforeInit.isNotEmpty() }
    if (remaining == 0 && ttsTurnStreamFinished.get() && !hasPending) {
      stopTtsAmplitudeAnimation()
      abandonTurnAudioFocus()
      _isSpeaking.value = false
      _audioAmplitude.value = 0f
      onSpeechCompleted?.invoke()
    }
  }

  private fun startTtsAmplitudeAnimation() {
    if (ttsAmplitudeAnimatorJob?.isActive == true) return
    ttsAmplitudeAnimatorJob = scope.launch {
      var tick = 0
      while (isActive && _isSpeaking.value && activeTtsUtterances.get() > 0) {
        val mod = 0.32f + 0.22f * sin(tick * 0.45f) + 0.12f * sin(tick * 1.1f)
        _audioAmplitude.value = mod.coerceIn(0.12f, 0.85f)
        tick++
        delay(45L)
      }
    }
  }

  private fun stopTtsAmplitudeAnimation() {
    ttsAmplitudeAnimatorJob?.cancel()
    ttsAmplitudeAnimatorJob = null
  }

  fun updateTtsConfig(speechRate: Float, pitch: Float) {
    val prevRate = ttsSpeechRate
    val prevPitch = ttsPitch
    ttsSpeechRate = speechRate.coerceIn(0.5f, 2.0f)
    ttsPitch = pitch.coerceIn(0.5f, 2.0f)
    try {
      if (kotlin.math.abs(ttsSpeechRate - 1.0f) > 0.01f || kotlin.math.abs(prevRate - ttsSpeechRate) > 0.01f) {
        systemTts?.setSpeechRate(ttsSpeechRate)
      }
      if (kotlin.math.abs(ttsPitch - 1.0f) > 0.01f || kotlin.math.abs(prevPitch - ttsPitch) > 0.01f) {
        systemTts?.setPitch(ttsPitch)
      }
    } catch (_: Throwable) {}
  }

  @Volatile
  var enableSentenceSplitting: Boolean = true

  /**
   * Nimmt während der Modell-Generierung neue Text-Deltas entgegen.
   * Wenn enableSentenceSplitting aktiv ist, wird satzweise an Satzgrenzen gesprochen.
   * Wenn deaktiviert, wird der gesamte Text-Stream direkt ohne Satz-Unterbrechungen am Stück gesprochen.
   */
  fun enqueueTtsTextDelta(delta: String) {
    if (delta.isEmpty()) return
    val epoch = currentEpoch

    if (!enableSentenceSplitting) {
      synchronized(ttsSentenceLock) {
        ttsSentenceBuffer.append(delta)
      }
      return
    }

    val completedSentences = mutableListOf<String>()

    synchronized(ttsSentenceLock) {
      ttsSentenceBuffer.append(delta)
      while (true) {
        val splitIdx = findNextSentenceSplitIndex(ttsSentenceBuffer)
        if (splitIdx == -1) break
        val candidate = ttsSentenceBuffer.substring(0, splitIdx).trim()
        ttsSentenceBuffer.delete(0, splitIdx)
        if (candidate.isNotEmpty()) {
          completedSentences.add(candidate)
        }
      }
    }

    for (sentence in completedSentences) {
      queueSentenceToSystemTts(sentence, epoch)
    }
  }

  /**
   * Wird am Ende eines Modell-Turns aufgerufen und spricht den verbliebenen Text aus.
   */
  fun flushTtsTextStream() {
    val epoch = currentEpoch
    val remainingSentence: String
    synchronized(ttsSentenceLock) {
      remainingSentence = ttsSentenceBuffer.toString().trim()
      ttsSentenceBuffer.setLength(0)
    }
    if (remainingSentence.isNotEmpty()) {
      queueSentenceToSystemTts(remainingSentence, epoch)
    }
    ttsTurnStreamFinished.set(true)
    val hasPending = synchronized(ttsSentenceLock) { pendingTtsSentencesBeforeInit.isNotEmpty() }
    if (activeTtsUtterances.get() <= 0 && !hasPending) {
      stopTtsAmplitudeAnimation()
      abandonTurnAudioFocus()
      _isSpeaking.value = false
      _audioAmplitude.value = 0f
      onSpeechCompleted?.invoke()
    }
  }

  /**
   * Spricht einen kompletten Text (z. B. beim Replay oder Stimmtest im System-TTS-Modus)
   * satzweise über die Android Standard-System-TTS.
   */
  fun speakFullTextWithSystemTts(text: String) {
    val clean = text.trim()
    if (clean.isEmpty()) return
    stop()
    beginNewResponseTurn()
    enqueueTtsTextDelta(clean)
    flushTtsTextStream()
  }

  /**
   * Erkennt echte Satzgrenzen (`.`, `!`, `?`, `\n\n`) für vollständige, flüssige Satz-Sprachausgabe.
   * Teilt NIEMALS an Kommas, Doppelpunkten oder Gedankenstrichen und schützt Abkürzungen (`z. B.`, `ca.`, `Nr. 1`)
   * sowie Ordnungszahlen vor fehlerhaften Satzabbrüchen.
   */
  internal fun findNextSentenceSplitIndex(buffer: CharSequence): Int {
    val len = buffer.length
    if (len < 4) return -1

    for (i in 0 until len) {
      val ch = buffer[i]

      // 1. Absatzgrenze: Doppeltes Newline oder Newline nach Satzende-Zeichen
      if (ch == '\n') {
        val prefix = buffer.subSequence(0, i + 1).toString().trim()
        if (prefix.length >= 3) {
          val isDoubleNewline = (i + 1 < len && buffer[i + 1] == '\n')
          val endsWithPunct = prefix.isNotEmpty() && (prefix.last() == '.' || prefix.last() == '!' || prefix.last() == '?')
          if (isDoubleNewline || endsWithPunct) {
            var nextNonSpace = i + 1
            while (nextNonSpace < len && buffer[nextNonSpace].isWhitespace()) {
              nextNonSpace++
            }
            return nextNonSpace
          }
        }
      }

      // 2. Echte Satzende-Zeichen: '.' '!' '?'
      if (ch == '.' || ch == '!' || ch == '?') {
        // Punkt nach Ziffer (z.B. "3.14" oder "1.") ist Zahl oder Aufzählung -> niemals trennen
        if (ch == '.' && i > 0 && buffer[i - 1].isDigit()) {
          continue
        }

        // Muss von Leerzeichen oder schließendem Anführungszeichen gefolgt sein
        val hasFollowingWhitespace = (i + 1 < len && buffer[i + 1].isWhitespace())
        val hasFollowingQuoteAndSpace = (i + 2 < len &&
          (buffer[i + 1] == '"' || buffer[i + 1] == '\'' || buffer[i + 1] == '“' || buffer[i + 1] == '”') &&
          buffer[i + 2].isWhitespace())

        if (hasFollowingWhitespace || hasFollowingQuoteAndSpace) {
          val candidate = buffer.subSequence(0, i + 1).toString().trim()
          if (candidate.length < 3) continue

          if (ch == '.') {
            val lastWord = candidate.substringAfterLast(' ').lowercase(Locale.ROOT)
            // Einzelne Buchstaben vor Punkt (z.B. "z." oder "b." in "z. B.") schützen
            if (lastWord.length <= 2 && lastWord.endsWith('.')) {
              continue
            }
            // Bekannte deutsche und internationale Abkürzungen schützen
            if (lastWord in COMMON_ABBREVIATIONS) {
              continue
            }
          }

          var nextIndex = i + 1
          if (hasFollowingQuoteAndSpace) {
            nextIndex++
          }
          while (nextIndex < len && buffer[nextIndex].isWhitespace()) {
            nextIndex++
          }
          return nextIndex
        }
      }
    }
    return -1
  }

  private fun queueSentenceToSystemTts(sentence: String, epoch: Long) {
    if (epoch != currentEpoch) return
    var cleanSentence = sentence
      .replace(Regex("[*_#`~]+"), "")
      .trim()
    if (cleanSentence.isEmpty()) return

    // Garantiere saubere Satzende-Interpunktion für natürliche Sprachmelodie und Abschluss ohne Wort-Abbrüche
    val lastChar = cleanSentence.last()
    if (lastChar != '.' && lastChar != '!' && lastChar != '?') {
      cleanSentence = "$cleanSentence."
    }

    ensureSystemDefaultTtsBound()
    activeTtsUtterances.incrementAndGet()
    _isSpeaking.value = true

    if (!isSystemTtsReady.get()) {
      synchronized(ttsSentenceLock) {
        pendingTtsSentencesBeforeInit.add(cleanSentence to epoch)
      }
      return
    }
    speakSentenceInternal(cleanSentence, epoch, alreadyIncremented = true)
  }

  private fun speakSentenceInternal(cleanSentence: String, epoch: Long, alreadyIncremented: Boolean = false) {
    if (epoch != currentEpoch) {
      if (alreadyIncremented) activeTtsUtterances.decrementAndGet()
      return
    }
    val tts = systemTts ?: run {
      if (alreadyIncremented) activeTtsUtterances.decrementAndGet()
      return
    }
    requestTurnAudioFocusOnce()

    val utteranceId = "lfm3_tts_${epoch}_${ttsUtteranceCounter.incrementAndGet()}"
    ttsTotalQueuedChars += cleanSentence.length + 1
    utteranceEndCharMap[utteranceId] = ttsTotalQueuedChars
    if (!alreadyIncremented) {
      activeTtsUtterances.incrementAndGet()
    }
    _isSpeaking.value = true
    startTtsAmplitudeAnimation()

    val params = Bundle().apply {
      putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
      putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
      putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
    }

    // Nachlaufendes Leerzeichen gibt dem akustischen Modell saubere Ausklingzeit ohne Silbenabschneiden
    val ttsSpeechText = "$cleanSentence "
    // QUEUE_FLUSH sorgt dafür, dass Supertonic3 (und Custom-TTS) den vorherigen Satz sauber ausklingen lässt
    // und nicht mit dem nächsten Satz überlappt oder abgeschnitten wird.
    val res = tts.speak(ttsSpeechText, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
    if (res == TextToSpeech.ERROR) {
      Log.w(tag, "System-TTS speak() gab ERROR zurück für Satz: '$cleanSentence'")
      handleUtteranceFinished(utteranceId)
    }
  }

  private fun currentUnsignedHead(track: AudioTrack): Long {
    return try {
      track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
    } catch (_: Throwable) {
      0L
    }
  }

  private fun applyEdgeFadeIn(pcmData: ByteArray, sampleRate: Int, fadeMs: Int) {
    val totalSamples = pcmData.size / 2
    val fadeSamples = ((sampleRate * fadeMs) / 1000).coerceAtMost(totalSamples)
    if (fadeSamples <= 1) return
    for (i in 0 until fadeSamples) {
      val idx = i * 2
      val low = pcmData[idx].toInt() and 0xFF
      val high = pcmData[idx + 1].toInt()
      val sample = ((high shl 8) or low).toShort().toInt()
      val gain = i.toFloat() / fadeSamples.toFloat()
      val scaled = (sample * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
      pcmData[idx] = (scaled and 0xFF).toByte()
      pcmData[idx + 1] = ((scaled shr 8) and 0xFF).toByte()
    }
  }

  private fun startPlaybackConsumer() {
    scope.launch {
      for (cmd in playbackQueue) {
        when (cmd) {
          is PlaybackCommand.PlayChunk -> {
            if (cmd.generationEpoch != currentEpoch) continue
            handlePlayChunk(cmd)
          }
          is PlaybackCommand.EndTurn -> {
            if (cmd.generationEpoch != currentEpoch) continue
            drainAndComplete(cmd.generationEpoch)
          }
        }
      }
    }
  }

  private fun requestTurnAudioFocusOnce() {
    if (hasAudioFocus) return
    try {
      val am = audioManager ?: return
      if (am.mode != AudioManager.MODE_NORMAL) {
        am.mode = AudioManager.MODE_NORMAL
      }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val attrs = AudioAttributes.Builder()
          .setUsage(AudioAttributes.USAGE_MEDIA)
          .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
          .build()
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
          .setAudioAttributes(attrs)
          .build()
        currentAudioFocusRequest = req
        am.requestAudioFocus(req)
      }
      hasAudioFocus = true
    } catch (e: Exception) {
      Log.d(tag, "Audio focus request info: ${e.message}")
    }
  }

  private fun abandonTurnAudioFocus() {
    if (!hasAudioFocus) return
    try {
      val am = audioManager ?: return
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        currentAudioFocusRequest?.let { req ->
          am.abandonAudioFocusRequest(req)
        }
      }
    } catch (e: Exception) {
      Log.d(tag, "Audio focus abandon info: ${e.message}")
    } finally {
      currentAudioFocusRequest = null
      hasAudioFocus = false
    }
  }

  @Synchronized
  private fun initAudioTrack(targetSampleRate: Int) {
    try {
      audioTrack?.let {
        try {
          it.pause()
          it.flush()
          it.release()
        } catch (_: Throwable) {}
      }

      val minBufferSize = AudioTrack.getMinBufferSize(
        targetSampleRate,
        AudioFormat.CHANNEL_OUT_MONO,
        AudioFormat.ENCODING_PCM_16BIT
      ).coerceAtLeast(4096)

      val ringBufferBytes = (minBufferSize * 8).coerceAtLeast(targetSampleRate * 2 * 2)

      val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

      val format = AudioFormat.Builder()
        .setSampleRate(targetSampleRate)
        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
        .build()

      audioTrack = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        AudioTrack.Builder()
          .setAudioAttributes(attributes)
          .setAudioFormat(format)
          .setBufferSizeInBytes(ringBufferBytes)
          .setTransferMode(AudioTrack.MODE_STREAM)
          .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_NONE)
          .build()
      } else {
        @Suppress("DEPRECATION")
        AudioTrack(
          attributes,
          format,
          ringBufferBytes,
          AudioTrack.MODE_STREAM,
          AudioManager.AUDIO_SESSION_ID_GENERATE
        )
      }
      activeSampleRate = targetSampleRate
      turnPlaybackHeadStart = 0L
      Log.i(tag, "AudioTrack initialisiert: ${targetSampleRate}Hz Mono | RingBuffer=${ringBufferBytes}B")
    } catch (e: Exception) {
      Log.e(tag, "Failed to initialize ${targetSampleRate}Hz AudioTrack", e)
    }
  }

  @Synchronized
  fun beginNewResponseTurn() {
    currentEpoch++
    synchronized(turnAudioBufferLock) {
      currentTurnAudioBuffer.reset()
    }
    synchronized(ttsSentenceLock) {
      ttsSentenceBuffer.setLength(0)
      pendingTtsSentencesBeforeInit.clear()
    }
    utteranceEndCharMap.clear()
    activeTtsUtterances.set(0)
    ttsTurnStreamFinished.set(false)
    ttsTotalQueuedChars = 0
    stopTtsAmplitudeAnimation()
    try {
      systemTts?.stop()
    } catch (_: Throwable) {}
    ensureSystemDefaultTtsBound()
    _spokenCharCount.value = 0
    _audioAmplitude.value = 0f
    _isSpeaking.value = false
    try {
      audioTrack?.pause()
      audioTrack?.flush()
    } catch (_: Throwable) {}
  }

  fun playPcmChunk(pcmData: ByteArray, sampleRate: Int = 24000, currentTextLength: Int = 0) {
    if (pcmData.isEmpty()) return
    val epoch = currentEpoch
    synchronized(turnAudioBufferLock) {
      lastTurnSampleRate = sampleRate
      if (currentTurnAudioBuffer.size() + pcmData.size <= maxBufferedBytes) {
        currentTurnAudioBuffer.write(pcmData)
      }
    }
    _isSpeaking.value = true
    playbackQueue.trySend(
      PlaybackCommand.PlayChunk(
        pcmData = pcmData,
        sampleRate = sampleRate,
        totalTextLenAtEmit = currentTextLength,
        generationEpoch = epoch
      )
    )
  }

  fun finishAudioStream() {
    val epoch = currentEpoch
    playbackQueue.trySend(PlaybackCommand.EndTurn(epoch))
  }

  fun getBufferedTurnAudio(): ByteArray? {
    synchronized(turnAudioBufferLock) {
      val bytes = currentTurnAudioBuffer.toByteArray()
      return if (bytes.isNotEmpty()) bytes else null
    }
  }

  fun replayPcm(pcmData: ByteArray, sampleRate: Int = 24000, totalTextLength: Int = 0) {
    if (pcmData.isEmpty()) return
    stop()
    beginNewResponseTurn()
    val chunkBytes = (sampleRate / 10) * 2
    var offset = 0
    while (offset < pcmData.size) {
      val end = (offset + chunkBytes).coerceAtMost(pcmData.size)
      val slice = pcmData.copyOfRange(offset, end)
      val progressChars = if (totalTextLength > 0) {
        ((end.toFloat() / pcmData.size.toFloat()) * totalTextLength).toInt()
      } else 0
      playPcmChunk(slice, sampleRate, progressChars)
      offset = end
    }
    finishAudioStream()
  }

  private fun resetConsumerTurnState(epoch: Long, sampleRate: Int) {
    consumerTurnEpoch = epoch
    turnStartedPlayback = false
    turnTotalFramesWritten = 0L
    needsEdgeFadeIn = false
    preBufferStream.reset()
    pendingLastTextLen = 0
    pendingSampleRate = sampleRate
    currentPreBufferTargetBytes = BASE_PREBUFFER_BYTES
    val track = audioTrack
    turnPlaybackHeadStart = if (track != null) currentUnsignedHead(track) else 0L
    turnInitialUnderrunCount = if (track != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
      track.underrunCount
    } else 0
  }

  private suspend fun handlePlayChunk(cmd: PlaybackCommand.PlayChunk) {
    if (cmd.generationEpoch != consumerTurnEpoch) {
      resetConsumerTurnState(cmd.generationEpoch, cmd.sampleRate)
    }

    pendingSampleRate = cmd.sampleRate
    if (cmd.totalTextLenAtEmit > pendingLastTextLen) {
      pendingLastTextLen = cmd.totalTextLenAtEmit
    }

    if (!turnStartedPlayback) {
      preBufferStream.write(cmd.pcmData)

      while (true) {
        val peek = playbackQueue.tryReceive().getOrNull() ?: break
        when (peek) {
          is PlaybackCommand.PlayChunk -> {
            if (peek.generationEpoch != cmd.generationEpoch) continue
            pendingSampleRate = peek.sampleRate
            if (peek.totalTextLenAtEmit > pendingLastTextLen) {
              pendingLastTextLen = peek.totalTextLenAtEmit
            }
            preBufferStream.write(peek.pcmData)
          }
          is PlaybackCommand.EndTurn -> {
            if (peek.generationEpoch == cmd.generationEpoch) {
              flushPreBufferAndPlay(cmd.generationEpoch)
              drainAndComplete(cmd.generationEpoch)
              return
            }
          }
        }
      }

      if (preBufferStream.size() >= currentPreBufferTargetBytes) {
        flushPreBufferAndPlay(cmd.generationEpoch)
      }
      return
    }

    val batch = ByteArrayOutputStream(cmd.pcmData.size * 2)
    batch.write(cmd.pcmData)
    var seenEndTurn = false
    while (batch.size() < 24576) {
      val nextResult = playbackQueue.tryReceive().getOrNull() ?: break
      when (nextResult) {
        is PlaybackCommand.PlayChunk -> {
          if (nextResult.generationEpoch != cmd.generationEpoch) continue
          if (nextResult.totalTextLenAtEmit > pendingLastTextLen) {
            pendingLastTextLen = nextResult.totalTextLenAtEmit
          }
          batch.write(nextResult.pcmData)
        }
        is PlaybackCommand.EndTurn -> {
          if (nextResult.generationEpoch == cmd.generationEpoch) {
            seenEndTurn = true
            break
          }
        }
      }
    }

    val batchBytes = batch.toByteArray()
    if (!seenEndTurn) {
      val track = audioTrack
      if (track != null && turnTotalFramesWritten > 0L) {
        val currentHead = currentUnsignedHead(track)
        val playedFrames = (currentHead - turnPlaybackHeadStart).coerceAtLeast(0L)
        val queuedFrames = (turnTotalFramesWritten - playedFrames).coerceAtLeast(0L)
        val currentUnderruns = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
          track.underrunCount
        } else {
          turnInitialUnderrunCount
        }
        val underrunOccurred = currentUnderruns > turnInitialUnderrunCount

        if (underrunOccurred || (queuedFrames <= 480L && batchBytes.size < REBUFFER_MIN_BYTES)) {
          try {
            track.pause()
          } catch (_: Throwable) {}
          if (underrunOccurred) {
            turnInitialUnderrunCount = currentUnderruns
          }
          currentPreBufferTargetBytes = (currentPreBufferTargetBytes + 14400)
            .coerceIn(REBUFFER_MIN_BYTES, ADAPTIVE_MAX_PREBUFFER_BYTES)
          turnStartedPlayback = false
          needsEdgeFadeIn = true
          preBufferStream.write(batchBytes)
          if (preBufferStream.size() >= currentPreBufferTargetBytes) {
            flushPreBufferAndPlay(cmd.generationEpoch)
          }
          return
        }
      }
    }

    writeChunkBlocking(batchBytes, pendingSampleRate, pendingLastTextLen, cmd.generationEpoch)
    if (seenEndTurn) {
      drainAndComplete(cmd.generationEpoch)
    }
  }

  private fun flushPreBufferAndPlay(generationEpoch: Long) {
    if (generationEpoch != currentEpoch || preBufferStream.size() == 0) return
    val buffered = preBufferStream.toByteArray()
    preBufferStream.reset()
    val fadeMs = if (needsEdgeFadeIn) 4 else 2
    applyEdgeFadeIn(buffered, pendingSampleRate, fadeMs)
    needsEdgeFadeIn = false
    turnStartedPlayback = true
    requestTurnAudioFocusOnce()
    writeChunkBlocking(buffered, pendingSampleRate, pendingLastTextLen, generationEpoch)
  }

  private fun writeChunkBlocking(
    pcmData: ByteArray,
    chunkSampleRate: Int,
    totalTextLenAtEmit: Int,
    generationEpoch: Long
  ) {
    try {
      if (generationEpoch != currentEpoch || pcmData.isEmpty()) return
      if (audioTrack == null || activeSampleRate != chunkSampleRate) {
        initAudioTrack(chunkSampleRate)
      }
      val track = audioTrack ?: return

      if (turnTotalFramesWritten == 0L) {
        turnPlaybackHeadStart = currentUnsignedHead(track)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
          turnInitialUnderrunCount = track.underrunCount
        }
      }

      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && turnTotalFramesWritten > 0L) {
        val currentUnderruns = track.underrunCount
        if (currentUnderruns > turnInitialUnderrunCount) {
          turnInitialUnderrunCount = currentUnderruns
          currentPreBufferTargetBytes = (currentPreBufferTargetBytes + 14400)
            .coerceIn(REBUFFER_MIN_BYTES, ADAPTIVE_MAX_PREBUFFER_BYTES)
        }
      }

      var sumSquares = 0.0
      val shortCount = pcmData.size / 2
      var i = 0
      while (i + 1 < pcmData.size) {
        val low = pcmData[i].toInt() and 0xFF
        val high = pcmData[i + 1].toInt()
        val sample = (high shl 8) or low
        sumSquares += sample.toDouble() * sample.toDouble()
        i += 2
      }
      if (shortCount > 0) {
        val rms = sqrt(sumSquares / shortCount) / 32768.0
        _audioAmplitude.value = (rms * 3.2).toFloat().coerceIn(0.05f, 1.0f)
      }

      if (totalTextLenAtEmit > _spokenCharCount.value) {
        _spokenCharCount.value = totalTextLenAtEmit
      }

      var written = 0
      var playStartedHere = track.playState == AudioTrack.PLAYSTATE_PLAYING
      while (written < pcmData.size && generationEpoch == currentEpoch) {
        val chunkLen = (pcmData.size - written).coerceAtMost(16384)
        val res = track.write(pcmData, written, chunkLen, AudioTrack.WRITE_BLOCKING)
        if (res <= 0) break
        written += res
        turnTotalFramesWritten += (res / 2)
        if (!playStartedHere) {
          track.play()
          playStartedHere = true
        }
      }
      _isSpeaking.value = true
    } catch (e: Exception) {
      Log.w(tag, "Error writing native 24kHz PCM chunk to AudioTrack: ${e.message}")
    }
  }

  private suspend fun drainAndComplete(generationEpoch: Long) {
    try {
      if (generationEpoch != currentEpoch) return
      if (preBufferStream.size() > 0) {
        flushPreBufferAndPlay(generationEpoch)
      }
      val track = audioTrack
      if (track != null && track.playState == AudioTrack.PLAYSTATE_PLAYING) {
        val playedFrames = (currentUnsignedHead(track) - turnPlaybackHeadStart).coerceAtLeast(0L)
        val remainingFrames = (turnTotalFramesWritten - playedFrames).coerceAtLeast(0L)
        val expectedDrainMs = ((remainingFrames * 1000L) / activeSampleRate.coerceAtLeast(8000)).coerceAtMost(8000L)
        try {
          track.stop()
        } catch (_: Throwable) {}

        val startWaitMs = System.currentTimeMillis()
        while (generationEpoch == currentEpoch && System.currentTimeMillis() - startWaitMs < expectedDrainMs + 250L) {
          val headDelta = (currentUnsignedHead(track) - turnPlaybackHeadStart).coerceAtLeast(0L)
          if (headDelta >= turnTotalFramesWritten) break
          delay(25L)
        }
      }
    } catch (_: Throwable) {
    } finally {
      preBufferStream.reset()
      if (generationEpoch == currentEpoch) {
        abandonTurnAudioFocus()
        _isSpeaking.value = false
        _audioAmplitude.value = 0f
        onSpeechCompleted?.invoke()
      }
    }
  }

  @Synchronized
  fun stop() {
    currentEpoch++
    synchronized(ttsSentenceLock) {
      ttsSentenceBuffer.setLength(0)
      pendingTtsSentencesBeforeInit.clear()
    }
    utteranceEndCharMap.clear()
    activeTtsUtterances.set(0)
    ttsTurnStreamFinished.set(false)
    stopTtsAmplitudeAnimation()
    try {
      systemTts?.stop()
    } catch (_: Throwable) {}
    _isSpeaking.value = false
    _audioAmplitude.value = 0f
    abandonTurnAudioFocus()
    try {
      audioTrack?.pause()
      audioTrack?.flush()
    } catch (e: Exception) {
      Log.w(tag, "Error stopping AudioTrack", e)
    }
  }

  fun release() {
    try {
      stop()
      playbackQueue.close()
      try {
        systemTts?.shutdown()
      } catch (_: Throwable) {}
      systemTts = null
      audioTrack?.release()
      audioTrack = null
    } catch (e: Exception) {
      Log.w(tag, "Error releasing OmniAudioPlayer", e)
    }
  }
}
