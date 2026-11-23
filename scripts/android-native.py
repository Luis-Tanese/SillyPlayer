#!/usr/bin/env python3
"""Build the small audio/tag JNI dependencies; no FFmpeg executables or video codecs."""
import hashlib,json,os,shutil,subprocess,tarfile,urllib.request
from pathlib import Path
root=Path(__file__).resolve().parents[1];deps=root/'.deps';sdk=deps/'android-sdk';base=deps/'android-native';downloads=deps/'android-downloads'
base.mkdir(exist_ok=True);downloads.mkdir(exist_ok=True)
ndk=sdk/'ndk/28.1.13356709';tools=ndk/'toolchains/llvm/prebuilt/linux-x86_64/bin'
if not tools.exists():raise SystemExit('Run python3 scripts/android-bootstrap.py first')
PINNED={'lame-3.100': 'ddfe36cab873794038ae2c1210557ad34857a4b6bdc515785d1da9e175b1da1e', 'taglib-1.13.1': 'c8da2b10f1bfec2cd7dbfcd33f4a2338db0765d851a50583d410bacf055cfd0b', 'openssl-3.5.4': '967311f84955316969bdb1d8d4b983718ef42338639c621ec4c34fddef355e99', 'ffmpeg-6.1.5': '05fbe9db1f5452a3605bb1d9bf91da9d4bb08162891692e4f172b58c49109412'}
def source(name,url):
 target=base/'sources'/name
 if target.exists():return target
 file=downloads/(name+'.tar.gz');file.parent.mkdir(parents=True,exist_ok=True)
 if not file.exists():
  print('Downloading',name,flush=True)
  with urllib.request.urlopen(url,timeout=120) as response,file.with_suffix('.part').open('wb') as output:shutil.copyfileobj(response,output,1024*1024)
  file.with_suffix('.part').replace(file)
 if hashlib.sha256(file.read_bytes()).hexdigest()!=PINNED[name]:raise RuntimeError('Source checksum mismatch: '+name)
 temporary=base/'sources'/('unpack-'+name);temporary.mkdir(parents=True,exist_ok=True)
 with tarfile.open(file) as archive:archive.extractall(temporary,filter='data')
 children=list(temporary.iterdir());assert len(children)==1
 children[0].rename(target);temporary.rmdir()
 record=base/'sources.json';items=json.loads(record.read_text()) if record.exists() else {};items[name]={'url':url,'sha256':hashlib.sha256(file.read_bytes()).hexdigest()};record.write_text(json.dumps(items,indent=2)+'\n')
 return target
def run(args,cwd,env):
 print(' '.join(map(str,args[:5])),flush=True);subprocess.run(list(map(str,args)),cwd=cwd,env=env,check=True)
header=base/'include/json.hpp';header.parent.mkdir(parents=True,exist_ok=True)
if not header.exists():
 with urllib.request.urlopen('https://raw.githubusercontent.com/nlohmann/json/v3.12.0/single_include/nlohmann/json.hpp',timeout=90) as response:header.write_bytes(response.read())
if hashlib.sha256(header.read_bytes()).hexdigest()!='aaf127c04cb31c406e5b04a63f1ae89369fccde6d8fa7cdda1ed4f32dfc5de63':raise RuntimeError('JSON header checksum mismatch')
lame=source('lame-3.100','https://deb.debian.org/debian/pool/main/l/lame/lame_3.100.orig.tar.gz')
tag=source('taglib-1.13.1','https://codeload.github.com/taglib/taglib/tar.gz/refs/tags/v1.13.1')
ssl=source('openssl-3.5.4','https://github.com/openssl/openssl/releases/download/openssl-3.5.4/openssl-3.5.4.tar.gz')
ff=source('ffmpeg-6.1.5','https://ffmpeg.org/releases/ffmpeg-6.1.5.tar.xz')
patch=root/'mobile/android/native/ffmpeg-hostname.patch'
if 'SillyPlayer: verify both' not in (ff/'libavformat/tls_openssl.c').read_text():
 subprocess.run(['patch','-p1','--input',str(patch)],cwd=ff,check=True)
elif '"icy", "tls_verify"' not in (ff/'libavformat/aviobuf.c').read_text():
 source_file=ff/'libavformat/aviobuf.c';source_file.write_text(source_file.read_text().replace('"rw_timeout", "icy", NULL','"rw_timeout", "icy", "tls_verify", "ca_file", NULL'))
