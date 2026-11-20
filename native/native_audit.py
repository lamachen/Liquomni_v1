#!/usr/bin/env python3
"""
native_audit.py — Prüft die mitgelieferten llama.cpp/GLM-Native-Bibliotheken (.so) auf
Provenienz, API-Vollständigkeit, GPU-Fähigkeit und CPU-Instruction-Set.

Hintergrund: ditthis Repo enthält hand-committete Binary-Blobs in
`app/src/main/jniLibs/arm64-v8a/`. Früher war nicht dokumentiert, woher die stammen,
welcher llama.cpp-Build sie sind und ob sie GPU-Backends enthalten. Dieses Skript macht
genau das maschinell prüfbar und ist die Grundlage von `native/README.md`,
`scripts/fetch-*.sh` und dem CI-Gate `.github/workflows/native-libs.yml`.

Nur Python-Standardbibliothek. Optional: `pip install capstone` für die
Instruction-Set-Analyse (dotprod/i8mm/SVE) — ohne capstone wird die Analyse übersprungen.

Aufruf:
    python3 native/native_audit.py app/src/main/jniLibs/arm64-v8a
    python3 native/native_audit.py app/src/main/jniLibs/arm64-v8a --gate audio
    python3 native/native_audit.py app/src/main/jniLibs/arm64-v8a --json report.json
Exit-Code: 0 ok, 1 Gate verletzt, 2 Fehler.
"""

from __future__ import annotations

import argparse
import collections
import hashlib
import json
import os
import re
import struct
import sys

DT_NEEDED = 1
DT_SONAME = 14
DT_NULL = 0

# --------------------------------------------------------------------------- ELF-Parser


class ElfError(Exception):
    pass


class Elf:
    """Minimaler ELF64-Reader (AArch64), nur für statische Analyse."""

    def __init__(self, path: str):
        with open(path, "rb") as fh:
            self.data = fh.read()
        d = self.data
        if len(d) < 64 or d[:4] != b"\x7fELF" or d[4] != 2:
            raise ElfError(f"{path}: keine ELF64-Datei")
        self.e_type, self.e_machine = struct.unpack_from("<HH", d, 16)
        e_shoff = struct.unpack_from("<Q", d, 0x28)[0]
        e_shentsize, e_shnum, e_shstrndx = struct.unpack_from("<HHH", d, 0x3A)
        self.sections = []
        for i in range(e_shnum):
            off = e_shoff + i * e_shentsize
            v = struct.unpack_from("<IIQQQQIIQQ", d, off)
            self.sections.append(
                {
                    "name": v[0],
                    "type": v[1],
                    "addr": v[3],
                    "offset": v[4],
                    "size": v[5],
                    "link": v[6],
                    "entsize": v[9],
                }
            )
        shstr = self.sections[e_shstrndx]
        for s in self.sections:
            s["nm"] = self._cstr(shstr["offset"] + s["name"])
        self._dynstr = self._sec(".dynstr")
        self._dynsym = self._sec(".dynsym")
        self._dynamic = self._sec(".dynamic")

    def _cstr(self, pos: int) -> str:
        d = self.data
        end = d.find(b"\0", pos)
        if end < 0:
            end = len(d)
        return d[pos:end].decode("utf-8", "replace")

    def _sec(self, name: str):
        for s in self.sections:
            if s["nm"] == name:
                return s
        return None

    def soname(self) -> str | None:
        return self._dynstr_value(DT_SONAME)

    def needed(self) -> list[str]:
        out = []
        for tag, val in self._dynamic_entries():
            if tag == DT_NEEDED:
                out.append(self._cstr(self._dynstr["offset"] + val))
        return out

    def _dynstr_value(self, want_tag: int) -> str | None:
        for tag, val in self._dynamic_entries():
            if tag == want_tag:
                return self._cstr(self._dynstr["offset"] + val)
            if tag == DT_NULL:
                break
        return None

    def _dynamic_entries(self):
        if not self._dynamic:
            return
        d, off, base = self.data, self._dynamic["offset"], self._dynamic["offset"] + self._dynamic["size"]
        while off + 16 <= base:
            tag, val = struct.unpack_from("<qQ", d, off)
            off += 16
            yield tag, val
            if tag == DT_NULL:
                return

    def symbols(self, defined_only: bool = True) -> list[dict]:
        if not (self._dynsym and self._dynstr):
            return []
        d = self.data
        out = []
        n = self._dynsym["size"] // max(1, self._dynsym["entsize"])
        for i in range(n):
            off = self._dynsym["offset"] + i * self._dynsym["entsize"]
            st_name, st_info, _, st_shndx, st_value, st_size = struct.unpack_from("<IBBHQQ", d, off)
            if not st_name:
                continue
            if defined_only and st_shndx == 0:
                continue
            out.append(
                {
                    "name": self._cstr(self._dynstr["offset"] + st_name),
                    "value": st_value,
                    "size": st_size,
                    "type": st_info & 0xF,
                }
            )
        return out

    def global_data(self, name: str) -> bytes | None:
        """Liest den Inhalt eines exportierten Daten-Symbols (z.B. LLAMA_BUILD_NUMBER)."""
        for s in self.symbols():
            if s["name"] == name and s["type"] == 1:  # STT_OBJECT
                for sec in self.sections:
                    if sec["addr"] and sec["addr"] <= s["value"] < sec["addr"] + sec["size"]:
                        o = sec["offset"] + (s["value"] - sec["addr"])
                        ln = s["size"] or 8
                        return self.data[o : o + ln]
        return None

    def section_bytes(self, name: str) -> bytes | None:
        sec = self._sec(name)
        if not sec:
            return None
        return self.data[sec["offset"] : sec["offset"] + sec["size"]]

    def rodata_strings(self) -> list[bytes]:
        out = []
        for nm in (".rodata", ".data", ".data.rel.ro"):
            b = self.section_bytes(nm)
            if b:
                out += re.findall(rb"[ -~]{5,160}", b)
        return out


