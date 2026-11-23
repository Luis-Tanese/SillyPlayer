#!/usr/bin/env python3
"""Bundle the build and its ELF/QML dependencies, then create a ZIP."""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import zipfile
from extractor_bundle import DENO, YT_DLP, install_extractor

root = Path(__file__).resolve().parent.parent
patchelf = shutil.which("patchelf") or str(root / ".deps/root/usr/bin/patchelf")
if not Path(patchelf).is_file():
    raise SystemExit("Release building requires patchelf; run scripts/bootstrap-dev.sh or install patchelf")
version = re.search(r"project\(SillyPlayer VERSION ([\d.]+)", (root / "CMakeLists.txt").read_text()).group(1)
release_dir = root / "dist/linux" / version
release_dir.mkdir(parents=True, exist_ok=True)
output = root / "build/release/linux" / f"SillyPlayer-{version}-linux-x86_64"
if output.exists():
    shutil.rmtree(output)
for directory in ("bin", "lib", "plugins", "qml", "assets", "licenses"):
    (output / directory).mkdir(parents=True, exist_ok=True)
sdk = root / ".deps/root/usr"
prefix = sdk if sdk.exists() else Path("/usr")
qtlib = prefix / "lib/x86_64-linux-gnu"
media = root / ".deps/media"
env = dict(os.environ, LD_LIBRARY_PATH=str(media / "lib")+":"+str(qtlib))
copied = set()
origins = {}
# OS C library/loader and host graphics drivers remain part of the documented baseline.
system = re.compile(r"^(ld-linux|lib(c|m|pthread|dl|rt|resolv|util|nss_.*|anl)\.so|lib(GL|EGL|GLX|GLdispatch|OpenGL|drm).*)")

def elf(path):
    try:
        with open(path, "rb") as f:
            return f.read(4) == b"\x7fELF"
    except OSError:
        return False

def dependencies(path):
    result = subprocess.run(["ldd", str(path)], env=env, text=True, capture_output=True)
    if "not found" in result.stdout:
        raise SystemExit(f"Missing dependency for {path}:\n{result.stdout}")
    for line in result.stdout.splitlines():
        match = re.search(r"\s*(\S+) => (/\S+)", line)
        if not match:
            continue
        name, source = match.groups()
        if name in copied or system.match(name):
            continue
        copied.add(name)
        target = output / "lib" / name
        shutil.copy2(Path(source).resolve(), target)
        origins[name] = str(Path(source).resolve())
        dependencies(target)

def copy_binary(source, target):
    source = Path(source)
    if not source.exists():
        raise SystemExit(f"Missing {source}; build or run scripts/fetch-tools.py first")
    shutil.copy2(source, target)
    target.chmod(0o755)
    dependencies(source)

copy_binary(root / "build/sillyplayer", output / "bin/sillyplayer")
for name in ("ffmpeg", "ffprobe"):
    source = str(media / "bin" / name) if (media / "bin" / name).exists() else shutil.which(name)
    if not source:
        raise SystemExit(f"Install the release-builder tool {name}")
    copy_binary(source, output / "bin" / name)

qmlroot = qtlib / "qt6/qml"
# Root files are copied separately, so unrelated QtQuick submodules/styles are excluded.
modules = ["QtQml", "QtQml/Models", "QtQml/WorkerScript", "QtQuick", "QtQuick/Window", "QtQuick/Layouts", "QtQuick/Templates", "QtQuick/Controls", "QtQuick/Controls/Basic", "QtQuick/Controls/Basic/impl", "QtQuick/Controls/impl"]
for module in modules:
    source = qmlroot / module
    dest = output / "qml" / module
    dest.mkdir(parents=True, exist_ok=True)
    for f in source.glob("*"):
        if f.is_file() and f.suffix not in (".qmltypes", ".a", ".prl"):
            shutil.copy2(f, dest / f.name)
            if elf(f):
                dependencies(f)
for group in ("platforms", "imageformats", "sqldrivers", "xcbglintegrations", "tls", "wayland-decoration-client", "wayland-graphics-integration-client", "wayland-shell-integration"):
    candidates = [qtlib / "qt6/plugins" / group, Path("/usr/lib/x86_64-linux-gnu/qt6/plugins") / group]
    dest = output / "plugins" / group
    dest.mkdir(parents=True, exist_ok=True)
    for folder in candidates:
        for f in folder.glob("*.so"):
            if (dest / f.name).exists():
                continue
            if group == "platforms" and f.name not in ("libqxcb.so", "libqoffscreen.so", "libqwayland-egl.so", "libqwayland-generic.so"):
                continue
            if group == "sqldrivers" and f.name != "libqsqlite.so":
                continue
            if group == "imageformats" and f.name not in ("libqgif.so", "libqico.so", "libqjpeg.so", "libqsvg.so", "libqwebp.so"):
                continue
            shutil.copy2(f, dest / f.name)
            dependencies(f)
