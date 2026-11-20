# Native-Engine & Backends: was die App wirklich rechnet

Diese Datei dokumentiert die **messbare** Lage der nativen Inferenz-Bibliotheken und die
Entscheidungen, die die App seitdem trifft. Beweise sind Kommandos, die jeder im Repo
nachvollziehen kann (`native/native_audit.py`); Spekulation ist bewusst nicht enthalten.

Kurzantwort auf die Frage „läuft das nur auf der CPU?": **Ja — und das war unvermeidlich,
weil die mitgelieferte `libllama.so` gar keinen GPU-Code enthält.**

---

## 1. Befund: die gebündelten Bibliotheken

Auditierung aller `.so` in `app/src/main/jniLibs/arm64-v8a/`:

```
python3 native/native_audit.py app/src/main/jniLibs/arm64-v8a
```

Ergebnis (Zeilen aus dem echten Report):

| Bibliothek | Größe | Rolle | Build | GPU-Backend-Symbole |
|---|---|---|---|---|
| `libllama.so` | 13,2 MB | Engine (monolithisch) | **llama.cpp b11960** | **keine** |
| `librnllama.so` | ~1,9 MB | JNI-Bridge (`Java_org_nehuatl_llamacpp_*`) | – | – |
| `librnllama_v8.so` …`librnllama_v8_2_i8mm.so` (5 Stück) | je ~1,9 MB | JNI-Bridge, „CPU-Varianten" | – | – |

Drei harte Fakten daraus:

1. **Die Engine ist CPU-only.** `native_audit.py` findet in `libllama.so` kein einziges
   Backend-Symbol (`ggml_backend_cuda_reg`, `ggml_backend_vulkan_reg`,
   `ggml_backend_metal_reg`, `ggml_backend_opencl_reg`, `ggml_backend_hexagsyn…` — Fehlanzeige)
   und `llama_supports_gpu_offload()` kann deshalb nur `false` liefern.
2. **Die fünf „CPU-Varianten" der JNI-Bridge sind Kopien.** Der Report meldet
   `identischer .text-Code in 5 Bibliotheken`. Die SIMD-Kernel leben in `libllama.so`,
   nicht in den Bridges — der Name `…_dotprod_i8mm` bringt also **keinen** Vorteil, er
   ändert nur den Pfad, über den `context_map`/`mtmd_context` gelesen werden.
3. **Die Engine stammt von einem fremden Build-Rechner.** Im ELF steht als
   Build-Pfad `/Users/vali/Desktop/dev/react_native/cui-llama.rn/vendor/llama.cpp` —
   d. h. niemand im Repo kann diese Datei nachbauen. Sie ist ein_blob im Git.

## 2. Warum die App trotzdem „GPU" geglaubt hat

In `Lfm3OmniNativeEngine.kt` stand (bis zu dieser Änderung):

```kotlin
// Auf echten Android-Geräten mit Vulkan-Unterstützung immer 99 Schichten auf die GPU auslagern
val targetGpuLayers = 99
```

`n_gpu_layers = 99` wird llama.cpp **nicht** von einer Fähigkeit abhängiggemacht: Ist kein
GPU-Backend registriert, wird die Zahl **kommentarlos ignoriert**. Es gab also nie einen
Fehler, nie eine Warnung — nur ein APK, das still auf der CPU lief. Genau das ist der
„Backend-Kram", der hier als Problem wahrgenommen wurde.

Zweite stille Annahme: die Thread-Zahl

```kotlin
cores >= 8 -> (cores - 2).coerceIn(4, 6)
```

kam ohne Blick in `/sys` daher. Auf big.LITTLE-SoCs (Pixel 10: 1 Prime + 5 Performance +
2 Efficiency) entscheidet aber genau das über die Geschwindigkeit, weil llama.cpp seine
Worker nicht selbst auf die schnellen Kerne legt.

Dritte Quelle der Verwirrung: die Gradle-Abhängigkeit `llamacpp-kotlin:0.4.0` bringt
**eigene** `librnllama*.so` (je 5,7 MB, CPU-only, **ohne mtmd**) mit. Nur dank
`packaging.jniLibs.pickFirsts` gewinnen die lokalen Dateien. Ohne Dokumentation ist das
ein Konflikt, der bei jeder Dependency-Aktualisierung wieder aufbricht.

## 3. ISA-Messung: was die Engine code-mäßig nutzen *könnte*