# --------------------------------------------------------------------------- Analysen

# Funktionen, die der Kotlin-JNA-Layer (LlamaNativeBridge) zwingend braucht.
REQUIRED_OMNI_API = [
    # Kern
    "llama_get_model",
    "llama_get_memory",
    "llama_memory_clear",
    "llama_memory_seq_rm",
    "llama_set_embeddings",
    "llama_set_n_threads",
    "llama_decode",
    "llama_model_n_embd",
    "llama_model_get_vocab",
    "llama_vocab_n_tokens",
    "llama_token_to_piece",
    "llama_tokenize",
    "llama_sampler_chain_init",
    "llama_sampler_chain_add",
    "llama_sampler_init_temp",
    "llama_sampler_init_dist",
    "llama_sampler_sample",
    "llama_sampler_accept",
    "llama_sampler_free",
    # Multimodal
    "mtmd_support_vision",
    "mtmd_support_audio",
    "mtmd_get_audio_sample_rate",
    "mtmd_bitmap_init",
    "mtmd_bitmap_init_from_audio",
    "mtmd_bitmap_free",
    "mtmd_input_chunks_init",
    "mtmd_tokenize",
    "mtmd_helper_eval_chunks",
    # natives LFM3-Omni Speech-to-Speech
    "mtmd_helper_gen_audio_init",
    "mtmd_helper_gen_audio_reset",
    "mtmd_helper_gen_audio_free",
    "mtmd_helper_gen_audio_set_input",
    "mtmd_helper_gen_audio_get_logit_bias",
    "mtmd_helper_gen_audio_step_prompt",
    "mtmd_helper_gen_audio_next_is_text",
    "mtmd_helper_gen_audio_step_gen",
    "mtmd_helper_gen_audio_flush",
    "mtmd_helper_gen_audio_get_output",
    # Backend-Diagnose
    "llama_supports_gpu_offload",
    "llama_print_system_info",
    "llama_log_set",
    "ggml_backend_reg_count",
    "ggml_backend_reg_get",
    "ggml_backend_reg_name",
    "ggml_backend_dev_count",
    "ggml_backend_dev_get",
    "ggml_backend_dev_name",
    "ggml_backend_dev_description",
    "ggml_backend_dev_type",
    "ggml_backend_load_all_from_path",
    "ggml_backend_dev_by_type",
]