if not (output / "plugins/platforms/libqwayland-generic.so").exists() or not (output / "plugins/wayland-shell-integration/libxdg-shell.so").exists():
    raise SystemExit("Native Wayland plugins are required; install Qt Wayland on the builder")
if not (output / "plugins/imageformats/libqwebp.so").exists():
    raise SystemExit("Qt WebP support is required for YouTube thumbnails; run scripts/bootstrap-dev.sh")
for f in output.rglob("*"):
    if f.is_file() and elf(f):
        subprocess.run(["strip", "--strip-unneeded", str(f)], check=True)
        # Qt's original plugin RUNPATHs point at the distribution's lib layout.
        # Relocate every ELF, including plugins and helpers, to our flat lib/.
        relative_lib = os.path.relpath(output / "lib", f.parent)
        rpath = "$ORIGIN" if relative_lib == "." else "$ORIGIN/" + relative_lib
        subprocess.run([patchelf, "--force-rpath", "--set-rpath", rpath, str(f)], check=True)
install_extractor("linux", output / "bin")
# Run the packaged helper against its relocated libraries, never host FFmpeg.
packaged_env = dict(os.environ, LD_LIBRARY_PATH=str(output / "lib"))
capabilities = subprocess.run([str(output / "bin/ffmpeg"), "-hide_banner", "-bsfs"], env=packaged_env, text=True, capture_output=True, check=True).stdout.split()
for required in ("setts", "mjpeg2jpeg", "aac_adtstoasc", "opus_metadata"):
    if required not in capabilities:
        raise SystemExit(f"Bundled FFmpeg is missing required bitstream filter: {required}")
muxers = subprocess.run([str(output / "bin/ffmpeg"), "-hide_banner", "-muxers"], env=packaged_env, text=True, capture_output=True, check=True).stdout.split()
for required in ("ipod", "mp3", "ogg"):
    if required not in muxers: raise SystemExit(f"Bundled FFmpeg is missing required audio muxer: {required}")
demuxers = subprocess.run([str(output / "bin/ffmpeg"), "-hide_banner", "-demuxers"], env=packaged_env, text=True, capture_output=True, check=True).stdout.split()
if "ffmetadata" not in demuxers:
    raise SystemExit("Bundled FFmpeg is missing chapter metadata demuxing (ffmetadata)")
(output / "sillyplayer").write_text('''#!/bin/sh
set -eu
APP_DIR=$(CDPATH= cd -- "$(dirname -- "$(readlink -f -- "$0")")" && pwd)
export LD_LIBRARY_PATH="$APP_DIR/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
export QT_PLUGIN_PATH="$APP_DIR/plugins"
export QML2_IMPORT_PATH="$APP_DIR/qml"
export QML_IMPORT_PATH="$APP_DIR/qml"
export QT_QUICK_CONTROLS_STYLE=Basic
exec "$APP_DIR/bin/sillyplayer" "$@"
''')
(output / "sillyplayer").chmod(0o755)
(output / "bin/qt.conf").write_text("[Paths]\nPrefix=..\nLibraries=lib\nPlugins=plugins\nQml2Imports=qml\n")
for name in ("install.sh", "uninstall.sh"):
    shutil.copy2(root / "scripts" / name, output / name)
    (output / name).chmod(0o755)
shutil.copy2(root / "assets/sillyplayer.png", output / "assets/sillyplayer.png")
shutil.copy2(root / "assets/icons/LICENSE", output / "licenses/Lucide.txt")
qr_header = (root / "src/third_party/qrcodegen.hpp").read_text()
(output / "licenses/QR-Code-generator.txt").write_text(qr_header[:qr_header.index("*/")+2])

