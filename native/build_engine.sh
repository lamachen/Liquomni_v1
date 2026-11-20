#!/usr/bin/env bash
#
# native/build_engine.sh — baut den GPU-fähigen Native-Engine-Kern für Liquomni.
#
# Ergebnis:
#   app/src/main/jniLibs/arm64-v8a/libllama.so   llama.cpp + ggml + mtmd (+ Vulkan/OpenCL/Hexagon)
#                                                + omni_shim.cpp (flache C-API für JNA)
#   native/provenance.json                       Build-Metadaten (SHA, Flags, Backends)
#
# Es gibt KEINE JNI-Bridge (librnllama.so) mehr. Die App ruft per JNA direkt gegen
# libllama.so; Struct-Layouts und Offsets übernimmt native/omni_shim.cpp (omni_load,
# omni_lctx, omni_mtmd, ...). Siehe Kopfkommentar in native/CMakeLists.txt.
#
# Warum selbst bauen? Die committeten Blobs waren CPU-only:
#   python3 native/native_audit.py app/src/main/jniLibs/arm64-v8a
#   -> "GPU-BACKENDS: KEINE"
#
# Voraussetzungen:
#   Android NDK 27+, CMake >= 3.24, Ninja, Git, Python3
#   für Vulkan: glslc (NDK shader-tools reicht) + Vulkan-Headers (VULKAN_HEADERS=<pfad>)
#
# Aufruf:
#   native/build_engine.sh                        # Standard: Vulkan, armv8.2+dotprod+i8mm
#   native/build_engine.sh --backend vulkan --no-strip
#   native/build_engine.sh --backend cpu          # Referenz-Build ohne GPU (A/B-Messung)
#   native/build_engine.sh --arch-flags "-march=armv9.2-a+sve2"   # nur SVE2-Geräte!
#   native/build_engine.sh --dry-run
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
NATIVE_DIR="$ROOT/native"
WORK_DIR="${OMNI_WORK_DIR:-$NATIVE_DIR/.cache}"
OUT_DIR="$ROOT/app/src/main/jniLibs/arm64-v8a"
ABI="arm64-v8a"
ANDROID_PLATFORM="${ANDROID_PLATFORM:-android-31}"
LLAMA_RN_REF="${LLAMA_RN_REF:-v0.13.0-rc.12}"
CPU_FLAGS="-march=armv8.2-a+dotprod+i8mm"
VULKAN=ON
OPENCL=OFF      # auf Pixel (PowerVR) nicht nutzbar -> nur per --backend opencl/all
HEXAGON=OFF
DRY=0
STRIP=1

while [[ $# -gt 0 ]]; do
  case "$1" in
    --backend) shift; case "${1:-vulkan}" in
        vulkan)  VULKAN=ON;  OPENCL=OFF; HEXAGON=OFF ;;
        opencl)  VULKAN=OFF; OPENCL=ON;  HEXAGON=OFF ;;
        hexagon) VULKAN=OFF; OPENCL=OFF; HEXAGON=ON  ;;
        all)     VULKAN=ON;  OPENCL=ON;  HEXAGON=OFF ;;
        cpu)     VULKAN=OFF; OPENCL=OFF; HEXAGON=OFF ;;
        *) echo "unbekanntes Backend: ${1:-}" >&2; exit 2 ;;
      esac; shift ;;
    --arch-flags) CPU_FLAGS="$2"; shift 2 ;;
    --out) OUT_DIR="$2"; shift 2 ;;
    --no-strip) STRIP=0; shift ;;
    --dry-run) DRY=1; shift ;;
    -h|--help) sed -n '1,32p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "Unbekannte Option: $1" >&2; exit 2 ;;
  esac
done

say() { printf '\n\033[1;36m== %s\033[0m\n' "$*"; }
die() { printf '\033[1;31mFEHLER:\033[0m %s\n' "$*" >&2; exit 1; }

