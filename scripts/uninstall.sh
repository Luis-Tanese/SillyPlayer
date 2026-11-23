#!/usr/bin/env bash
set -euo pipefail
install_root="${SILLYPLAYER_INSTALL_ROOT:-$HOME/.local}"
target="$install_root/opt/sillyplayer"
data="${XDG_DATA_HOME:-$HOME/.local/share}/SillyPlayer/SillyPlayer"
if [[ -f "$data/instance.lock" ]]; then
  read -r pid < "$data/instance.lock" || true
  if [[ "${pid:-}" =~ ^[0-9]+$ ]] && kill -0 "$pid" 2>/dev/null; then
    printf 'Exit SillyPlayer before uninstalling.\n' >&2; exit 1
  fi
fi
rm -f -- "$install_root/bin/sillyplayer" "$install_root/share/applications/sillyplayer.desktop" "$install_root/share/icons/hicolor/512x512/apps/sillyplayer.png"
rm -f -- "$install_root/share/icons/hicolor/256x256/apps/sillyplayer.png"
rm -rf -- "$target"
printf 'Application removed. Your library data and media files have been kept.\n'
