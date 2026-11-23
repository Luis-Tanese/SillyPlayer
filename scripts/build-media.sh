#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "$0")/.."
project_dir="$PWD"
sdk="$project_dir/.deps/root/usr"
mkdir -p .deps/debs .deps/media
(
  cd .deps/debs
  apt-get download libmp3lame-dev libdav1d-dev libxml2-dev nasm
)
for package in libmp3lame-dev libdav1d-dev libxml2-dev nasm; do
  for deb in .deps/debs/"$package"_*.deb; do dpkg-deb -x "$deb" .deps/root; done
done
python3 - <<'PY'
from pathlib import Path
root=Path('.deps/root/usr/lib/x86_64-linux-gnu')
for pattern in ('libmp3lame*','libdav1d*','libxml2*'):
    for source in Path('/usr/lib/x86_64-linux-gnu').glob(pattern):
        dest=root/source.name
        if not dest.exists():
            if dest.is_symlink():dest.unlink()
            dest.symlink_to(source)
PY
archive=.deps/ffmpeg-6.1.5.tar.xz
if [[ ! -f "$archive" ]]; then curl -fL --retry 2 -o "$archive" https://ffmpeg.org/releases/ffmpeg-6.1.5.tar.xz; fi
printf '%s  %s\n' '05fbe9db1f5452a3605bb1d9bf91da9d4bb08162891692e4f172b58c49109412' "$archive" | sha256sum --check --status
[[ -d .deps/ffmpeg-6.1.5 ]] || tar -xJf "$archive" -C .deps
cd .deps/ffmpeg-6.1.5
export PATH="$sdk/bin:$PATH"
export PKG_CONFIG_PATH="$sdk/lib/x86_64-linux-gnu/pkgconfig${PKG_CONFIG_PATH:+:$PKG_CONFIG_PATH}"
./configure --prefix="$project_dir/.deps/media" \
  --extra-cflags="-I$sdk/include -I$sdk/include/libxml2" \
  --extra-ldflags="-L$sdk/lib/x86_64-linux-gnu" \
  --disable-static --enable-shared --disable-debug --disable-doc \
  --disable-autodetect --disable-everything --enable-pic --enable-network \
  --enable-openssl --enable-libmp3lame --enable-libdav1d --enable-libxml2 --enable-zlib \
  --enable-decoder=aac,aac_fixed,alac,mp3,mp3float,flac,vorbis,opus,pcm_s16le,pcm_s24le,pcm_s32le,pcm_f32le,pcm_f64le,pcm_u8,pcm_s16be,h264,hevc,vp8,vp9,libdav1d,mjpeg,png,webp,bmp,gif \
  --enable-encoder=libmp3lame,aac,flac,pcm_s16le,pcm_f32le,png,mjpeg \
  --enable-demuxer=ffmetadata,aac,mp3,flac,ogg,mov,wav,matroska,hls,dash,mpegts,image2,image2pipe \
  --enable-muxer=ipod,mp3,mp4,mov,flac,ogg,wav,pcm_f32le,matroska,webm,image2,image2pipe,adts \
  --enable-parser=aac,aac_latm,flac,h264,hevc,mpegaudio,opus,png,vorbis,vp8,vp9,av1 \
  --enable-bsf=setts,mjpeg2jpeg,aac_adtstoasc,extract_extradata,h264_mp4toannexb,hevc_mp4toannexb,vp9_superframe,opus_metadata \
  --enable-protocol=file,pipe,http,https,tcp,tls,crypto,data,httpproxy \
  --enable-filter=aresample,anull,aformat,atempo,alimiter,apad,atrim,asetpts,volume,equalizer,abuffer,abuffersink,buffer,buffersink,scale,format,copy,null,trim,setpts \
  > "$project_dir/.deps/media-configure.log" 2>&1
make -j "${BUILD_JOBS:-4}" > "$project_dir/.deps/media-build.log" 2>&1
make install >> "$project_dir/.deps/media-build.log" 2>&1
printf 'Minimal FFmpeg built in .deps/media; run tests with SILLYPLAYER_MINIMAL_MEDIA=1.\n'
