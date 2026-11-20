package com.example.engine

import android.util.Log
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.ByteByReference
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.LongByReference
import com.sun.jna.ptr.PointerByReference

/**
 * Direkte JNA-Bindung an `libllama.so`, gebaut von `native/build_engine.sh`
 * (llama.cpp + ggml + libmtmd inkl. LFM3-Omni Audio-Gen-Helfer + `native/omni_shim.cpp`).
 *
 * Es gibt KEINE JNI-Bridge (`librnllama.so`) und keine Struct-Offsets mehr:
 *  - Laden/Freigeben und das Auflösen von `llama_context*` / `llama_model*` / `mtmd_context*`
 *    erledigen die flachen C-Funktionen `omni_load` / `omni_free` / `omni_lctx` / `omni_model` /
 *    `omni_mtmd` aus `omni_shim.cpp`.
 *  - `mtmd_helper_gen_audio_inp` wird in C++ gegen die eingebauten Header befüllt
 *    (`omni_gen_set_input`), Kotlin liefert nur Primitive.
 *  - `llama_sampler_init_penalties` läuft über `omni_sampler_penalties`, weil sich die
 *    Signatur zwischen llama.cpp-Ständen geändert hat.
 *
 * Bleibt noch zu verifizieren (Layout-Annahmen gegen libllama.so, siehe [ensureLoaded]):
 *  - `llama_batch` (56 Byte) in der Engine,
 *  - `mtmd_input_text` (24 Byte, [buildMtmdInputText]),
 *  - `mtmd_helper_gen_audio*`-Kontext: Pipeline-Zeiger bei +0 (Engine.setupNativeAudioGenPipeline).
 */
interface LlamaNativeLib : Library {
    // --- omni_shim.cpp (flache C-API, ersetzt JNI-Bridge + Offsets) ---
    fun omni_abi_version(): Int
    fun omni_load(
        modelPath: String,
        mmprojPath: String?,
        nCtx: Int,
        nBatch: Int,
        nThreads: Int,
        nGpuLayers: Int,
        useMmap: Boolean,
        useMlock: Boolean
    ): Pointer?
    fun omni_free(handle: Pointer)
    fun omni_lctx(handle: Pointer): Pointer?
    fun omni_model(handle: Pointer): Pointer?
    fun omni_mtmd(handle: Pointer): Pointer?
    fun omni_gen_set_input(
        gen: Pointer,
        prompt: String?,
        nPast: Int,
        outMode: Int,
        stream: Boolean,
        seqId: Int,
        lang: String?,
        topK: Int,
        topP: Float,
        seed: Int,
        outType: Int
    ): Int
    fun omni_sampler_penalties(
        nVocab: Int,
        lastN: Int,
        repeat: Float,
        freq: Float,
        present: Float
    ): Pointer?
    fun omni_device_count(): Int

    // --- Core llama_context / llama_model / llama_vocab ---
    fun llama_get_model(ctx: Pointer): Pointer?
    fun llama_get_memory(ctx: Pointer): Pointer?
    fun llama_memory_clear(mem: Pointer, data: Boolean)
    fun llama_memory_seq_rm(mem: Pointer, seq_id: Int, p0: Int, p1: Int): Boolean
    fun llama_set_embeddings(ctx: Pointer, embeddings: Boolean)
    fun llama_set_causal_attn(ctx: Pointer, causal_attn: Boolean)
    fun llama_set_n_threads(ctx: Pointer, n_threads: Int, n_threads_batch: Int)
    fun llama_get_embeddings_ith(ctx: Pointer, i: Int): Pointer?
    fun llama_decode(ctx: Pointer, batch: Pointer): Int
    fun llama_model_n_embd(model: Pointer): Int
    fun llama_model_get_vocab(model: Pointer): Pointer?
    fun llama_vocab_n_tokens(vocab: Pointer): Int
    fun llama_vocab_eos(vocab: Pointer): Int
    fun llama_vocab_eot(vocab: Pointer): Int
    fun llama_vocab_is_eog(vocab: Pointer, token: Int): Boolean
    fun llama_vocab_is_control(vocab: Pointer, token: Int): Boolean
    fun llama_token_to_piece(
        vocab: Pointer,
        token: Int,
        buf: ByteArray,
        length: Int,
        lstrip: Int,
        special: Boolean
    ): Int
    fun llama_tokenize(
        vocab: Pointer,
        text: ByteArray,
        text_len: Int,
        tokens: IntArray?,
        n_tokens_max: Int,
        add_special: Boolean,
        parse_special: Boolean
    ): Int

