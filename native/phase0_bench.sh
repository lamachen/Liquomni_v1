#!/usr/bin/env bash
# Phase 0: misst auf dem Pixel, ob Vulkan (GPU) oder CPU schneller ist.
# Aufruf (WSL2/Linux, Pixel per USB, USB-Debugging an):
#   export ANDROID_HOME=...        # NDK muss unter $ANDROID_HOME/ndk/<version> liegen
#   export VULKAN_HEADERS=$HOME/Vulkan-Headers      # git clone --depth 1 --branch v1.4.313 ...
#   native/phase0_bench.sh /pfad/zu/LFM3-Omni-2B-Abliter8-Q4_K_M.gguf
#
# Entscheidungsregel: GPU wird Default, wenn tg(GPU) >= 0.9 * tg(CPU) oder pp deutlich besser ist.
# Sonst bleibt CPU Default, die GPU übernimmt höchstens Prefill / Encoder.
set -euo pipefail

MODEL="${1:?Pfad zum GGUF-Hauptmodell angeben}"
NDK="${ANDROID_NDK_HOME:-$(ls -d "$ANDROID_HOME"/ndk/* | sort -V | tail -1)}"
LLAMA_DIR="${LLAMA_DIR:-$HOME/llama.cpp}"
THREADS="${THREADS:-4,5,6}"      # Pixel 10: Big/Mid-Kerne testen, nicht alle 8
ARCH="${ARCH:-armv8.2-a+dotprod+i8mm}"

[[ -d "$LLAMA_DIR" ]] || git clone --depth 1 https://github.com/ggml-org/llama.cpp "$LLAMA_DIR"
[[ -d "${VULKAN_HEADERS:-}/include/vulkan" ]] || { echo "VULKAN_HEADERS fehlt" >&2; exit 1; }
GLSLC="$(ls "$NDK"/shader-tools/*/glslc | head -1)"

cmake -S "$LLAMA_DIR" -B "$LLAMA_DIR/b-android" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-31 \
  -DBUILD_SHARED_LIBS=OFF -DGGML_OPENMP=OFF -DLLAMA_CURL=OFF \
  -DGGML_VULKAN=ON -DGGML_CPU_ARM_ARCH="$ARCH" \
  -DVulkan_GLSLC_EXECUTABLE="$GLSLC" \
  -DVulkan_INCLUDE_DIR="$VULKAN_HEADERS/include"
cmake --build "$LLAMA_DIR/b-android" -t llama-bench -j"$(nproc)"

adb push "$LLAMA_DIR/b-android/bin/llama-bench" /data/local/tmp/
adb push "$MODEL" /data/local/tmp/m.gguf
adb shell 'chmod +x /data/local/tmp/llama-bench'

# Gerät vorher abkühlen lassen und Display an lassen; Thermik verfälscht sonst die zweite Messung.
echo "=== CPU (ngl 0) und GPU (ngl 99) ==="
adb shell "cd /data/local/tmp && ./llama-bench -m m.gguf -p 256 -n 64 -ngl 0,99 -t $THREADS -r 3" \
  | tee bench_$(date +%Y%m%d_%H%M%S).txt

echo
echo "Auswerten: Spalten pp256 (Prefill) und tg64 (Decode) in t/s vergleichen."
echo "GPU Default, wenn tg(ngl=99) >= 0.9 * tg(ngl=0) oder pp deutlich höher."