GPU_BACKEND_PATTERNS = {
    "vulkan": re.compile(rb"(?i)(vulkan|_vk_)"),
    "opencl": re.compile(rb"(?i)(opencl|_cl_)"),
    "hexagon": re.compile(rb"(?i)hexagon"),
    "cuda": re.compile(rb"(?i)\bcuda\b"),
    "metal": re.compile(rb"(?i)\bmetal\b"),
    "rocm": re.compile(rb"(?i)\brocm\b"),
    "webgpu": re.compile(rb"(?i)webgpu"),
}

ISA_MNEMONICS = {
    "dotprod": {"sdot", "udot"},
    "i8mm": {"smmla", "ummla", "sumla", "usmmla"},
    "sve": {"ptrue", "pfirst", "whilelo", "whilelt", "cntb", "ld1b", "ld1d", "st1d", "wrffr"},
    "sme": {"smstop", "smstart", "smstop_z", "ld1zw", "st1zw", "zero"},
}


def audit_library(path: str, with_isa: bool, isa_bytes: int = 0) -> dict:
    rep = {"file": os.path.basename(path), "path": path, "bytes": os.path.getsize(path)}
    try:
        elf = Elf(path)
    except ElfError as exc:
        rep["error"] = str(exc)
        return rep

    with open(path, "rb") as fh:
        digest = hashlib.sha256(fh.read()).hexdigest()
    rep["sha256"] = digest
    rep["machine"] = "AArch64" if elf.e_machine == 0xB7 else hex(elf.e_machine)
    rep["soname"] = elf.soname()
    rep["needed"] = elf.needed()

    export_names = {s["name"] for s in elf.symbols(True)}
    import_names = {s["name"] for s in elf.symbols(False)}
    rep["exported_symbols"] = len(export_names)

    # llama.cpp Build-Nummer / Commit (exportierte Globals, wenn vorhanden)
    bn = elf.global_data("LLAMA_BUILD_NUMBER")
    if bn and len(bn) >= 4:
        rep["llama_build_number"] = struct.unpack_from("<I", bn, 0)[0]
    for nm in ("LLAMA_COMMIT", "LLAMA_BUILD_TARGET", "LLAMA_COMPILER"):
        val = elf.global_data(nm)
        if val:
            ptr = struct.unpack_from("<Q", val, 0)[0] if len(val) >= 8 else 0
            # Zeiger auf String -> über Sections auflösen
            if ptr:
                for sec in elf.sections:
                    if sec["addr"] and sec["addr"] <= ptr < sec["addr"] + sec["size"]:
                        o = sec["offset"] + (ptr - sec["addr"])
                        rep[nm.lower()] = elf._cstr(o)[:64]
                        break

    # Rollen-Erkennung: Engine (ggml+llama+mtmd), JNI-Bridge oder Backend-Modul
    jni_exports = sorted(n for n in export_names if n.startswith("Java_"))
    is_engine = "llama_backend_init" in export_names or "llama_model_load_from_file" in export_names
    rep["role"] = (
        "engine" if is_engine else "jni-bridge" if jni_exports else "backend-module" if rep["needed"] and any(
            n.startswith("libggml") for n in rep["needed"]
        ) else "unknown"
    )
    rep["jni_exports"] = len(jni_exports)
    classes = set()
    for n in jni_exports:
        parts = n.split("_")
        if len(parts) >= 4:
            classes.add(".".join(parts[1:3]))
    rep["jni_classes"] = sorted(classes)[:4]

    # benötigte API (nur für die Engine-Bibliothek relevant)
    missing = [f for f in REQUIRED_OMNI_API if f not in export_names]
    rep["missing_required_symbols"] = missing if is_engine else []
    rep["has_required_api"] = bool(is_engine and not missing)

    # GPU-Backends
    blobs = elf.data
    gpu = {}
    for name, rx in GPU_BACKEND_PATTERNS.items():
        sym_hits = [n for n in export_names if rx.search(n.encode())]
        reg_symbol = (
            f"ggml_backend_{name}_reg" in export_names
            or (name == "vulkan" and ("ggml_backend_vk_reg" in export_names or "libvulkan.so" in rep["needed"]))
        )
        gpu[name] = {
            "reg_symbol": reg_symbol,
            "string_hits": len(rx.findall(blobs)),
            "symbols": len(sym_hits),
            "symbol_samples": sorted(sym_hits)[:3],
        }
    rep["gpu_backends"] = gpu
    rep["gpu_backends_enabled"] = sorted(n for n, v in gpu.items() if v["reg_symbol"] or v["symbols"] > 2)

    # Quellcode-Pfade aus dem Build (Provenienz)
    srcs = sorted({s.decode() for s in elf.rodata_strings() if b"/vendor/llama.cpp/" in s or b"llama.cpp/ggml" in s})[:5]
    if srcs:
        rep["build_source_paths"] = srcs

    if with_isa:
        rep["isa"] = isa_scan(elf, isa_bytes)
    return rep