    // --- Native llama_sampler chain ---
    // llama_sampler_chain_params ist ein 1-Byte-Struct { bool no_perf; } -> als Byte (W0) übergeben.
    fun llama_sampler_chain_init(no_perf: Byte): Pointer?
    fun llama_sampler_chain_add(chain: Pointer, smpl: Pointer)
    fun llama_sampler_init_top_k(k: Int): Pointer?
    fun llama_sampler_init_top_p(p: Float, min_keep: Long): Pointer?
    fun llama_sampler_init_min_p(p: Float, min_keep: Long): Pointer?
    fun llama_sampler_init_temp(t: Float): Pointer?
    fun llama_sampler_init_dist(seed: Int): Pointer?
    fun llama_sampler_init_greedy(): Pointer?
    fun llama_sampler_init_logit_bias(
        n_vocab: Int,
        n_logit_bias: Int,
        logit_bias: Pointer
    ): Pointer?
    fun llama_sampler_sample(smpl: Pointer, ctx: Pointer, idx: Int): Int
    fun llama_sampler_accept(smpl: Pointer, token: Int)
    fun llama_sampler_free(smpl: Pointer)

    // --- Multimodal Input (mtmd) ---
    fun mtmd_support_vision(ctx: Pointer): Boolean
    fun mtmd_support_audio(ctx: Pointer): Boolean
    fun mtmd_get_audio_sample_rate(ctx: Pointer): Int
    fun mtmd_bitmap_init(nx: Int, ny: Int, data: ByteArray): Pointer?
    fun mtmd_bitmap_init_from_audio(n_samples: Long, data: FloatArray): Pointer?
    fun mtmd_bitmap_free(bitmap: Pointer)
    fun mtmd_input_chunks_init(): Pointer?
    fun mtmd_input_chunks_free(chunks: Pointer)
    fun mtmd_tokenize(
        ctx: Pointer,
        output: Pointer,
        text: Pointer,
        bitmaps: Pointer?,
        n_bitmaps: Long
    ): Int
    fun mtmd_helper_eval_chunks(
        ctx: Pointer,
        lctx: Pointer,
        chunks: Pointer,
        n_past: Int,
        seq_id: Int,
        n_batch: Int,
        logits_last: Boolean,
        new_n_past: IntByReference
    ): Int

    // --- Native LFM3-Omni Audio + Text Generation Pipeline (mtmd_helper_gen_audio_*) ---
    fun mtmd_helper_gen_audio_init(lctx: Pointer, mctx: Pointer?): Pointer?
    fun mtmd_helper_gen_audio_reset(ctx: Pointer)
    fun mtmd_helper_gen_audio_free(ctx: Pointer)
    fun mtmd_helper_gen_audio_get_logit_bias(
        ctx: Pointer,
        n_bias: LongByReference
    ): Pointer?
    fun mtmd_helper_gen_audio_step_prompt(ctx: Pointer, n_batch: Long): Int
    fun mtmd_helper_gen_audio_next_is_text(ctx: Pointer): Boolean
    fun mtmd_helper_gen_audio_step_gen(
        ctx: Pointer,
        sampled: Int,
        h_cur: Pointer,
        h_next: PointerByReference,
        stop: ByteByReference
    ): Int
    fun mtmd_helper_gen_audio_flush(ctx: Pointer): Int
    fun mtmd_helper_gen_audio_get_output(
        ctx: Pointer,
        sample_rate: IntByReference,
        data: PointerByReference,
        data_len: LongByReference,
        n_samples: LongByReference
    ): Int

    // --- Hardware Backend Diagnostics (ggml_backend + llama_supports_gpu_offload) ---
    fun llama_supports_gpu_offload(): Boolean

    /** Initialisiert ggml (Zeitbasis, f16-Tabellen). Idempotent. */
    fun llama_backend_init()

    /**
     * Lädt Backend-Module (libggml-*.so) aus [dirPath]. Beim statischen Monolith-Build
     * (GGML_BACKEND_DL=OFF) ist das ein No-Op; mit dem Laufzeit-ISA-Build (Phase 5) nötig.
     */
    fun ggml_backend_load_all_from_path(dirPath: String?)