say "Konfiguration"
cat <<EOF
  ABI                 : $ABI ($ANDROID_PLATFORM)
  Vulkan-Backend      : $VULKAN
  OpenCL-Backend      : $OPENCL
  Hexagon-Backend     : $HEXAGON
  CPU-Flags           : $CPU_FLAGS
  llama.rn-Ref        : $LLAMA_RN_REF   (liefert vendor/llama.cpp)
  Ausgabe             : $OUT_DIR
EOF

# ---------------------------------------------------------------- Prüfschritte
missing=()
check() { command -v "$1" >/dev/null 2>&1 || missing+=("$1 ($2)"); }
check git git
check cmake "Android SDK 'cmake;3.31.6' oder apt install cmake"
check ninja ninja-build
check python3 python3

if [[ $DRY -eq 1 ]]; then
  say "Prüfungen (Trockenlauf)"
  if [[ ${#missing[@]} -eq 0 ]]; then
    echo "  alle Werkzeuge vorhanden ✓"
  else
    echo "  fehlt noch:"; for m in "${missing[@]}"; do echo "    - $m"; done
  fi
  if [[ "$VULKAN" == "ON" ]]; then
    echo "  Vulkan braucht zusätzlich: glslc (NDK shader-tools) + Vulkan-Headers (VULKAN_HEADERS=<pfad>)"
  fi
else
  for m in "${missing[@]}"; do die "Werkzeug fehlt: $m"; done
fi

if [[ $DRY -eq 0 ]]; then
  NDK_DIR="${ANDROID_NDK_HOME:-${ANDROID_NDK:-}}"
  if [[ -z "$NDK_DIR" && -n "${ANDROID_HOME:-}" && -d "$ANDROID_HOME/ndk" ]]; then
    NDK_DIR="$(ls -d "$ANDROID_HOME/ndk"/* 2>/dev/null | sort -V | tail -1 || true)"
  fi
  [[ -n "$NDK_DIR" && -f "$NDK_DIR/build/cmake/android.toolchain.cmake" ]] || die \
"Android NDK nicht gefunden. Setze ANDROID_NDK_HOME oder ANDROID_HOME/ndk/<version>.
   Download: https://developer.android.com/ndk/downloads   (NDK 27.3.13750724 empfohlen)"
  TOOLCHAIN="$NDK_DIR/build/cmake/android.toolchain.cmake"
  GLSLC="${GLSLC_EXECUTABLE:-$(ls "$NDK_DIR"/shader-tools/*/glslc 2>/dev/null | head -1 || true)}"
  if [[ "$VULKAN" == "ON" ]]; then
    command -v glslc >/dev/null 2>&1 || [[ -x "${GLSLC:-}" ]] || die \
"Vulkan-Build braucht glslc (Shaderc): 'apt install shaderc-glslc' oder
   GLSLC_EXECUTABLE=/pfad/zu/glslc setzen (NDK: \$NDK/shader-tools/<platform>/glslc)."
    [[ -n "${VULKAN_HEADERS:-}" && -d "${VULKAN_HEADERS}/include/vulkan" ]] || die \
"Vulkan-Build braucht die Vulkan-Headers (NDK-Sysroot ist für ggml-vulkan zu alt):
   git clone --depth 1 --branch v1.4.313 https://github.com/KhronosGroup/Vulkan-Headers \$HOME/Vulkan-Headers
   export VULKAN_HEADERS=\$HOME/Vulkan-Headers   (Tag an den llama.cpp-Stand anpassen)"
  fi
fi

mkdir -p "$WORK_DIR"

# ---------------------------------------------------------------- Quellen holen
fetch() { # fetch <git-url> <ref> <dir>
  local url="$1" ref="$2" dir="$3"
  if [[ -d "$dir/.git" ]]; then
    say "Update $dir ($ref)"
    git -C "$dir" fetch --depth 1 origin "$ref" >/dev/null && git -C "$dir" checkout -q FETCH_HEAD
  else
    say "Clone $url ($ref)"
    git clone --depth 1 --branch "$ref" "$url" "$dir" || {
      # Ref ist kein Branch/Tag (z. B. Commit-Sha) -> zwei Schritte
      git clone "$url" "$dir" && git -C "$dir" checkout -q "$ref"
    }
  fi
}

LLAMA_RN_DIR="$WORK_DIR/llama.rn"
if [[ $DRY -eq 0 ]]; then
  fetch https://github.com/mybigday/llama.rn.git "$LLAMA_RN_REF" "$LLAMA_RN_DIR"
  # vendor/llama.cpp ist ein Submodul bzw. liegt eingecheckt vor
  if [[ ! -d "$LLAMA_RN_DIR/vendor/llama.cpp/ggml" ]]; then
    git -C "$LLAMA_RN_DIR" submodule update --init --depth 1 vendor/llama.cpp 2>/dev/null || true
  fi
  [[ -d "$LLAMA_RN_DIR/vendor/llama.cpp/ggml" ]] || die "vendor/llama.cpp fehlt im llama.rn-Checkout"
fi

BUILD_DIR="$WORK_DIR/build-$ABI"

# ---------------------------------------------------------------- Bauen
CMAKE_ARGS=(
  -S "$NATIVE_DIR"
  -B "$BUILD_DIR"
  -G "${CMAKE_GENERATOR:-Ninja}"
  -DCMAKE_BUILD_TYPE=Release
  -DOMNI_LLAMA_RN_DIR="$LLAMA_RN_DIR"
  -DOMNI_ENABLE_VULKAN="$VULKAN"
  -DOMNI_ENABLE_OPENCL="$OPENCL"
  -DOMNI_ENABLE_HEXAGON="$HEXAGON"
  -DOMNI_CPU_ARCH_FLAGS="$CPU_FLAGS"
  -DPython3_EXECUTABLE="$(command -v python3)"
)
if [[ $DRY -eq 0 ]]; then
  CMAKE_ARGS+=(
    -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN"
    -DANDROID_ABI="$ABI"
    -DANDROID_PLATFORM="$ANDROID_PLATFORM"
    -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON
    -DCMAKE_FIND_ROOT_PATH_MODE_PACKAGE=BOTH
    -DCMAKE_FIND_ROOT_PATH_MODE_INCLUDE=BOTH
  )
  [[ -n "${GLSLC:-}" && -x "${GLSLC:-}" ]] && CMAKE_ARGS+=(-DVulkan_GLSLC_EXECUTABLE="$GLSLC")
  [[ -n "${VULKAN_HEADERS:-}" ]] && CMAKE_ARGS+=(-DVulkan_INCLUDE_DIR="$VULKAN_HEADERS/include")
  [[ -n "${SPIRV_HEADERS_DIR:-}" ]] && CMAKE_ARGS+=(-DSPIRV-Headers_DIR="$SPIRV_HEADERS_DIR")
fi

say "CMake configure"
printf '  cmake %s\n' "${CMAKE_ARGS[*]}"
if [[ $DRY -eq 1 ]]; then
  echo
  echo "Trockenlauf — es wurde nichts gebaut."
  echo "Nächste Schritte:"
  echo "  1. NDK + cmake + glslc + Vulkan-Headers bereitstellen"
  echo "  2. native/build_engine.sh --backend vulkan --no-strip"
  echo "  3. python3 native/native_audit.py --gate gpu $OUT_DIR"
  echo "  4. ./gradlew installDebug   (auf dem Gerät testen)"
  exit 0
fi
cmake "${CMAKE_ARGS[@]}"

say "CMake build"
cmake --build "$BUILD_DIR" --parallel "$(nproc)"

# ---------------------------------------------------------------- Einsammeln
LIB="$(find "$BUILD_DIR" -name 'libllama.so' | head -1)"
[[ -n "$LIB" ]] || die "libllama.so im Build-Baum nicht gefunden ($BUILD_DIR)"

if [[ $STRIP -eq 1 ]]; then
  STRIP_BIN="$(ls "$NDK_DIR"/toolchains/llvm/prebuilt/*/bin/llvm-strip 2>/dev/null | head -1 || true)"
  [[ -n "${STRIP_BIN:-}" ]] && "$STRIP_BIN" --strip-unneeded "$LIB"
fi

say "Installiere nach $OUT_DIR"
mkdir -p "$OUT_DIR"
cp -f "$LIB" "$OUT_DIR/libllama.so"
echo "  libllama.so  ($(stat -c%s "$OUT_DIR/libllama.so") Bytes)"

# Altlasten der JNI-Bridge entfernen: die App lädt sie nicht mehr, und die CPU-only-Varianten
# (librnllama_v8*.so, ohne mtmd) dürfen nie mitgepackt werden.
rm -f "$OUT_DIR"/librnllama*.so

python3 - "$OUT_DIR" "$NATIVE_DIR/provenance.json" "$LLAMA_RN_DIR" "$CPU_FLAGS" "$VULKAN" "$OPENCL" "$HEXAGON" <<'PY'
import datetime, hashlib, json, os, subprocess, sys
out_dir, prov_path, rn_dir, cpu, vulkan, opencl, hexagon = sys.argv[1:8]
def sha(p):
    with open(p, "rb") as fh:
        return hashlib.sha256(fh.read()).hexdigest()
libs = {}
for name in sorted(os.listdir(out_dir)):
    if name.endswith(".so"):
        p = os.path.join(out_dir, name)
        libs[name] = {"sha256": sha(p), "bytes": os.path.getsize(p)}
def git_ref(d):
    try:
        return subprocess.check_output(["git", "-C", d, "rev-parse", "--short", "HEAD"], text=True).strip()
    except Exception:
        return "unbekannt"
lib_sha = libs.get("libllama.so", {}).get("sha256", "")
prov = {
    "generated_at": datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds"),
    "builder": "native/build_engine.sh",
    "abi": "arm64-v8a",
    "build_id": lib_sha[:12],          # = NativeBuild.id (Schlüssel für BackendTuner)
    "llama_rn_commit": git_ref(rn_dir),
    "llama_cpp_commit": git_ref(os.path.join(rn_dir, "vendor", "llama.cpp")),
    "cpu_arch_flags": cpu,
    "shim_abi_version": 1,
    "backends": {"vulkan": vulkan == "ON", "opencl": opencl == "ON", "hexagon": hexagon == "ON"},
    "libraries": libs,
}
with open(prov_path, "w") as fh:
    json.dump(prov, fh, indent=2, ensure_ascii=False)
    fh.write("\n")
print("Provenienz: " + prov_path)
PY

say "Verifikation"
# Der Shim-Export muss da sein, sonst findet JNA omni_load() nicht.
if command -v nm >/dev/null 2>&1; then
  nm -D --defined-only "$OUT_DIR/libllama.so" | grep -q ' omni_load$' \
    || die "omni_load fehlt in libllama.so (omni_shim.cpp nicht eingebunden?)"
fi
if [[ "$VULKAN" == "ON" || "$OPENCL" == "ON" || "$HEXAGON" == "ON" ]]; then
  python3 "$NATIVE_DIR/native_audit.py" --gate gpu "$OUT_DIR" || die \
"Der gebaute Engine-Kern enthält KEIN GPU-Backend. Build-Flags prüfen (GGML_VULKAN/GLSLC/Vulkan-Headers)."
else
  python3 "$NATIVE_DIR/native_audit.py" "$OUT_DIR" || true
fi

cat <<'EOF'

Fertig. Prüfen, ob die App wirklich auf die GPU geht:
  ./gradlew installDebug
  adb logcat | grep BACKEND
Erwartete Zeile:  BACKEND: Geräte=[CPU(armv8 ...), VULKAN(PowerVR ...)] | offload=aktiv
EOF
