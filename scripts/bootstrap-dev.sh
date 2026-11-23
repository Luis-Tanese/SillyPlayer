#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "$0")/.."
# Mint 22 / Ubuntu 24.04 development SDK, extracted locally without sudo.
mkdir -p .deps/debs .deps/root
packages=(patchelf nasm qt6-wayland libarchive-dev libx11-dev qt6-base-dev qt6-declarative-dev qt6-svg-dev qt6-image-formats-plugins qml6-module-qtquick-controls qml6-module-qtquick-dialogs qml6-module-qtquick-layouts qml6-module-qtquick-window qml6-module-qtqml-workerscript)
apt-get -s install "${packages[@]}" | awk '/^Inst / {print $2}' > .deps/packages.txt
(
  cd .deps/debs
  xargs -r apt-get download < ../packages.txt
  apt-get download "${packages[@]}" libmpv-dev libtag1-dev qml6-module-qtquick qml6-module-qtqml qml6-module-qtquick-templates
)
find .deps/debs -name '*.deb' -exec dpkg-deb -x '{}' .deps/root \;
python3 - <<'PY'
from pathlib import Path
root=Path('.deps/root/usr/lib/x86_64-linux-gnu')
# Ubuntu dev packages reference runtime libraries already installed on the host.
for p in [p for pattern in ('libQt6*', 'libX11.so*', 'libarchive.so*') for p in Path('/usr/lib/x86_64-linux-gnu').glob(pattern)]+[Path('/usr/lib/x86_64-linux-gnu/libmpv.so.2'),Path('/usr/lib/x86_64-linux-gnu/libtag.so.1')]:
    dest=root/p.name
    if p.exists() and not dest.exists():
        if dest.is_symlink(): dest.unlink()
        dest.symlink_to(p)
PY
printf 'Local SDK ready. Run ./scripts/build.sh\n'
