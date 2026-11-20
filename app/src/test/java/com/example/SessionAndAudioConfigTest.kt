package com.example

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.audio.OmniAudioPlayer
import com.example.engine.Lfm3OmniNativeEngine
import com.example.model.LfmInferenceConfig
import com.example.model.OmniCallState
import com.example.model.OmniMessage
import com.example.model.OmniUiState
import com.example.viewmodel.OmniViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SessionAndAudioConfigTest {

    @Test
    fun defaultConfigHasCameraOffAndOfficialSamplingDefaults() {
        val cfg = LfmInferenceConfig()
        val ui = OmniUiState()

        assertFalse("Camera auto-start on call must default to OFF", cfg.autoStartCameraOnCall)
        assertFalse("Camera active state must default to OFF", ui.isCameraActive)
        assertEquals("SYSTEM_TTS", cfg.omniOutputMode)
        assertEquals(6, cfg.cpuThreads)
        assertEquals(0.0f, cfg.temperature, 0.0001f)
        assertEquals(1.05f, cfg.repetitionPenalty, 0.0001f)
        assertEquals(128, cfg.maxNewTokens)
    }

    @Test
    fun systemTtsModeOmitsAudioDirectiveAndSplitsSentencesCleanly() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val engine = Lfm3OmniNativeEngine(app)
        val cfg = LfmInferenceConfig(
            omniOutputMode = "SYSTEM_TTS",
            systemPrompt = "Respond with interleaved text and audio.\nAntworte kurz auf Deutsch.",
            enableThinking = false
        )

        val prompt = engine.buildNativeOmniPrefixPrompt(
            userText = "",
            history = emptyList(),
            visionCount = 1,
            hasNativeAudio = true,
            inferenceConfig = cfg
        )

        assertFalse(
            "Pure-text SYSTEM_TTS mode must strip audio synthesis directive",
            prompt.contains("Respond with interleaved text and audio.")
        )
        assertFalse(
            "Pure-text SYSTEM_TTS mode must not include Respond with audio.",
            prompt.contains("Respond with audio.")
        )
        assertTrue(
            "Pure-text SYSTEM_TTS mode must include both vision and audio <__media__> tags",
            prompt.contains("<|im_start|>user\n<__media__>\n<__media__>\n<|im_end|>\n")
        )
        assertTrue(
            "Pure-text SYSTEM_TTS mode must append assistant prefix for direct text generation",
            prompt.endsWith("<|im_start|>assistant\n<think>\n</think>\n")
        )

        android.provider.Settings.Secure.putString(
            app.contentResolver,
            "tts_default_synth",
            "com.custom.user.tts"
        )
        val player = OmniAudioPlayer(app)
        assertEquals(
            "Must resolve user's custom Android System Default TTS package from Settings.Secure.TTS_DEFAULT_SYNTH",
            "com.custom.user.tts",
            player.querySystemDefaultTtsPackage()
        )
        val twoSentences = "Hallo! Wie kann ich dir heute helfen?"
        val splitIdx = player.findNextSentenceSplitIndex(twoSentences)
        assertTrue("Must find first sentence boundary at 'Hallo! '", splitIdx > 0)
        assertEquals("Hallo!", twoSentences.substring(0, splitIdx).trim())

        val abbreviationPartial = "Das kostet z.B. zehn"
        assertEquals(
            "Must not split prematurely on German abbreviation 'z.B.'",
            -1,
            player.findNextSentenceSplitIndex(abbreviationPartial)
        )

        val abbreviationWithSpacePartial = "Das kostet z. B. zehn"
        assertEquals(
            "Must not split prematurely on spaced German abbreviation 'z. B.'",
            -1,
            player.findNextSentenceSplitIndex(abbreviationWithSpacePartial)
        )

        val longCommaSentence = "Wenn du heute noch Zeit hast, können wir gerne zusammen das Projekt durchgehen und alles fertigstellen. Und danach machen wir Pause."
        val splitLongSentence = player.findNextSentenceSplitIndex(longCommaSentence)
        assertTrue(splitLongSentence > 0)
        assertEquals(
            "Must not split on commas within long sentences",
            "Wenn du heute noch Zeit hast, können wir gerne zusammen das Projekt durchgehen und alles fertigstellen.",
            longCommaSentence.substring(0, splitLongSentence).trim()
        )

        player.release()
    }

    @Test
    fun audioPlayerPreBufferAndMidTurnRebufferMeet400msBaseline() {
        assertEquals(19200, OmniAudioPlayer.BASE_PREBUFFER_BYTES)
        assertTrue(
            "Mid-turn re-buffer minimum must be >= 400ms (19200 B)",
            OmniAudioPlayer.REBUFFER_MIN_BYTES >= 19200
        )
        assertTrue(
            "Adaptive max pre-buffer must be >= base pre-buffer",
            OmniAudioPlayer.ADAPTIVE_MAX_PREBUFFER_BYTES >= OmniAudioPlayer.BASE_PREBUFFER_BYTES
        )
    }

    @Test
    fun prefixPromptFollowsOfficialLfm3OmniFormatAndSanitizesVoicePlaceholders() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val engine = Lfm3OmniNativeEngine(app)
        val cfg = LfmInferenceConfig(
            omniOutputMode = "MIXED",
            systemPrompt = "Respond with interleaved text and audio.\nAntworte kurz auf Deutsch."
        )

        val audioOnlyPrompt = engine.buildNativeOmniPrefixPrompt(
            userText = "",
            history = listOf(
                OmniMessage(isUser = true, text = "Spracheingabe (2.1s • 16kHz Audio)", isVoiceInput = true),
                OmniMessage(isUser = false, text = "Hallo! Wie geht es dir?")
            ),
            visionCount = 0,
            hasNativeAudio = true,
            inferenceConfig = cfg
        )

        val directiveMatches = Regex("Respond with interleaved text and audio\\.").findAll(audioOnlyPrompt).count()
        assertEquals("Official LiquidAI directive must appear exactly once", 1, directiveMatches)
        assertFalse(audioOnlyPrompt.contains("female audio", ignoreCase = true))
        assertFalse(audioOnlyPrompt.contains("male audio", ignoreCase = true))
        assertFalse(
            "Vision hint must not appear when camera is OFF (visionCount=0)",
            audioOnlyPrompt.contains("Kamerabilder")
        )
        assertFalse(
            "Synthetic audio telemetry string must never leak into ChatML history",
            audioOnlyPrompt.contains("16kHz Audio")
        )
        assertTrue(audioOnlyPrompt.contains("(Gesprochene Frage auf Deutsch)"))
        assertTrue(audioOnlyPrompt.endsWith("<|im_start|>user\n<__media__>\n<|im_end|>\n"))

        val visionPrompt = engine.buildNativeOmniPrefixPrompt(
            userText = "Was siehst du?",
            history = emptyList(),
            visionCount = 2,
            hasNativeAudio = true,
            inferenceConfig = cfg
        )
        assertTrue(visionPrompt.contains("Kamerabilder"))
        assertTrue(visionPrompt.contains("<__media__>\n<__media__>\n<__media__>\nWas siehst du?<|im_end|>\n"))
    }

    @Test
    fun historyPairBuilderUsesResolvedBackgroundTranscriptsAndStripsOrphanGreeting() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val engine = Lfm3OmniNativeEngine(app)
        val cfg = LfmInferenceConfig(omniOutputMode = "MIXED")

        val historyWithGreetingAndResolvedVoice = listOf(
            OmniMessage(
                isUser = false,
                text = "Hallo! Wie kann ich dir helfen?",
                isGreeting = true
            ),
            OmniMessage(
                isUser = true,
                text = "Wie ist das Wetter in Berlin?",
                isVoiceInput = true,
                isTranscriptResolved = true
            ),
            OmniMessage(
                isUser = false,
                text = "In Berlin ist es heute sonnig bei zwanzig Grad."
            ),
            OmniMessage(
                isUser = true,
                text = "Spracheingabe (1.9s • 16kHz Audio)",
                isVoiceInput = true,
                isTranscriptResolved = false
            )
        )

        val prompt = engine.buildNativeOmniPrefixPrompt(
            userText = "",
            history = historyWithGreetingAndResolvedVoice,
            visionCount = 1,
            hasNativeAudio = true,
            inferenceConfig = cfg
        )

        assertFalse(
            "Orphan call greeting must not appear in ChatML history",
            prompt.contains("Hallo! Wie kann ich dir helfen?")
        )
        assertTrue(
            "Background-transcribed voice message must appear as compact real text in history",
            prompt.contains("<|im_start|>user\nWie ist das Wetter in Berlin?<|im_end|>\n")
        )
        assertTrue(
            prompt.contains("<|im_start|>assistant\nIn Berlin ist es heute sonnig bei zwanzig Grad.<|im_end|>\n")
        )
        assertFalse(
            "Resolved voice transcript must not be overwritten by dummy placeholder",
            prompt.contains("(Gesprochene Frage auf Deutsch)")
        )
        val mediaTagCount = Regex("<__media__>").findAll(prompt).count()
        assertEquals(
            "Only the current turn (1 vision frame + 1 audio input) may contain <__media__> tags",
            2,
            mediaTagCount
        )
    }

    @Test
    fun endCallClearsSessionContextMessagesAndCamera() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = OmniViewModel(app)

        assertFalse(vm.uiState.value.isCameraActive)

        vm.toggleCamera()
        assertTrue(vm.uiState.value.isCameraActive)

        vm.sendTextPromptWithVision("Hallo Test")
        assertTrue(vm.uiState.value.messages.isNotEmpty())
        assertEquals("Hallo Test", vm.uiState.value.lastUserTranscript)

        vm.endCall()

        val after = vm.uiState.value
        assertEquals(OmniCallState.IDLE, after.callState)
        assertFalse(after.isCallActive)
        assertFalse("Camera must reset to OFF after call ends", after.isCameraActive)
        assertFalse("Torch must reset to OFF after call ends", after.isTorchOn)
        assertFalse("Model weights must be unloaded from RAM when call ends", after.modelStorageStatus.isModelLoaded)
        assertFalse("Projector weights must be unloaded from RAM when call ends", after.modelStorageStatus.isProjectorLoaded)
        assertEquals(com.example.model.LfmExecutionPhase.OFFLINE, after.executionPhase)
        assertFalse("Diagnostics log sheet must close automatically", after.showDiagnosticsSheet)
        assertTrue("Conversation history must be wiped after call ends", after.messages.isEmpty())
        assertEquals("", after.lastUserTranscript)
        assertEquals("", after.userLiveTranscript)
        assertEquals("", after.lastAiResponse)
        assertEquals("", after.streamingAiResponse)
    }

    @Test
    fun clearHistoryWipesMessagesAndTranscripts() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = OmniViewModel(app)

        vm.sendTextPromptWithVision("Erste Nachricht")
        assertTrue(vm.uiState.value.messages.isNotEmpty())

        vm.clearHistory()

        val after = vm.uiState.value
        assertTrue(after.messages.isEmpty())
        assertEquals("", after.lastUserTranscript)
        assertEquals("", after.userLiveTranscript)
        assertEquals("", after.lastAiResponse)
        assertEquals("", after.streamingAiResponse)
        assertEquals(0, after.spokenCharCount)
    }
}