    /** Einkompilierte CPU-Features des ggml-CPU-Backends (z. B. "NEON = 1 | DOTPROD = 1 ..."). */
    fun llama_print_system_info(): String?

    /** Setzt den nativen Log-Handler (ggml_log_callback). */
    fun llama_log_set(callback: GgmlLogCallback?, userData: Pointer?)
    fun ggml_backend_dev_count(): Long
    fun ggml_backend_dev_get(index: Long): Pointer?
    fun ggml_backend_dev_name(device: Pointer): String?
    fun ggml_backend_dev_description(device: Pointer): String?
    fun ggml_backend_dev_type(device: Pointer): Int
}

data class ResolvedNativePointers(
    /** `omni_ctx*` aus `omni_load` (Besitzer von model/lctx/mctx; nur an `omni_free` geben). */
    val omniHandle: Pointer,
    val modelPtr: Pointer,
    val lctxPtr: Pointer,
    val mctxPtr: Pointer?,
    val vocabPtr: Pointer,
    val nEmbd: Int,
    val nVocab: Int
)

object LlamaNativeBridge {
    private const val TAG = "LlamaNativeBridge"

    /** Muss zu `omni_abi_version()` in native/omni_shim.cpp passen. */
    const val EXPECTED_SHIM_ABI = 1

    // mtmd_helper_gen_audio_out_mode enum values
    const val OUT_MODE_AUTO = 0
    const val OUT_MODE_TEXT = 1
    const val OUT_MODE_AUDIO = 2
    const val OUT_MODE_MIXED = 3

    // mtmd_helper_gen_audio_out_type enum values
    const val OUT_TYPE_PCM = 0
    const val OUT_TYPE_WAV = 1

    @Volatile
    var llamaLib: LlamaNativeLib? = null
        private set

    /**
     * Bindet `libllama.so` und prüft, dass die omni_*-Shim-Funktionen vorhanden und
     * ABI-kompatibel sind. Ein alter Blob ohne Shim (oder mit anderer ABI-Version) wird
     * ausdrücklich abgelehnt, statt später mit UnsatisfiedLinkError zu scheitern.
     */
    fun ensureLoaded(onLog: ((String) -> Unit)? = null): LlamaNativeLib? {
        llamaLib?.let { return it }
        synchronized(this) {
            llamaLib?.let { return it }
            return try {
                val lib = Native.load("llama", LlamaNativeLib::class.java)
                val abi = try {
                    lib.omni_abi_version()
                } catch (e: UnsatisfiedLinkError) {
                    onLog?.invoke(
                        "JNA FEHLER: libllama.so enthält keine omni_*-Funktionen — alter/CPU-only-Blob? " +
                            "Neu bauen mit native/build_engine.sh."
                    )
                    return null
                }
                if (abi != EXPECTED_SHIM_ABI) {
                    onLog?.invoke("JNA FEHLER: Shim-ABI $abi, erwartet $EXPECTED_SHIM_ABI — libllama.so und App passen nicht zusammen.")
                    return null
                }
                llamaLib = lib
                onLog?.invoke("JNA: 'libllama.so' gebunden (Shim-ABI $abi)")
                lib
            } catch (e: Throwable) {
                Log.w(TAG, "Konnte JNA für libllama.so nicht initialisieren: ${e.message}")
                onLog?.invoke("JNA WARNUNG: Bindung fehlgeschlagen: ${e.message}")
                null
            }
        }
    }

    /**
     * Löst alle Zeiger über die Shim-Accessoren auf — ohne Struct-Offsets.
     * [omniHandle] ist der Rückgabewert von `omni_load`.
     */
    fun resolvePointers(
        omniHandle: Pointer,
        onLog: ((String) -> Unit)? = null
    ): ResolvedNativePointers? {
        val lib = ensureLoaded(onLog) ?: return null

        val lctxPtr = lib.omni_lctx(omniHandle) ?: run {
            onLog?.invoke("JNA FEHLER: omni_lctx lieferte null")
            return null
        }
        val modelPtr = lib.omni_model(omniHandle) ?: run {
            onLog?.invoke("JNA FEHLER: omni_model lieferte null")
            return null
        }
        val mctxPtr = lib.omni_mtmd(omniHandle)
        val vocabPtr = lib.llama_model_get_vocab(modelPtr) ?: run {
            onLog?.invoke("JNA FEHLER: llama_model_get_vocab lieferte null")
            return null
        }
        return ResolvedNativePointers(
            omniHandle = omniHandle,
            modelPtr = modelPtr,
            lctxPtr = lctxPtr,
            mctxPtr = mctxPtr,
            vocabPtr = vocabPtr,
            nEmbd = lib.llama_model_n_embd(modelPtr),
            nVocab = lib.llama_vocab_n_tokens(vocabPtr)
        )
    }

