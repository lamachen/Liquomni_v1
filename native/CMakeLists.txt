# native/CMakeLists.txt
#
# Baut EINE Bibliothek: libllama.so = llama.cpp + ggml (+ Vulkan/OpenCL/Hexagon) + mtmd
# (inkl. LFM3-Omni Audio-Gen-Helfer) + native/omni_shim.cpp.
#
# Es gibt bewusst KEINE JNI-Bridge mehr (org.nehuatl.llamacpp / librnllama.so):
#   * Die Bridge brauchte ihren eigenen, geprefixten (lm_ggml_*) llama.cpp-Stand und die
#     rn-*.cpp aus derselben Version. Beides lässt sich nicht mit dem vendor/llama.cpp
#     aus llama.rn mischen -> das alte Rezept konnte nie linken.
#   * Die App ruft ohnehin alles per JNA gegen libllama.so. Was früher über Struct-Offsets
#     (RN_OFFSET_LCTX = 0x16d0, context_map-Hash-Walk) geraten wurde, liefert jetzt
#     omni_shim.cpp als flache C-Funktionen (omni_load / omni_lctx / omni_mtmd ...).
#
# Aufruf nicht direkt, sondern über native/build_engine.sh.

cmake_minimum_required(VERSION 3.24)   # $<LINK_LIBRARY:WHOLE_ARCHIVE,...>
project(omni_native_engine LANGUAGES C CXX)

option(OMNI_ALLOW_HOST "Nur zum Testen der CMake-Logik auf dem Host (kein Android-Build)" OFF)
if(NOT CMAKE_SYSTEM_NAME STREQUAL "Android" AND NOT OMNI_ALLOW_HOST)
  message(FATAL_ERROR
    "Android-NDK-Cross-Build erwartet. Bitte über native/build_engine.sh aufrufen "
    "(setzt CMAKE_TOOLCHAIN_FILE).")
endif()

# --- Eingänge -----------------------------------------------------------------
# OMNI_LLAMA_RN_DIR: Checkout von mybigday/llama.rn; dessen vendor/llama.cpp ist die Quelle
# (enthält die mtmd-Audio-Gen-Helfer für LFM3-Omni). Alternativ OMNI_LLAMA_DIR direkt setzen.
set(OMNI_LLAMA_RN_DIR "" CACHE PATH "Pfad zum llama.rn-Checkout (mit vendor/llama.cpp)")
set(OMNI_LLAMA_DIR    "" CACHE PATH "Direkter Pfad zu llama.cpp (überschreibt OMNI_LLAMA_RN_DIR)")

option(OMNI_ENABLE_VULKAN "Vulkan-Backend (Pixel/Tensor, Mali, Adreno, PowerVR)" ON)
option(OMNI_ENABLE_OPENCL "OpenCL-Backend (Adreno; auf Pixel i. d. R. nicht nutzbar)" OFF)
option(OMNI_ENABLE_HEXAGON "Hexagon-NPU-Backend (nur Qualcomm)" OFF)
set(OMNI_CPU_ARCH_FLAGS "-march=armv8.2-a+dotprod+i8mm" CACHE STRING
    "-march für die ggml-CPU-Kernel. armv9.2-a+sve2 läuft nur auf SVE2-Geräten (sonst SIGILL).")

if(OMNI_LLAMA_DIR)
  set(LLAMA_DIR "${OMNI_LLAMA_DIR}")
elseif(OMNI_LLAMA_RN_DIR)
  set(LLAMA_DIR "${OMNI_LLAMA_RN_DIR}/vendor/llama.cpp")
endif()
if(NOT LLAMA_DIR OR NOT IS_DIRECTORY "${LLAMA_DIR}/ggml")
  message(FATAL_ERROR "llama.cpp nicht gefunden: '${LLAMA_DIR}' "
                      "(OMNI_LLAMA_RN_DIR bzw. OMNI_LLAMA_DIR setzen)")
endif()

# --- llama.cpp über sein eigenes CMake ---------------------------------------
# Muss VOR add_subdirectory stehen, damit ggml/llama die Werte erben.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP      OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_UI       OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON   OFF CACHE BOOL "" FORCE)  # shim braucht weder common noch HTTP
set(LLAMA_OPENSSL        OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_MTMD     ON  CACHE BOOL "" FORCE)  # libmtmd inkl. mtmd-helper-gen (Audio-Gen)