for abi,target,cpu,arch,sslarch in [('arm64-v8a','aarch64-linux-android','armv8-a','aarch64','android-arm64'),('armeabi-v7a','armv7a-linux-androideabi','armv7-a','arm','android-arm')]:
 prefix=base/abi;prefix.mkdir(exist_ok=True);work=base/'build'/abi;work.mkdir(parents=True,exist_ok=True)
 env=dict(os.environ,PATH=str(tools)+':'+os.environ['PATH'],ANDROID_NDK_ROOT=str(ndk),ANDROID_NDK_HOME=str(ndk),CC=str(tools/(target+'26-clang')),CXX=str(tools/(target+'26-clang++')),AR=str(tools/'llvm-ar'),RANLIB=str(tools/'llvm-ranlib'),STRIP=str(tools/'llvm-strip'),CFLAGS='-O2 -fPIC -D_FILE_OFFSET_BITS=64',LDFLAGS='-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384')
 if not (prefix/'lib/libmp3lame.a').exists():
  path=work/'lame';path.mkdir(exist_ok=True)
  run([lame/'configure','--host='+target,'--prefix='+str(prefix),'--disable-shared','--enable-static','--with-pic','--disable-frontend','--disable-analyzer-hooks','--disable-decoder','--disable-debug'],path,env);run(['make','-j3'],path,env);run(['make','install'],path,env)
 if not (prefix/'lib/libcrypto.a').exists():
  path=work/'openssl';path.mkdir(exist_ok=True)
  run(['perl',ssl/'Configure',sslarch,'-D__ANDROID_API__=26','--prefix='+str(prefix),'--libdir=lib','no-shared','no-tests','no-apps','no-docs','no-module','no-engine'],path,env);run(['make','-j3','build_libs'],path,env);run(['make','install_dev'],path,env)
 if not (prefix/'lib/libtag.a').exists():
  path=work/'taglib'
  run(['cmake','-S',tag,'-B',path,'-G','Ninja','-DCMAKE_TOOLCHAIN_FILE='+str(ndk/'build/cmake/android.toolchain.cmake'),'-DANDROID_ABI='+abi,'-DANDROID_PLATFORM=android-26','-DCMAKE_BUILD_TYPE=Release','-DCMAKE_INSTALL_PREFIX='+str(prefix),'-DBUILD_SHARED_LIBS=OFF','-DBUILD_TESTING=OFF','-DBUILD_EXAMPLES=OFF','-DWITH_ZLIB=OFF','-DCMAKE_POSITION_INDEPENDENT_CODE=ON'],work,env);run(['cmake','--build',path,'--parallel','3'],work,env);run(['cmake','--install',path],work,env)
 if not (prefix/'lib/libavformat.a').exists() or not (prefix/'.revision-3').exists():
  path=work/'ffmpeg';path.mkdir(exist_ok=True)
  args=[ff/'configure','--prefix='+str(prefix),'--target-os=android','--arch='+arch,'--cpu='+cpu,'--enable-cross-compile','--cc='+env['CC'],'--cxx='+env['CXX'],'--ar='+env['AR'],'--ranlib='+env['RANLIB'],'--strip='+env['STRIP'],'--sysroot='+str(tools.parent/'sysroot'),'--disable-autodetect','--disable-everything','--disable-programs','--disable-doc','--disable-debug','--disable-shared','--enable-static','--enable-pic','--enable-small','--disable-avdevice','--disable-avfilter','--disable-postproc','--disable-swscale','--enable-avformat','--enable-avcodec','--enable-swresample','--enable-libmp3lame','--enable-openssl','--enable-decoder=mp3,mp3float,aac,aac_fixed,aac_latm,flac,vorbis,opus,alac,pcm_s16le,pcm_s24le,pcm_s32le,pcm_u8,pcm_f32le,pcm_s16be','--enable-parser=mpegaudio,aac,aac_latm,flac,vorbis,opus','--enable-demuxer=mp3,aac,flac,ogg,mov,wav,matroska,hls','--enable-muxer=mp3,ipod,ogg,opus,flac,wav,matroska,adts','--enable-encoder=libmp3lame','--enable-protocol=file,http,https,tcp,tls,crypto','--enable-bsf=aac_adtstoasc','--extra-cflags=-O2 -fPIC -I'+str(prefix/'include'),'--extra-ldflags=-L'+str(prefix/'lib')+' -Wl,-z,max-page-size=16384','--extra-libs=-ldl']
  run(args,path,env);run(['make','-j3'],path,env);run(['make','install'],path,env);(prefix/'.revision-3').touch()
 print('Native dependencies ready:',abi,flush=True)