    /**
     * Früher: schrieb `clip_ctx->n_threads` (+52) per Offset in den mtmd_context.
     * Entfällt bewusst — ein falscher Offset im neuen llama.cpp-Stand würde still Speicher
     * überschreiben. Der Encoder bekommt seine Thread-Zahl jetzt einmal beim Laden
     * (`omni_load` -> `mtmd_context_params.n_threads`); pro Turn wird nur
     * `llama_set_n_threads` für den LLM-Teil gesetzt.
     */
    @Suppress("UNUSED_PARAMETER")
    fun syncMultimodalThreads(mctxPtr: Pointer?, nThreads: Int, onLog: ((String) -> Unit)? = null) = Unit

    /**
     * Plausibilitätscheck für Zeiger (0, unaligned, offensichtlich unsinnig).
     * Schutz gegen Zufallswerte, kein Sicherheitsnetz.
     */
    internal fun isValidPointerAddress(addr: Long): Boolean {
        val unsigned = addr ushr 16
        return addr != 0L && (addr and 0x7L) == 0L && unsigned > 0x10L
    }

    /**
     * Legt ein 24-Byte `mtmd_input_text` { const char* text; size_t text_len; bool add_special;
     * bool parse_special; } plus UTF-8-C-String an. Beide müssen während `mtmd_tokenize` leben.
     */
    fun buildMtmdInputText(
        text: String,
        addSpecial: Boolean = true,
        parseSpecial: Boolean = true
    ): Pair<Memory, Memory> {
        val utf8Bytes = text.toByteArray(Charsets.UTF_8)
        val cStrMem = Memory((utf8Bytes.size + 1).toLong())
        cStrMem.write(0L, utf8Bytes, 0, utf8Bytes.size)
        cStrMem.setByte(utf8Bytes.size.toLong(), 0)

        val structMem = Memory(24L)
        structMem.clear()
        structMem.setPointer(0L, cStrMem)
        structMem.setLong(8L, utf8Bytes.size.toLong())
        structMem.setByte(16L, if (addSpecial) 1 else 0)
        structMem.setByte(17L, if (parseSpecial) 1 else 0)

        return Pair(structMem, cStrMem)
    }

    /** 16-Bit-LE-Mono-PCM (16 kHz) -> normalisiertes FloatArray für `mtmd_bitmap_init_from_audio`. */
    fun pcm16LeToFloatArray(pcmBytes: ByteArray): FloatArray {
        val sampleCount = pcmBytes.size / 2
        val floats = FloatArray(sampleCount)
        var byteIdx = 0
        for (i in 0 until sampleCount) {
            val low = pcmBytes[byteIdx].toInt() and 0xFF
            val high = pcmBytes[byteIdx + 1].toInt()
            val sampleShort = (high shl 8) or low
            floats[i] = (sampleShort / 32768.0f).coerceIn(-1.0f, 1.0f)
            byteIdx += 2
        }
        return floats
    }

    /** Erzeugtes 24-kHz-Float32-PCM aus `mtmd_helper_gen_audio_get_output` -> 16-Bit-LE-Mono-PCM. */
    fun float32PointerToPcm16Le(dataPtr: Pointer, nSamples: Int, gain: Float = 1.0f): ByteArray {
        val floats = dataPtr.getFloatArray(0L, nSamples)
        val outBytes = ByteArray(nSamples * 2)
        var outIdx = 0
        for (i in 0 until nSamples) {
            val scaled = (floats[i] * gain * 32767.0f).toInt().coerceIn(-32768, 32767)
            outBytes[outIdx] = (scaled and 0xFF).toByte()
            outBytes[outIdx + 1] = ((scaled shr 8) and 0xFF).toByte()
            outIdx += 2
        }
        return outBytes
    }
}