def isa_scan(elf: Elf, max_bytes: int = 0) -> dict:
    """Disassembliert .text (falls capstone vorhanden) und zählt SIMD-/SVE-/SME-Instruktionen."""
    try:
        import capstone  # type: ignore
    except Exception:
        return {"skipped": "capstone nicht installiert (pip install capstone)"}
    text = elf._sec(".text")
    if not text:
        return {"skipped": "kein .text Abschnitt"}
    budget = text["size"] if max_bytes <= 0 else min(text["size"], max_bytes)
    code = elf.data[text["offset"] : text["offset"] + budget]
    try:
        md = capstone.Cs(capstone.CS_ARCH_ARM64, capstone.CS_MODE_ARM)
        counts = collections.Counter()
        total = 0
        for _, _, mnem, _ops in md.disasm_lite(code, text["addr"]):
            counts[mnem] += 1
            total += 1
    except Exception as exc:  # pragma: no cover
        return {"error": str(exc)}
    return {
        "instructions_scanned": total,
        "text_scanned_bytes": budget,
        "text_total_bytes": text["size"],
        **{feat: sum(counts.get(m, 0) for m in mnems) for feat, mnems in ISA_MNEMONICS.items()},
    }


def find_duplicate_variants(reports: list[dict]) -> list[list[str]]:
    """Findet '.so'-Dateien, die sich nur im Namen unterscheiden (text-identisch)."""
    by_text = collections.defaultdict(list)
    for r in reports:
        p = r["path"]
        try:
            elf = Elf(p)
            t = elf.section_bytes(".text")
            if t:
                by_text[hashlib.sha256(t).hexdigest()].append(r["file"])
        except ElfError:
            continue
    return [v for v in by_text.values() if len(v) > 1]


# --------------------------------------------------------------------------- CLI

