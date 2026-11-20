// native/omni_shim.cpp
//
// Flache C-API, die in libllama.so einkompiliert wird. Ersetzt die JNI-Bridge
// (org.nehuatl.llamacpp.LlamaContext) und das Auslesen von rn-Struct-Offsets
// (RN_OFFSET_*, context_map) durch Funktionen, die mit reinen Primitiven arbeiten.
//
// Warum: Alle Structs (llama_*_params, mtmd_context_params, mtmd_helper_gen_audio_inp)
// werden hier vom Compiler gegen GENAU die Header gebaut, aus denen auch die Engine
// entsteht. Kotlin/JNA muss kein Struct-Layout mehr kennen — ein Versionssprung der
// Engine kann das Layout ändern, ohne dass die App still falsch liest.
// (Konkret: zwischen b11960 und b12445 hat sich mtmd_helper_gen_audio_inp geändert
// — `voice` weg, `temp`/`speed`/`loudness` neu — und llama_sampler_init_penalties
// bekam n_vocab als erstes Argument.)
//
// Alle Pfade sind echte Dateipfade (kein FD-Trick): Modelle liegen im App-Speicher.

#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"
#include "ggml-backend.h"

#include <cstdint>
#include <cstring>
#include <new>
#include <string>

#define OMNI_API extern "C" __attribute__((visibility("default")))

// mtmd darf den prompt-/lang-Zeiger bis zum Ende von step_prompt() behalten. Die Kopien leben
// deshalb bis zum nächsten omni_gen_set_input() (Aufrufe sind in der Engine per Mutex serialisiert).
static std::string s_gen_prompt;
static std::string s_gen_lang;

struct omni_ctx {
    llama_model   * model = nullptr;
    llama_context * lctx  = nullptr;
    mtmd_context  * mctx  = nullptr;
};

// Hochzählen, wenn sich die Bedeutung einer omni_*-Funktion ändert.
OMNI_API int32_t omni_abi_version(void) { return 1; }

/**
 * Lädt Modell + Kontext (+ optional mmproj). Liefert nullptr bei Fehler; die Ursache steht
 * im llama_log (App: NativeLogSink).
 *
 * n_gpu_layers > 0 schaltet auch den mtmd-Encoder (Vision/Audio) auf die GPU.
 */
OMNI_API omni_ctx * omni_load(const char * model_path,
                              const char * mmproj_path,
                              int32_t n_ctx,
                              int32_t n_batch,
                              int32_t n_threads,
                              int32_t n_gpu_layers,
                              bool    use_mmap,
                              bool    use_mlock) {
    if (!model_path) return nullptr;

    llama_backend_init();

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = n_gpu_layers;
    // b12445: use_mmap/use_mlock wurden zu einem Enum
    mp.load_mode    = use_mmap ? (use_mlock ? LLAMA_LOAD_MODE_MMAP_MLOCK : LLAMA_LOAD_MODE_MMAP)
                               : (use_mlock ? LLAMA_LOAD_MODE_MLOCK      : LLAMA_LOAD_MODE_NONE);

    auto * h = new (std::nothrow) omni_ctx();
    if (!h) return nullptr;

    h->model = llama_model_load_from_file(model_path, mp);
    if (!h->model) { delete h; return nullptr; }

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx           = (uint32_t) n_ctx;
    cp.n_batch         = (uint32_t) n_batch;
    cp.n_ubatch        = (uint32_t) (n_batch < 512 ? n_batch : 512);
    cp.n_threads       = n_threads;
    cp.n_threads_batch = n_threads;
    cp.embeddings      = false;   // die Engine schaltet es pro Turn mit llama_set_embeddings()

    h->lctx = llama_init_from_model(h->model, cp);
    if (!h->lctx) { llama_model_free(h->model); delete h; return nullptr; }

    if (mmproj_path && mmproj_path[0] != '\0') {
        mtmd_context_params xp = mtmd_context_params_default();
        xp.use_gpu         = n_gpu_layers > 0;
        xp.n_threads       = n_threads;
        xp.print_timings   = false;
        xp.flash_attn_type = cp.flash_attn_type;
        h->mctx = mtmd_init_from_file(mmproj_path, h->model, xp);
        if (!h->mctx) {
            llama_free(h->lctx);
            llama_model_free(h->model);
            delete h;
            return nullptr;
        }
    }
    return h;
}

OMNI_API void omni_free(omni_ctx * h) {
    if (!h) return;
    if (h->mctx)  mtmd_free(h->mctx);
    if (h->lctx)  llama_free(h->lctx);
    if (h->model) llama_model_free(h->model);
    delete h;
}

OMNI_API llama_context * omni_lctx (omni_ctx * h) { return h ? h->lctx  : nullptr; }
OMNI_API llama_model   * omni_model(omni_ctx * h) { return h ? h->model : nullptr; }
OMNI_API mtmd_context  * omni_mtmd (omni_ctx * h) { return h ? h->mctx  : nullptr; }

/**
 * Ersetzt buildGenAudioInputStruct(): setzt alle Felder über die Default-Struktur der
 * eingebauten mtmd-Version, Kotlin liefert nur Primitive.
 * seed: UINT32_MAX (= -1 als jint) heißt zufällig.
 * out_mode: 0=AUTO 1=TEXT 2=AUDIO 3=MIXED   out_type: 0=PCM 1=WAV
 */
OMNI_API int32_t omni_gen_set_input(mtmd_helper_gen_audio * g,
                                    const char * prompt,
                                    int32_t n_past,
                                    int32_t out_mode,
                                    bool    stream,
                                    int32_t seq_id,
                                    const char * lang,
                                    int32_t top_k,
                                    float   top_p,
                                    uint32_t seed,
                                    int32_t out_type) {
    if (!g) return 1;
    s_gen_prompt = prompt ? prompt : "";
    s_gen_lang   = lang   ? lang   : "";
    mtmd_helper_gen_audio_inp inp = mtmd_helper_gen_audio_inp_default();
    inp.stream     = stream;
    inp.seq_id     = seq_id;
    inp.prompt     = s_gen_prompt.c_str();
    inp.prompt_len = s_gen_prompt.size();
    inp.n_past     = n_past;
    inp.out_mode   = (enum mtmd_helper_gen_audio_out_mode) out_mode;
    inp.lang       = s_gen_lang.empty() ? nullptr : s_gen_lang.c_str();
    inp.top_k      = top_k;
    inp.top_p      = top_p;
    if (seed != UINT32_MAX) inp.seed = seed;
    inp.out_type   = (enum mtmd_helper_gen_audio_outtype) out_type;
    return mtmd_helper_gen_audio_set_input(g, &inp);
}

/**
 * llama_sampler_init_penalties hat zwischen Engine-Ständen die Signatur geändert
 * (b12445: erstes Argument n_vocab). Die Shim-Variante ist stabil.
 */
OMNI_API llama_sampler * omni_sampler_penalties(int32_t n_vocab, int32_t last_n,
                                                float repeat, float freq, float present) {
    return llama_sampler_init_penalties(n_vocab, last_n, repeat, freq, present);
}

/** Anzahl registrierter ggml-Geräte (CPU zählt mit) — schneller Selbsttest ohne Struct-Marshalling. */
OMNI_API int32_t omni_device_count(void) { return (int32_t) ggml_backend_dev_count(); }