Vollständige `.text`-Analyse mit `capstone` (`--isa-bytes 0`, Report-Abschnitt „ISA-Kernels"):

| Bibliothek | dotprod | i8mm | SVE | SME |
|---|---|---|---|---|
| `libllama.so` (gebaut) | **1245** | **444** | 0 | 0 |
| llama.rn b12445 Base-Variante | 0 | 0 | 0 | 0 |
| offizielles `libggml-cpu-android_armv9.2_1.so` (b12470) | 1197 | 424 | **1079** | 0 |
| offizielles `libggml-cpu-android_armv8.2_2.so` (b12470) | 1120 | 0 | 0 | 0 |

Drei Konsequenzen:

* Ein Build mit `-march=armv9.2-a+sve+sve2` wäre auf Tensor-Geräten ein echtes Plus — aber
  eine so gebaute `.so` wirft auf älteren Geräten `SIGILL`. Deshalb in
  `native/CMakeLists.txt` als optionaler Arch-Flag, nicht als Default.
* Der llama.rn-Prebuilt wäre in dieser Hinsicht ein **Rückschritt** (0 dotprod-Kernel).
* Offizielle Builds verteilen Kernel auf mehrere `libggml-cpu-*.so` und wählen zur
  Laufzeit — das ist das Design, das die App sich eigentlich wünscht.

## 4. Warum „fertig kompiliert von irgendwoher" nicht funktioniert

Getestet wurden alle drei naheliegenden Abkürzungen:

**a) llama.rn-Prebuilt (`llama-rn-android-jni-libs.tar.gz`, v0.13.0-rc.12) ins jniLibs.**
Die Variante `librnllama_v8_2_dotprod_i8mm_hexagon_opencl.so` (18,8 MB) exportiert die
identischen 51 `mtmd_helper_gen_audio_*`-/mtmd-/llama-Symbole **plus**
`ggml_backend_opencl_reg` und die Hexagon-Registry. Sie ist aber kein Austausch-Kandidat:
* **keine** `Java_*`-Exporte → die Kotlin-Schicht (`org.nehuatl.llamacpp.LlamaContext`)
  findet ihre Native-Methoden nicht,
* llama.cpp **b12445** statt b11960 → ABI der `llama_*_params`-Strukturen anders, und die
  Bridge liest Feld-Offsets (z. B. `RN_OFFSET_LCTX = 0x16d0`) hart.

**b) Offiziellen Prebuilt + `ggml_backend_load_all_from_path()` zur Laufzeit.**
Die Modul-Dateien (`libggml-vulkan.so`, `libggml-opencl.so`, `libggml-hexagon.so`) haben
als `DT_NEEDED` ihr eigenes `libggml-base.so` **ihrer** Buildnummer (b12470). Unsere
`libllama.so` (b11960) hat ggml-base statisch einkompiliert. Ergebnis: Symbole existieren
zweifach in verschiedenen Versionen, das Backend-Modul lädt nicht sauber. Der Aufruf bleibt
im Code als **harmlose** Zusatzstufe des Probes (er liefert `void`), ist aber nicht die Lösung.

**c) Pure-JNA gegen die offiziellen Split-Libs.**
Würde drei JNA-Interfaces (`ggml-base`, `ggml`, `llama`, `mtmd`) und deren
Struct-Layouts brauchen — dasselbe Offsets-Raten, das hier schon einmal Schiffbruch
verursacht hat. Verworfen.

**Der einzige haltbare Weg:** selbst bauen — Engine-Kern und JNI-Bridge aus derselben
Quell-Basis, mit GPU-Backend einkompiliert. Das ist `native/build_engine.sh`.

## 5. Was jetzt gebaut werden kann

`native/CMakeLists.txt` + `native/build_engine.sh` (beide im Repo) tun genau das:

```
llama.rn  vendor/llama.cpp (enthält die fork-eigenen mtmd-/codec-Quellen)
   ├── whole-archive ──> libllama.so      (Engine-Kern + ggml + mtmd + Backends)
   └── jni.cpp ────────> librnllama.so    (org.nehuatl.JNI, linkt gegen libllama.so)
kotlinllamacpp  ──────> Quelle der JNI-Signaturen
```

```bash
native/build_engine.sh --backend vulkan          # Pixel, Mali, Adreno — ein Build für alles
native/build_engine.sh --backend opencl          # Snapdragon: Adreno-OpenCL
native/build_engine.sh --backend hexagon         # Snapdragon NPU (libggml-htp-vNN.so nötig)
native/build_engine.sh --backend all             # alle drei
native/build_engine.sh --arch fast --backend vulkan   # armv9.2-a+sve2 (nur Tensor/8 Gen2+)
```

Das Skript verifiziert die Quell-Stände gegen `native/libs.lock.json`, installiert in
`app/src/main/jniLibs/arm64-v8a`, schreibt `native/provenance.json` (welcher Commit,
welche Flags, welche Hashes — damit ein `.so`-Tausch dokumentierbar ist) und **bricht ab**,
wenn das Audit kein GPU-Backend findet (`--gate gpu`). Ein `.so`, der das Gate nicht
erfüllt, landet also nicht mehr im APK.

Für Pixel 10 (Tensor G5, Imagination PowerVR GPU) ist **Vulkan** der Weg: llama.cpp hat
kein Tensor-NPU-Backend, und der OpenCL-Backend-Pfad ist auf Adreno/Intel ausgerichtet.

## 6. Wie die App seitdem entscheidet