(output / "README.txt").write_text("SillyPlayer " + version + "\n\nLinux x86-64; Ubuntu 24.04 / Linux Mint 22 baseline. Native Wayland and X11.\nRun ./sillyplayer, or ./install.sh to install/update for your user.\nUpdates preserve library/settings; uninstall.sh removes the app, not music.\nLocal music works offline. Network media depends on provider availability.\nUse Settings for watched folders, downloads, appearance, backup and phone sync.\nThird-party notices and source/build references: licenses/.\n")
# Include Debian/Ubuntu copyright files for bundled libraries and tools.
package_paths = {}
for listing in Path("/var/lib/dpkg/info").glob("*.list"):
    package = listing.name.removesuffix(".list").split(":")[0]
    for line in listing.read_text(errors="replace").splitlines():
        package_paths[line] = package
for name, source in origins.items():
    package = package_paths.get(source) or package_paths.get("/usr/lib/x86_64-linux-gnu/" + name)
    if not package:
        continue
    copyright_file = Path("/usr/share/doc") / package / "copyright"
    if copyright_file.exists():
        shutil.copy2(copyright_file, output / "licenses" / (package + ".txt"))
for copyright_file in (sdk / "share/doc").glob("*/copyright"):
    shutil.copy2(copyright_file, output / "licenses" / (copyright_file.parent.name + ".txt"))
for name in ("Deno.txt", "yt-dlp.txt"):
    shutil.copy2(root / "packaging/licenses" / name, output / "licenses" / name)

manifest = {"application": version, "baseline": "Linux Mint 22 / Ubuntu 24.04, x86-64, native Wayland and X11", "yt-dlp": YT_DLP, "deno": DENO, "libraries": sorted(copied)}
versions=subprocess.run(["dpkg-query","-W","-f=${Package}\t${Version}\n"],text=True,capture_output=True,check=True)
version_map=dict(line.split('\t',1) for line in versions.stdout.splitlines() if '\t' in line)
used_packages=set()
for name,source in origins.items():
    if str(media) in source:continue
    package=package_paths.get(source) or package_paths.get("/usr/lib/x86_64-linux-gnu/"+name)
    if package:used_packages.add(package)
manifest["system_library_packages"]={p:version_map[p] for p in sorted(used_packages) if p in version_map}
manifest["local_sdk_packages"]=[p.name for p in sorted((root/".deps/debs").glob('*.deb'))]
if (media / "bin/ffmpeg").exists():
    manifest["ffmpeg"]="6.1.5 (SillyPlayer selected-codec build)"
    shutil.copy2(root / "scripts/build-media.sh",output / "licenses/FFmpeg-build.sh")
    shutil.copy2(root / ".deps/ffmpeg-6.1.5/COPYING.LGPLv2.1",output / "licenses/FFmpeg.txt")
    (output / "licenses/FFmpeg-source.txt").write_text("Source: https://ffmpeg.org/releases/ffmpeg-6.1.5.tar.xz\nSHA256: 05fbe9db1f5452a3605bb1d9bf91da9d4bb08162891692e4f172b58c49109412\nExact configure/build recipe: FFmpeg-build.sh\n")
(output / "DEPENDENCIES.json").write_text(json.dumps(manifest, indent=2) + "\n")
(output / "licenses/NOTICE.txt").write_text("Qt, libmpv, FFmpeg, TagLib and other runtime libraries retain their upstream licenses. Sources: https://download.qt.io/archive/qt/6.4/6.4.2/submodules/ ; https://github.com/mpv-player/mpv ; https://ffmpeg.org/releases/ ; https://taglib.org/releases/ ; https://libarchive.org/downloads/ . Runtime libraries remain dynamically linked.\n")
# One compressed notice file avoids redundant license texts and ZIP entry overhead.
notices = []
seen_notices = set()
for notice in sorted((output / "licenses").glob("*.txt")):
    content = notice.read_bytes()
    digest = hashlib.sha256(content).hexdigest()
    if digest not in seen_notices:
        notices.append("\n===== " + notice.name + " =====\n" + content.decode("utf-8", errors="replace"))
        seen_notices.add(digest)
    notice.unlink()
(output / "licenses/NOTICE.txt").write_text("".join(notices))
archive = release_dir / (output.name + ".zip")
with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
    for f in sorted(output.rglob("*")):
        if f.is_file():
            z.write(f, f.relative_to(output.parent))
digest = hashlib.sha256(archive.read_bytes()).hexdigest()
Path(str(archive) + ".sha256").write_text(digest + "  " + archive.name + "\n")
print(f"ZIP: {archive.stat().st_size / 1048576:.1f} MiB")
print(f"Installed: {sum(f.stat().st_size for f in output.rglob('*') if f.is_file()) / 1048576:.1f} MiB")
print(archive)
