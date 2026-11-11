"""Builds the Node.js bundle for the APK from Termux's arm64 packages (SPIKE).

    python tools/node_bundle.py <out-dir>

Writes:
  <out-dir>/jniLibs/arm64-v8a/libnodejs.so   the node executable
  <out-dir>/jniLibs/arm64-v8a/lib*.so         the libraries it needs, renamed
  <out-dir>/assets/node/npm.zip               npm (JavaScript only)
  <out-dir>/assets/node/VERSION               the Node.js version

Every package is pinned by file name and SHA-256: the build stops if Termux serves anything else (it
also stops once Termux removes an old version; then update PINNED from its Packages index).

Android only extracts native files named lib*.so from the APK, so libraries named like libssl.so.3 are
renamed. The names they are linked by (DT_NEEDED, DT_SONAME) are rewritten in place in each file's
dynamic string table; every new name is no longer than the old one, so nothing moves. Termux's
RUNPATH stays but is unused: the app puts the native library folder on LD_LIBRARY_PATH, searched first.
Standard library only.
"""
import hashlib
import os
import io
import struct
import sys
import tarfile
import urllib.request
import zipfile
from pathlib import Path

REPO = "https://packages.termux.dev/apt/termux-main"
NODE_VERSION = "24.22.3"
PINNED = [
    ("pool/main/n/nodejs-lts/nodejs-lts_24.22.3_aarch64.deb", "80edcc73db025db0baa09be27e3d65a16e6f2a72ab925f1873a1b808ab857c28"),
    ("pool/main/c/c-ares/c-ares_1.34.9_aarch64.deb", "623d84855e176f3f2a415d24c0e1d2f529356f4b5439bb3313e6d7ff0da4585c"),
    ("pool/main/libc/libc++/libc++_30_aarch64.deb", "53d0b84a7ba7459024257cb94d5b136fe13ef858567f65a8064b35950799f2ca"),
    ("pool/main/libi/libicu/libicu_79.1_aarch64.deb", "83c980a010e957738e3567127801d66a1b9bd7e404a4fda98788048ee57d606c"),
    ("pool/main/libs/libsqlite/libsqlite_3.54.1-1_aarch64.deb", "661fb995f7215845007b222a85266ae99d9443e87e0604f8677edfd738e6b81a"),
    ("pool/main/o/openssl/openssl_1:3.6.5_aarch64.deb", "a2fb7856875e63ef6b90442752e05974813372c3ca86d2513a78ea43c67a0f8b"),
    ("pool/main/z/zlib/zlib_1.4_aarch64.deb", "e3027565ae50bcd8ba5113c988b589754ae192c9cc3e6afbe3e4ed7c06b2598e"),
    ("pool/main/n/npm/npm_12.2.2_all.deb", "edb4a1dc3a6967f1e65872c7cb7588bd3672152beab6ae633ba848931371e9e1"),
]
PREFIX = "data/data/com.termux/files/usr/"

# Name node links to (= the file under usr/lib) → name in the APK. Each new name is at most as long as
# the old one and does not clash with the app's other libraries (Python's are *_python.so).
LIBS = {
    "libz.so.1": "libnz.so",
    "libcares.so": "libcares.so",
    "libsqlite3.so": "libsqlite3.so",
    "libcrypto.so.3": "libncrypto.so",
    "libssl.so.3": "libnssl.so",
    "libicui18n.so.79": "libnicui18n.so",
    "libicuuc.so.79": "libnicuuc.so",
    "libicudata.so.79": "libnicudata.so",
    "libc++_shared.so": "libnc++.so",
}

DT_NULL, DT_NEEDED, DT_STRTAB, DT_STRSZ, DT_SONAME = 0, 1, 5, 10, 14
PT_LOAD, PT_DYNAMIC = 1, 2


def fetch(url: str) -> bytes:
    with urllib.request.urlopen(url, timeout=120) as r:
        return r.read()


def deb_files(data: bytes) -> dict[str, bytes]:
    """The files of a .deb (an ar archive holding data.tar.*), by path; symlinks resolved."""
    assert data[:8] == b"!<arch>\n"
    i, files = 8, {}
    while i < len(data):
        name = data[i:i + 16].decode().strip().rstrip("/")
        size = int(data[i + 48:i + 58])
        body = data[i + 60:i + 60 + size]
        i += 60 + size + size % 2
        if name.startswith("data.tar"):
            with tarfile.open(fileobj=io.BytesIO(body)) as tar:
                links = {}
                for member in tar.getmembers():
                    path = member.name.lstrip("./")
                    if member.isfile():
                        files[path] = tar.extractfile(member).read()
                    elif member.issym():
                        links[path] = member.linkname
                for path, target in links.items():
                    real = str(Path(path).parent / target).replace("\\", "/")
                    if real in files:
                        files[path] = files[real]
    return files


def _dynamic(elf: bytes) -> tuple[list[tuple[int, int]], int]:
    """The dynamic entries (tag, value) of a 64-bit little-endian ELF and its string table's file offset."""
    assert elf[:4] == b"\x7fELF" and elf[4] == 2 and elf[5] == 1, "not a 64-bit little-endian ELF"
    phoff, = struct.unpack_from("<Q", elf, 0x20)
    phentsize, phnum = struct.unpack_from("<HH", elf, 0x36)
    loads, dynamic = [], None
    for k in range(phnum):
        p_type, _, p_offset, p_vaddr, _, p_filesz = struct.unpack_from("<IIQQQQ", elf, phoff + k * phentsize)
        if p_type == PT_LOAD:
            loads.append((p_vaddr, p_offset, p_filesz))
        elif p_type == PT_DYNAMIC:
            dynamic = (p_offset, p_filesz)
    assert dynamic is not None, "no dynamic section"
    entries = []
    for off in range(dynamic[0], dynamic[0] + dynamic[1], 16):
        tag, value = struct.unpack_from("<qQ", elf, off)
        if tag == DT_NULL:
            break
        entries.append((tag, value))
    strtab = next(v for t, v in entries if t == DT_STRTAB)
    for vaddr, offset, size in loads:
        if vaddr <= strtab < vaddr + size:
            return entries, strtab - vaddr + offset
    raise AssertionError("string table outside the loaded segments")


