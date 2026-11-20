#!/usr/bin/env bash
# Holt die in native/libs.lock.json gepinnten Offline-Bibliotheken, prüft ihre
# Hashes und auditet sie. Reicht NICHT als "GPU-Fix" — das Skript erklärt, warum.
#
# Usage:
#   native/fetch_prebuilt.sh [Zielverzeichnis]      # Default: native/.cache/prebuilt
#   GOVERNOR=skip native/fetch_prebuilt.sh          # nur Prüfen, nichts laden
#
# Warum dieses Skript existiert: die naheliegende Idee lautet "einfach eine
# offizielle libllama.so mit GPU-Backend ins APK legen". Das ist nachweislich
# nicht möglich (Begründung in docs/NATIVE-ENGINE-BACKENDS.md, Abschnitt
# "Warum kein Download reicht"). Dieses Skript holt die Artefakte trotzdem —
# als Referenz, für Symbol-Vergleiche und damit die Aussage prüfbar bleibt.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOCK="$SCRIPT_DIR/libs.lock.json"
DEST="${1:-$SCRIPT_DIR/.cache/prebuilt}"
AUDIT="$SCRIPT_DIR/native_audit.py"

for tool in python3 curl sha256sum tar; do
  command -v "$tool" >/dev/null 2>&1 || { echo "FEHLT: $tool" >&2; exit 2; }
done
[ -f "$LOCK" ] || { echo "FEHLT: $LOCK" >&2; exit 2; }
[ -f "$AUDIT" ] || { echo "FEHLT: $AUDIT" >&2; exit 2; }

mkdir -p "$DEST"
cd "$DEST"

echo "== 1. Pins aus libs.lock.json lesen ======================================"
python3 - "$LOCK" > pins.tsv <<'PY'
import json, sys
lock = json.load(open(sys.argv[1]))
for key, p in lock["prebuilts"].items():
    print("\t".join([key, p["url"], p["sha256"], p["asset"]]))
PY
column -t -s $'\t' pins.tsv 2>/dev/null || cat pins.tsv
echo

echo "== 2. Herunterladen und Hash prüfen ======================================"
while IFS=$'\t' read -r key url sha asset; do
  if [ ! -f "$asset" ]; then
    echo "-- $key: lade $asset"
    curl -fsSL --retry 3 -o "$asset.download" "$url"
    printf '%s  %s\n' "$sha" "$asset.download" | sha256sum -c -
    mv "$asset.download" "$asset"
  else
    echo "-- $key: bereits vorhanden"
    printf '%s  %s\n' "$sha" "$asset" | sha256sum -c -
  fi
done < pins.tsv
echo

echo "== 3. Entpacken ============================================================"
for key in llama_cpp_android llama_cpp_android_snapdragon llama_rn_jni_libs; do
  asset=$(awk -F'\t' -v k="$key" '$1==k {print $4}' pins.tsv)
  [ -n "$asset" ] || continue
  out="${asset%.tar.gz}"
  [ -d "$out" ] || { mkdir -p "$out"; tar -xzf "$asset" -C "$out"; }
done
echo

echo "== 4. Audit: welche Backends sind wirklich drin? =========================="
for dir in */; do
  [ -d "$dir" ] || continue
  echo "--- ${dir%/}"
  python3 "$AUDIT" "$dir" || true
  echo
done

echo "== 5. Was das bedeutet ====================================================="
cat <<'EOF'
Zusammenfassung der Prüfergebnisse (Stand der Pin-Stände in libs.lock.json):

 * llama-bNNNNN-bin-android-arm64.tar.gz
     -> CPU-Only. Aber: mit GGML_CPU_ALL_VARIANTS, d.h. libggml-cpu-android_armv8.2_*.so /
        _armv9.2_*.so mit echter dotprod/i8mm/SVE2-Auswahl zur Laufzeit.
        Enthält zusätzlich libmtmd.so mit der mtmd_helper_gen_audio_*-API, die unsere
        Engine braucht.
        NICHT austauschbar gegen unsere libllama.so: andere llama.cpp-Version (ABI der
        Params-Strukturen), aufgeteilt in libggml-base/libggml/libllama, und unsere
        JNI-Bridge (librnllama.so) verlinkt statisch gegen die eine, monolithische
        libllama.so.

 * ...-bin-android-arm64-snapdragon.tar.gz
     -> libggml-opencl.so (Adreno) + libggml-hexagon.so/libggml-htp-vNN.so (Hexagon NPU).
        Auf einem Pixel (Tensor G5, PowerVR-GPU) läuft OpenCL dort nicht, und die
        Modul-Dateien brauchen libggml-base.so exakt derselben Buildnummer.

 * llama-rn-android-jni-libs.tar.gz
     -> Monolithische librnllama_*.so inkl. Audio-/mtmd-Helfer, aber OHNE die
        Java_org_nehuatl_*-JNI-Symbole unserer Kotlin-Schicht und mit anderer
        llama.cpp-Buildnummer.

Kein dieser Downloads macht aus dem vorhandenen APK ein GPU-APK. Der einzige Weg ist
ein eigener Build, der die JNI-Bridge und die Engine-Quellen kombiniert:

    native/build_engine.sh --backend vulkan      # Pixel / Mali / alles mit Vulkan
    native/build_engine.sh --backend all         # + OpenCL + Hexagon (Snapdragon)

EOF
