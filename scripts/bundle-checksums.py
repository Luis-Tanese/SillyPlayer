#!/usr/bin/env python3
"""Bundle all platform .sha256 files for a release into dist/v<version>/SHAs/."""
from pathlib import Path
import re
import sys
import zipfile

root = Path(__file__).resolve().parents[1]
version = sys.argv[1] if len(sys.argv) > 1 else re.search(
    r"project\(SillyPlayer VERSION ([\d.]+)", (root / "CMakeLists.txt").read_text()
).group(1)

sha_dir = root / "dist" / f"v{version}" / "SHAs"
sha_files = sorted(sha_dir.glob("*.sha256")) if sha_dir.is_dir() else []

if not sha_files:
    raise SystemExit(f"No .sha256 files found under {sha_dir}")

combined = "".join(p.read_text() for p in sha_files)
out = sha_dir / f"SillyPlayer-{version}-sha256.zip"
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
    z.writestr("SHA256SUMS.txt", combined)
    for p in sha_files:
        z.write(p, p.name)

print(f"Bundled {len(sha_files)} checksums into {out}")
print(combined, end="")