set(GGML_OPENMP   OFF CACHE BOOL "" FORCE)         # keine libomp.so-Abhängigkeit
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE   OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)       # Backends in den Monolithen
set(GGML_CPU_ALL_VARIANTS OFF CACHE BOOL "" FORCE) # statisch -> eine ISA
set(GGML_VULKAN ${OMNI_ENABLE_VULKAN} CACHE BOOL "" FORCE)
set(GGML_OPENCL ${OMNI_ENABLE_OPENCL} CACHE BOOL "" FORCE)
if(OMNI_ENABLE_HEXAGON)
  set(GGML_HEXAGON ON CACHE BOOL "" FORCE)
endif()

# WICHTIG: -march muss an ggml-cpu, nicht nur an irgendeine Bridge. Ohne GGML_CPU_ARM_ARCH
# baut ggml-cpu mit GGML_NATIVE=OFF auf Baseline-armv8.0 (kein dotprod/i8mm) und wäre
# LANGSAMER als der bisherige Blob (1245 dotprod-/444 i8mm-Kernel, siehe native_audit.py).
if(CMAKE_SYSTEM_PROCESSOR MATCHES "aarch64|arm64" OR ANDROID_ABI STREQUAL "arm64-v8a")
  string(REGEX REPLACE "^-march=" "" _omni_arm "${OMNI_CPU_ARCH_FLAGS}")
  set(GGML_CPU_ARM_ARCH "${_omni_arm}" CACHE STRING "" FORCE)
  message(STATUS "OMNI: GGML_CPU_ARM_ARCH=${GGML_CPU_ARM_ARCH}")
endif()

add_subdirectory("${LLAMA_DIR}" llama-build EXCLUDE_FROM_ALL)

foreach(_t llama mtmd ggml ggml-base ggml-cpu)
  if(NOT TARGET ${_t})
    message(FATAL_ERROR "Erwartetes llama.cpp-Target '${_t}' fehlt (geänderter Quellstand?)")
  endif()
endforeach()

# --- libllama.so: Monolith ----------------------------------------------------
set(OMNI_WHOLE llama mtmd ggml ggml-base ggml-cpu)
foreach(_b ggml-vulkan ggml-opencl ggml-hexagon)
  if(TARGET ${_b})
    list(APPEND OMNI_WHOLE ${_b})
  endif()
endforeach()
string(REPLACE ";" "," OMNI_WHOLE_CSV "${OMNI_WHOLE}")
message(STATUS "OMNI: whole-archive: ${OMNI_WHOLE_CSV}")

add_library(omni_engine SHARED "${CMAKE_CURRENT_SOURCE_DIR}/omni_shim.cpp")
set_target_properties(omni_engine PROPERTIES
  OUTPUT_NAME "llama"                       # -> libllama.so
  CXX_STANDARD 17 CXX_STANDARD_REQUIRED ON
  POSITION_INDEPENDENT_CODE ON)
target_include_directories(omni_engine PRIVATE
  "${LLAMA_DIR}/include"
  "${LLAMA_DIR}/ggml/include"
  "${LLAMA_DIR}/tools/mtmd")
# Alle llama_*/ggml_*/mtmd_*-Symbole müssen für JNA sichtbar bleiben -> ganze Archive,
# keine --gc-sections, keine Visibility-Einschränkung.
target_link_libraries(omni_engine PRIVATE
  "$<LINK_LIBRARY:WHOLE_ARCHIVE,${OMNI_WHOLE_CSV}>")
target_link_options(omni_engine PRIVATE
  "-Wl,--build-id=sha1"
  "-Wl,-soname,libllama.so")
if(ANDROID)
  find_library(LOG_LIB log REQUIRED)
  find_library(ANDROID_LIB android REQUIRED)
  target_link_libraries(omni_engine PRIVATE ${LOG_LIB} ${ANDROID_LIB} m dl)
endif()

message(STATUS "OMNI: libllama.so (Vulkan=${OMNI_ENABLE_VULKAN} OpenCL=${OMNI_ENABLE_OPENCL} Hexagon=${OMNI_ENABLE_HEXAGON}) aus ${LLAMA_DIR}")
