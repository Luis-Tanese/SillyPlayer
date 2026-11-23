"""Stage pinned desktop helpers without runtime archive extraction."""
import hashlib
from pathlib import Path
import urllib.request
import zipfile

YT_DLP = "2026.10.26"
DENO = "2.9.8"
ARCHIVES = {
    "linux": (
        "f34053415136e55073dd64ba5326d161ecc23ce0e98c081d8752867b37809450",
        "deno-x86_64-unknown-linux-gnu.zip",
        "e890975bc2549807bbcc0b584ffafda72cc7c93349dd99c80483dd784f6c4609",
    ),
    "win": (
        "fd51b212cf8380c7a52ee08c0d6be7f3c59b2f2c832eaf7b45acfae6eab57419",
        "deno-x86_64-pc-windows-msvc.zip",
        "cba84b26e0df5ae7f74fc66b86a95b866872a86292ebd84fa1e959927edd93bf",
    ),
}


def unpack(url, digest, destination):
    cache = Path(__file__).resolve().parents[1] / ".deps/tools"
    cache.mkdir(parents=True, exist_ok=True)
    archive = cache / url.rsplit("/", 1)[1]
    if not archive.exists() or hashlib.sha256(archive.read_bytes()).hexdigest() != digest:
        temporary = archive.with_suffix(".part")
        urllib.request.urlretrieve(url, temporary)
        if hashlib.sha256(temporary.read_bytes()).hexdigest() != digest:
            raise ValueError(f"Checksum mismatch for {archive.name}")
        temporary.replace(archive)
    with zipfile.ZipFile(archive) as bundle:
        bundle.extractall(destination)


def install_extractor(platform, destination):
    yt_digest, deno_name, deno_digest = ARCHIVES[platform]
    unpack(f"https://github.com/yt-dlp/yt-dlp/releases/download/{YT_DLP}/yt-dlp_{platform}.zip",
           yt_digest, destination)
    unpack(f"https://github.com/denoland/deno/releases/download/v{DENO}/{deno_name}",
           deno_digest, destination)
    if platform == "linux":
        executable = destination / "yt-dlp"
        (destination / "yt-dlp_linux").replace(executable)
        executable.chmod(0o755)
        (destination / "deno").chmod(0o755)