def _string(elf: bytes, at: int) -> str:
    return elf[at:elf.index(b"\0", at)].decode()


def _string_refs(elf: bytes, entries: list[tuple[int, int]]) -> set[int]:
    """Every offset into the dynamic string table that something in the file points at."""
    refs = {v for t, v in entries if t in (DT_NEEDED, DT_SONAME, 15, 29)}  # + DT_RPATH, DT_RUNPATH
    shoff, = struct.unpack_from("<Q", elf, 0x28)
    shentsize, shnum = struct.unpack_from("<HH", elf, 0x3A)
    for k in range(shnum):
        _, sh_type, _, _, sh_offset, sh_size, _, sh_info, _, sh_entsize = struct.unpack_from(
            "<IIQQQQIIQQ", elf, shoff + k * shentsize)
        if sh_type == 11:  # SHT_DYNSYM: st_name of every symbol
            refs.update(struct.unpack_from("<I", elf, sh_offset + i)[0] for i in range(0, sh_size, sh_entsize or 24))
        elif sh_type == 0x6FFFFFFE:  # SHT_GNU_verneed: library and version names
            at = sh_offset
            for _ in range(sh_info):
                _, cnt, file, aux, nxt = struct.unpack_from("<HHIII", elf, at)
                refs.add(file)
                a = at + aux
                for _ in range(cnt):
                    _, _, _, name, anext = struct.unpack_from("<IHHII", elf, a)
                    refs.add(name)
                    a += anext
                at += nxt
        elif sh_type == 0x6FFFFFFD:  # SHT_GNU_verdef: version names
            at = sh_offset
            for _ in range(sh_info):
                _, _, _, cnt, _, aux, nxt = struct.unpack_from("<HHHHIII", elf, at)
                a = at + aux
                for _ in range(cnt):
                    name, anext = struct.unpack_from("<II", elf, a)
                    refs.add(name)
                    a += anext
                at += nxt
    return refs


def rename_links(elf: bytes) -> bytes:
    """Rewrites the DT_NEEDED and DT_SONAME names found in LIBS, in place."""
    out = bytearray(elf)
    entries, strtab = _dynamic(elf)
    refs = _string_refs(elf, entries)
    for tag, value in entries:
        if tag not in (DT_NEEDED, DT_SONAME):
            continue
        old = _string(elf, strtab + value)
        new = LIBS.get(old)
        if new is None or new == old:
            continue
        assert len(new) <= len(old), f"{new} is longer than {old}"
        # A linker may store a name only as the tail of a longer string; such a name would change too.
        shared = [r for r in refs if value < r <= value + len(old)]
        assert not shared, f"{old}: part of it is used by another name"
        at = strtab + value
        out[at:at + len(old)] = new.encode() + b"\0" * (len(old) - len(new))
    return bytes(out)


def links(elf: bytes) -> list[str]:
    entries, strtab = _dynamic(elf)
    return [_string(elf, strtab + v) for t, v in entries if t in (DT_NEEDED, DT_SONAME)]


def main(out: Path) -> None:
    files: dict[str, bytes] = {}
    for path, sha256 in PINNED:
        data = fetch(f"{REPO}/{path}")
        if hashlib.sha256(data).hexdigest() != sha256:
            raise SystemExit(f"{path}: SHA-256 does not match the pinned value")
        print(path)
        files.update(deb_files(data))

    jni = out / "jniLibs" / "arm64-v8a"
    jni.mkdir(parents=True, exist_ok=True)
    outputs = {"libnodejs.so": rename_links(files[PREFIX + "bin/node"])}
    for termux_name, apk_name in LIBS.items():
        outputs[apk_name] = rename_links(files[PREFIX + "lib/" + termux_name])
    for name, data in outputs.items():
        # Every library must now be linked by a name the APK carries (or one Android provides).
        for link in links(data):
            assert link in outputs or link in ("libc.so", "libm.so", "libdl.so", "liblog.so"), f"{name} → {link}"
        (jni / name).write_bytes(data)

    assets = out / "assets" / "node"
    assets.mkdir(parents=True, exist_ok=True)
    npm_root = PREFIX + "lib/node_modules/npm/"
    with zipfile.ZipFile(assets / "npm.zip", "w", zipfile.ZIP_DEFLATED) as z:
        for path, body in sorted(files.items()):
            if path.startswith(npm_root):
                z.writestr("npm/" + path[len(npm_root):], body)
    (assets / "VERSION").write_text(NODE_VERSION)


if __name__ == "__main__":
    try:
        main(Path(sys.argv[1]))
    except BaseException as e:
        # On GitHub Actions, say why as an annotation: job logs need a sign-in, annotations don't.
        if os.environ.get("GITHUB_ACTIONS"):
            print(f"::error title=node_bundle::{type(e).__name__}: {e}".replace("\n", " "), flush=True)
        raise
