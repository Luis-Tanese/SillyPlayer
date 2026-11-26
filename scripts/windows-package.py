#!/usr/bin/env python3
"""Stage only runtime dependencies and build an NSIS installer; no Windows ZIP."""
import hashlib,json,os,re,shutil,subprocess
from pathlib import Path
from extractor_bundle import install_extractor
root=Path(__file__).resolve().parents[1];sdk=root/'.deps/windows';qt=sdk/'qt/6.4.2/mingw_64'
version=re.search(r'project\(SillyPlayer VERSION ([\d.]+)',(root/'CMakeLists.txt').read_text()).group(1)
stage=root/'build/release/windows/app';release=root/'dist'/f'v{version}'/'windows';sha_dir=root/'dist'/f'v{version}'/'SHAs'
if stage.exists():shutil.rmtree(stage)
stage.mkdir(parents=True);release.mkdir(parents=True,exist_ok=True);sha_dir.mkdir(parents=True,exist_ok=True)
search=[sdk/'native/bin',qt/'bin',sdk/'msys/mingw64/bin',sdk/'mpv',Path('/usr/lib/gcc/x86_64-w64-mingw32/13-posix'),Path('/usr/x86_64-w64-mingw32/lib')]
available={p.name.lower():p for directory in reversed(search) for p in directory.glob('*.dll')}
system=set('ADVAPI32 AUTHZ AVICAP32 AVRT BCRYPT BCRYPTPRIMITIVES CFGMGR32 COMCTL32 COMDLG32 CRYPT32 CRYPTBASE CREDUI D2D1 D3D9 D3D11 DBGHELP DNSAPI DWRITE DWMAPI DXGI GDI32 GLU32 IMM32 IPHLPAPI KERNEL32 MPR MSIMG32 MSVCRT MSVCP_WIN MSWSOCK NCRYPT NETAPI32 NORMALIZ NTDLL OLE32 OLEACC OLEAUT32 OPENGL32 PATHCCH POWRPROF PROPSYS PSAPI SECUR32 SETUPAPI SHCORE SHELL32 SHLWAPI UCRTBASE URLMON USER32 USERENV UXTHEME VERSION WIN32U WINHTTP WININET WINMM WINSPOOL WINTRUST XMLLITE WLDAP32 CRYPTUI D3DCOMPILER_47 MF MFPLAT MFREADWRITE RTWORKQ WS2_32 WTSAPI32'.lower().split())
copied={};visited=set()
def deps(path):
 path=Path(path)
 if path in visited:return
 visited.add(path)
 text=subprocess.run(['x86_64-w64-mingw32-objdump','-p',str(path)],capture_output=True,text=True,check=True).stdout
 for name in re.findall(r'DLL Name: (\S+)',text):
  low=name.lower()
  if low.startswith(('api-ms-','ext-ms-')) or low.removesuffix('.dll') in system:continue
  if low in copied:continue
  source=available.get(low)
  if source is None:raise SystemExit(f'Missing Windows dependency {name} for {path.name}')
  dest=stage/source.name;shutil.copy2(source,dest);copied[low]=str(source)
  deps(dest)
def copy(source,dest):
 source=Path(source);dest=Path(dest);dest.parent.mkdir(parents=True,exist_ok=True)
 shutil.copy2(source,dest)
 if source.suffix.lower() in ('.dll','.exe'):deps(dest)
copy(root/'build/windows/sillyplayer.exe',stage/'SillyPlayer.exe')
for name in ('ffmpeg.exe','ffprobe.exe'):copy(sdk/'native/bin'/name,stage/name)
modules=['QtQml','QtQml/Models','QtQml/WorkerScript','QtQuick','QtQuick/Window','QtQuick/Layouts','QtQuick/Templates','QtQuick/Controls','QtQuick/Controls/Basic','QtQuick/Controls/Basic/impl','QtQuick/Controls/impl']
for module in modules:
 for file in (qt/'qml'/module).glob('*'):
  if file.is_file() and file.suffix in ('.qml','.js','.dll','') and file.name not in ('plugins.qmltypes',):
   copy(file,stage/'qml'/module/file.name)
for group,names in {'platforms':['qwindows.dll'],'imageformats':['qjpeg.dll','qsvg.dll','qwebp.dll','qgif.dll','qico.dll'],'sqldrivers':['qsqlite.dll'],'tls':['qschannelbackend.dll','qcertonlybackend.dll'],'styles':['qwindowsvistastyle.dll']}.items():
 for name in names:
  source=qt/'plugins'/group/name
  if not source.exists():raise SystemExit('Missing Qt plugin '+str(source))
  copy(source,stage/'plugins'/group/name)