GATES = {
    "audio": lambda r: r.get("has_required_api", False),
    "gpu": lambda r: bool(r.get("gpu_backends_enabled")),
}


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="Auditiert llama.cpp Native-Bibliotheken")
    ap.add_argument("path", help="Datei oder Verzeichnis mit .so-Bibliotheken")
    ap.add_argument("--gate", choices=sorted(GATES), help="Fehlschlagen, wenn Gate nicht erfüllt")
    ap.add_argument("--no-isa", action="store_true", help="Instruction-Analyse überspringen")
    ap.add_argument("--isa-bytes", type=int, default=0,
                    help="Max. Bytes .text für die Instruction-Analyse (0 = vollständig)")
    ap.add_argument("--json", metavar="DATEI", help="Report als JSON schreiben")
    args = ap.parse_args(argv)

    targets = []
    if os.path.isdir(args.path):
        for root, _dirs, files in os.walk(args.path):
            for f in sorted(files):
                if f.endswith(".so"):
                    targets.append(os.path.join(root, f))
    else:
        targets = [args.path]
    if not targets:
        print(f"keine .so unter {args.path}", file=sys.stderr)
        return 2

    with_isa = not args.no_isa
    reports = [audit_library(p, with_isa, args.isa_bytes) for p in targets]

    print(f"# Native-Audit: {args.path}  ({len(reports)} Bibliotheken)\n")
    for r in reports:
        print(f"## {r['file']}  ({r['bytes']/1e6:.1f} MB)")
        if "error" in r:
            print(f"   FEHLER: {r['error']}\n")
            continue
        print(f"   sha256      : {r['sha256'][:32]}…")
        print(f"   machine     : {r['machine']}   soname: {r['soname']}")
        if r["needed"]:
            print(f"   NEEDED      : {', '.join(r['needed'])}")
        if "llama_build_number" in r:
            print(f"   llama.cpp   : b{r['llama_build_number']}  commit={r.get('llama_commit', 'unbekannt')}")
        if r.get("build_source_paths"):
            print(f"   gebaut aus  : {r['build_source_paths'][0]}")
        print(f"   Exporte     : {r['exported_symbols']} Symbole   Rolle: {r['role']}")
        if r.get("jni_exports"):
            print(f"   JNI         : {r['jni_exports']} exportierte Java_* ({', '.join(r.get('jni_classes') or ['?'])})")
        if r["role"] == "engine":
            if r["missing_required_symbols"]:
                miss = r["missing_required_symbols"]
                print(f"   FEHLT (API) : {len(miss)} von {len(REQUIRED_OMNI_API)} — {', '.join(miss[:8])}" + (" …" if len(miss) > 8 else ""))
            else:
                print(f"   API         : alle {len(REQUIRED_OMNI_API)} benötigten LFM3-Omni-Funktionen vorhanden ✓")
        if r["role"] in ("engine", "backend-module", "unknown"):
            enabled = r["gpu_backends_enabled"]
            if enabled:
                print(f"   GPU-BACKENDS: {', '.join(enabled)} ✓")
            elif r["role"] == "engine":
                print("   GPU-BACKENDS: KEINE — diese Engine rechnet rein auf der CPU ✗")
        isa = r.get("isa") or {}
        if isa and "error" not in isa and "skipped" not in isa:
            print(
                f"   ISA-Kernels : dotprod={isa.get('dotprod', 0)}  i8mm={isa.get('i8mm', 0)}"
                f"  sve={isa.get('sve', 0)}  sme={isa.get('sme', 0)}"
                f"  ({isa.get('instructions_scanned', 0)} Instr. = "
                f"{100.0 * isa.get('text_scanned_bytes', 0) / max(1, isa.get('text_total_bytes', 1)):.0f}% von .text)"
            )
        elif isa:
            print(f"   ISA-Kernels: ({isa.get('skipped') or isa.get('error')})")
        print()

    dups = find_duplicate_variants(reports)
    for group in dups:
        print(f"WARNUNG: identischer .text-Code in {len(group)} Bibliotheken -> {', '.join(sorted(group))}")
        print("         Die CPU-Varianten unterscheiden sich NICHT in den SIMD-Kerneln;")
        print("         das Laden eines '-dotprod_i8mm'-Namens bringt daher keinen Vorteil.\n")

    if args.json:
        with open(args.json, "w") as fh:
            json.dump({"root": args.path, "libraries": reports, "duplicate_variants": dups}, fh, indent=2)
        print(f"JSON-Report: {args.json}")

    if args.gate:
        check = GATES[args.gate]
        ok = any(check(r) for r in reports if "error" not in r)
        if not ok:
            print(f"GATE '{args.gate}': NICHT ERFÜLLT — keine Bibliothek erfüllt die Bedingung.", file=sys.stderr)
            return 1
        print(f"GATE '{args.gate}': erfüllt ✓")
    return 0


if __name__ == "__main__":
    sys.exit(main())
