#!/usr/bin/env python3
"""Bundle all platform .sha256 files for the current release into one ZIP."""
from pathlib import Path
import re
import sys
import zipfile

root = Path(__file__).resolve().parents[1]
version = sys.argv[1] if len(sys.argv) > 1 else re.search(
    r"project\(SillyPlayer VERSION ([\d.]+)", (root / "CMakeLists.txt").read_text()
).group(1)

platforms = ("linux", "windows", "android")
sha_files = []
for platform in platforms:
    folder = root / "dist" / platform / version
    if folder.is_dir():
        sha_files.extend(sorted(folder.glob("*.sha256")))

if not sha_files:
    raise SystemExit(f"No .sha256 files found under dist/*/{version}")

combined = "".join(p.read_text() for p in sha_files)
out = root / "dist" / f"SillyPlayer-{version}-sha256.zip"
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
    z.writestr("SHA256SUMS.txt", combined)
    for p in sha_files:
        z.write(p, p.name)

print(f"Bundled {len(sha_files)} checksums into {out}")
print(combined, end="")