Neue Dateien (all ohne Android-Abhängigkeit, wo es geht, damit testbar):

| Datei | Zweck |
|---|---|
| `engine/NativeBackendProbe.kt` | fragt die **geladene** Engine: `ggml_backend_dev_*`-Tabelle, `llama_supports_gpu_offload()`, `llama_print_system_info()`, Treiber-Dlopen-Test |
| `engine/BackendCapabilities.kt` | Datenmodell + `BackendPolicy`: die **einzige** Stelle, die `n_gpu_layers` und die Thread-Zahl festlegt |
| `engine/NativeLogSink.kt` | `llama_log_set` → Ringpuffer; native Fehlertexte landen in App-Diagnose und Exception-Meldung |
| `hardware/CpuTopology.kt` | `/sys/devices/system/cpu/policy*/` (Cluster, Taktrate, Kernzahl) + `/proc/cpuinfo` (ISA-Flags), plus Thread-Vorschlag |
| `hardware/VulkanDriverProbe.kt` | `vkEnumerateInstanceVersion` via JNA — Treiber ja/nein und Version, ohne Struct-Marshalling |

Ablauf beim Modelladen:

```
preloadNativeLibraries()
  → NativeLogSink.install()
  → NativeBackendProbe.probe(nativeLibraryDir)      // Geräte + Treiber + Systeminfo
  → BackendPolicy.resolve(backendMode, caps, gpuLayers)
  → BackendPolicy.resolveThreads(cpuThreads, cores, CpuTopologyReader.suggestedThreads(topology))
  → Params: n_gpu_layers = Entscheidung, n_threads = Entscheidung
  → Log: "BACKEND: Entscheidung = … | Begründung = …"
```

Regeln, die jetzt gelten (dokumentiert und in `BackendPolicyTest` geprüft):

* **AUTO**: GPU nur, wenn die Engine ein Beschleuniger-Gerät meldet. Sonst CPU — und der
  Grund steht im Log und im Einstellungspanel.
* **GPU erzwingen ohne Gerät**: `n_gpu_layers = 0` **und** `warned = true` mit der echten
  Diagnose („die geladene libllama.so enthält kein GPU-Backend; Abhilfe:
  `native/build_engine.sh --backend vulkan`"). Kein stiller Rückfall mehr.
* **Nur CPU**: immer 0, Absicht des Nutzers.
* **Threads**: Nutzerwerte werden auf die Zahl der gemessen schnellen Kerne **begrenzt**
  (nicht nur kommentiert), `0` heißt „automatisch aus der Messung".
* **Fehlermeldungen** enthalten jetzt die letzte native Log-Zeile statt nur
  „Interner Fehler".

Anzeigen: *Einstellungen → Rechen-Backend (GPU / CPU)* zeigt die Geräte-Tabelle,
Entscheidung, Begründung, Engine-CPU-Features, Treiberstatus und die letzten nativen
Log-Zeilen; „Backend neu messen" wiederholt die Messung ohne Modell-Neuladen.

## 7. Ehrliche Grenzen

* Solange die committete `libllama.so` (b11960, CPU-only) im APK bleibt, **ändert die
  Kotlin-Schicht nichts an der Geschwindigkeit** — sie macht den Zustand nur sichtbar und
  verhindert Falschangaben. Der GPU-Gewinn kommt erst mit dem selbst gebauten `.so`
  (Abschnitt 5) oder dem CI-Artefakt aus `.github/workflows/native-engine.yml`.
* `VulkanDriverProbe` prüft nur den **Treiber**, nicht, ob llama.cpp ihn nutzen kann.
  Maßgeblich ist die ggml-Gerätetabelle nach dem Laden.
* OpenCL ist aus einer regulären App oft nicht `dlopen`-bar (nicht im öffentlichen
  NDK-Scope). `openclDriverPresent = false` heißt deshalb *nicht* „Gerät hat kein OpenCL".
* Die Hexagon-Spaltung braucht die `libggml-htp-vNN.so` des Geräte-Modells; die liefert
  Qualcomm nicht als Open Source. Für Pixel ist sie ohnehin irrelevant.

## 8. Nachprüfen (Erwartungshorizont)

```bash
# 1. Current state: keine GPU-Backend-Symbole, 5 identische Bridges
python3 native/native_audit.py app/src/main/jniLibs/arm64-v8a

# 2. Gate auf GPU (muss mit dem aktuellen Stand FEHLEREN — das ist der Punkt)
python3 native/native_audit.py app/src/main/jniLibs/arm64-v8a --gate gpu

# 3. Downsloads prüfen und den Austausch-Irrtum selbst sehen
native/fetch_prebuilt.sh

# 4. Richtiger Build (NDK 27 + CMake + Vulkan-Headers nötig)
native/build_engine.sh --backend vulkan

# 5. Entscheidungspolicy ohne Gerät testen
./gradlew :app:testDebugUnitTest --tests '*BackendPolicyTest' --tests '*CpuTopologyTest'
```