(stage/'qt.conf').write_text('[Paths]\nPrefix=.\nPlugins=plugins\nQml2Imports=qml\n')
(stage/'README.txt').write_text('SillyPlayer '+version+'\n\nWindows 10/11 x64. Use the Start menu or desktop shortcut.\nUpdates preserve your library and settings; music stays in its chosen folders.\nExit through the tray menu before updating. The uninstaller does not remove your library or music.\nThis build is not code-signed. Third-party notices: NOTICE.txt.\n')
notice=['SillyPlayer uses Qt 6.4.2, libmpv 0.36.0 (audio-only minimal build), FFmpeg 6.1.5, TagLib, libarchive, OpenSSL, zlib, QuickJS-NG, yt-dlp and Lucide.\nLibraries remain dynamically replaceable.\nQt source: https://download.qt.io/archive/qt/6.4/6.4.2/submodules/\nlibmpv source: https://github.com/mpv-player/mpv/archive/refs/tags/v0.36.0.tar.gz\nFFmpeg 6.1.5: https://ffmpeg.org/releases/ffmpeg-6.1.5.tar.xz\nTagLib 1.13.1: https://taglib.org/releases/taglib-1.13.1.tar.gz\nlibarchive 3.8.2: https://www.libarchive.org/downloads/libarchive-3.8.2.tar.xz\nOpenSSL: https://openssl-library.org/source/\nQuickJS-NG: https://github.com/quickjs-ng/quickjs\n']
for name,path in [('LGPLv3','/usr/share/common-licenses/LGPL-3'),('GPLv3','/usr/share/common-licenses/GPL-3'),('LGPLv2.1','/usr/share/common-licenses/LGPL-2.1'),('GPLv2','/usr/share/common-licenses/GPL-2'),('Lucide',root/'assets/icons/LICENSE'),('TagLib',sdk/'src/taglib-1.13.1/COPYING.LGPL'),('libarchive',sdk/'src/libarchive-3.8.2/COPYING'),('OpenSSL',sdk/'msys/mingw64/share/licenses/openssl/LICENSE.txt'),('zlib',sdk/'src/zlib-1.3.1/README'),('LAME',sdk/'src/lame-3.100/COPYING'),('WebP',sdk/'src/libwebp-1.6.0/COPYING'),('QuickJS-NG',root/'packaging/licenses/QuickJS.txt'),('yt-dlp',root/'packaging/licenses/yt-dlp.txt')]:
 p=Path(path)
 if p.exists():notice.append('\n===== '+name+' =====\n'+p.read_text(errors='replace'))
qr=(root/'src/third_party/qrcodegen.hpp').read_text();notice.append(qr[:qr.index('*/')+2])
(stage/'NOTICE.txt').write_text('\n'.join(notice))
for file in stage.rglob('*'):
 if file.suffix.lower() in ('.dll','.exe'):
  subprocess.run(['x86_64-w64-mingw32-strip','--strip-unneeded',str(file)],check=True)
install_extractor('win',stage)
files=sorted(p for p in stage.rglob('*') if p.is_file())
remove=root/'build/windows/uninstall-files.nsh'
lines=['Delete "$INSTDIR\\app\\'+str(p.relative_to(stage)).replace('/','\\')+'"' for p in files]
for directory in sorted((p for p in stage.rglob('*') if p.is_dir()),key=lambda p:len(p.parts),reverse=True):
 lines.append('RMDir "$INSTDIR\\app\\'+str(directory.relative_to(stage)).replace('/','\\')+'"')
lines.append('RMDir "$INSTDIR\\app"');remove.write_text('\n'.join(lines)+'\n')
installer=release/f'SillyPlayer-{version}-windows-x86_64-setup.exe'
size=sum(p.stat().st_size for p in files)
env=dict(os.environ,NSISDIR=str(sdk/'host/usr/share/nsis'))
subprocess.run([str(sdk/'host/usr/bin/makensis'),'-V2','-DVERSION='+version,'-DPROJECT='+str(root),'-DPAYLOAD='+str(stage),'-DOUTPUT='+str(installer),'-DREMOVE_FILES='+str(remove),'-DSIZE_KIB='+str((size+1023)//1024),str(root/'packaging/windows/installer.nsi')],env=env,check=True)
digest=hashlib.sha256(installer.read_bytes()).hexdigest();(sha_dir/(installer.name+'.sha256')).write_text(digest+'  '+installer.name+'\n')
manifest={'version':version,'platform':'Windows 10/11 x64','installer':installer.name,'sha256':digest,'installedBytes':size,'installerBytes':installer.stat().st_size,'files':len(files),'dlls':sorted(copied),'codeSigned':False}
(root/'build/windows/package-manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
print(json.dumps(manifest,indent=2))
