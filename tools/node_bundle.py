"""Builds the Node.js bundle for the APK from Termux's arm64 packages (SPIKE).

    python tools/node_bundle.py <out-dir>

Writes:
  <out-dir>/jniLibs/arm64-v8a/libnodejs.so   the node executable
  <out-dir>/jniLibs/arm64-v8a/libnode_*.so   the libraries it needs, renamed
  <out-dir>/assets/node/npm.zip               npm (JavaScript only)
  <out-dir>/assets/node/VERSION               the Node.js version

Android only extracts native files named lib*.so from the APK, so every library is renamed and the
names they link to (DT_NEEDED, DT_SONAME) are rewritten to match; Termux's RUNPATH is removed (the app
sets LD_LIBRARY_PATH to the native library folder instead). Needs: pip install lief.
"""
import hashlib
import io
import sys
import tarfile
import urllib.request
import zipfile
from pathlib import Path

import lief

REPO = "https://packages.termux.dev/apt/termux-main"
PACKAGES = ["nodejs-lts", "c-ares", "libc++", "libicu", "libsqlite", "openssl", "zlib", "npm"]
PREFIX = "data/data/com.termux/files/usr/"

# Termux file (under usr/lib) → name in the APK, and the names other files link to it by.
LIBS = {
    "libz.so.1": "libnode_z.so",
    "libcares.so": "libnode_cares.so",
    "libsqlite3.so": "libnode_sqlite3.so",
    "libcrypto.so.3": "libnode_crypto.so",
    "libssl.so.3": "libnode_ssl.so",
    "libicui18n.so.79": "libnode_icui18n.so",
    "libicuuc.so.79": "libnode_icuuc.so",
    "libicudata.so.79": "libnode_icudata.so",
    "libc++_shared.so": "libnode_cxx.so",
}


def fetch(url: str) -> bytes:
    with urllib.request.urlopen(url, timeout=120) as r:
        return r.read()


def index() -> dict[str, dict[str, str]]:
    text = fetch(f"{REPO}/dists/stable/main/binary-aarch64/Packages").decode("utf-8")
    packages = {}
    for block in text.split("\n\n"):
        fields = {}
        for line in block.splitlines():
            if ":" in line and not line.startswith(" "):
                key, value = line.split(":", 1)
                fields[key] = value.strip()
        if "Package" in fields:
            packages[fields["Package"]] = fields
    return packages


def deb_files(data: bytes) -> dict[str, bytes]:
    """The files of a .deb (an ar archive holding data.tar.*), by path."""
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
                # Library names like libz.so.1 are symlinks: resolve them to the real file.
                for path, target in links.items():
                    real = str(Path(path).parent / target).replace("\\", "/")
                    if real in files:
                        files[path] = files[real]
    return files


def patch(data: bytes, rename_self: str | None) -> bytes:
    elf = lief.ELF.parse(list(data))
    for entry in list(elf.dynamic_entries):
        if entry.tag == lief.ELF.DynamicEntry.TAG.NEEDED and entry.name in LIBS:
            entry.name = LIBS[entry.name]
        elif entry.tag in (lief.ELF.DynamicEntry.TAG.RUNPATH, lief.ELF.DynamicEntry.TAG.RPATH):
            elf.remove(entry)
    if rename_self is not None:
        soname = elf.get(lief.ELF.DynamicEntry.TAG.SONAME)
        if soname is not None:
            soname.name = rename_self
    builder = lief.ELF.Builder(elf)
    builder.build()
    return bytes(builder.get_build())


def main(out: Path) -> None:
    packages = index()
    files: dict[str, bytes] = {}
    for name in PACKAGES:
        info = packages[name]
        data = fetch(f"{REPO}/{info['Filename']}")
        if hashlib.sha256(data).hexdigest() != info["SHA256"]:
            raise SystemExit(f"{name}: checksum mismatch")
        print(f"{name} {info['Version']}")
        files.update(deb_files(data))

    jni = out / "jniLibs" / "arm64-v8a"
    jni.mkdir(parents=True, exist_ok=True)
    (jni / "libnodejs.so").write_bytes(patch(files[PREFIX + "bin/node"], None))
    for termux_name, apk_name in LIBS.items():
        (jni / apk_name).write_bytes(patch(files[PREFIX + "lib/" + termux_name], apk_name))

    assets = out / "assets" / "node"
    assets.mkdir(parents=True, exist_ok=True)
    npm_root = PREFIX + "lib/node_modules/npm/"
    with zipfile.ZipFile(assets / "npm.zip", "w", zipfile.ZIP_DEFLATED) as z:
        for path, body in sorted(files.items()):
            if path.startswith(npm_root):
                z.writestr("npm/" + path[len(npm_root):], body)
    (assets / "VERSION").write_text(packages["nodejs-lts"]["Version"])


if __name__ == "__main__":
    main(Path(sys.argv[1]))
