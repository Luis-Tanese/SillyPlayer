"""Stage pinned desktop helpers without runtime archive extraction."""
import hashlib
from pathlib import Path
import shutil
import urllib.request
import zipfile

YT_DLP = "2026.11.23"
QUICKJS = "0.18.0"
ARCHIVES = {
    "linux": (
        "cc4a3aa98fb438a1dac04a6fb006fe57acbeed00fc12db9ec84e3036dbbe6ccb",
        "qjs-linux-x86_64",
        "85ded23b9fabe8c1644fc40a8d2bbdd71157affcbd807359c618cc717ee24d7d",
    ),
    "win": (
        "824860984b96ca01de09f98f7bfd74ed7bbdce72a3340b51af8b9dc5ee037a5f",
        "qjs-windows-x86_64.exe",
        "ff03398d74ea6e6bd3a8e58058f7003225abcac229d7d57775b7aaae812a49c7",
    ),
}


def fetch(url, digest):
    cache = Path(__file__).resolve().parents[1] / ".deps/tools"
    cache.mkdir(parents=True, exist_ok=True)
    target = cache / url.rsplit("/", 1)[1]
    if not target.exists() or hashlib.sha256(target.read_bytes()).hexdigest() != digest:
        temporary = target.with_suffix(target.suffix + ".part")
        urllib.request.urlretrieve(url, temporary)
        if hashlib.sha256(temporary.read_bytes()).hexdigest() != digest:
            raise ValueError(f"Checksum mismatch for {target.name}")
        temporary.replace(target)
    return target


def install_extractor(platform, destination):
    yt_digest, qjs_name, qjs_digest = ARCHIVES[platform]
    yt_archive = fetch(
        f"https://github.com/yt-dlp/yt-dlp/releases/download/{YT_DLP}/yt-dlp_{platform}.zip",
        yt_digest,
    )
    with zipfile.ZipFile(yt_archive) as bundle:
        bundle.extractall(destination)
    qjs_source = fetch(
        f"https://github.com/quickjs-ng/quickjs/releases/download/v{QUICKJS}/{qjs_name}",
        qjs_digest,
    )
    qjs_dest = destination / ("qjs.exe" if platform == "win" else "qjs")
    shutil.copy2(qjs_source, qjs_dest)
    if platform == "linux":
        executable = destination / "yt-dlp"
        (destination / "yt-dlp_linux").replace(executable)
        executable.chmod(0o755)
        qjs_dest.chmod(0o755)
