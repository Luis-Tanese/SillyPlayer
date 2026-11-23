#!/usr/bin/env python3
"""Fetch a project-local Android toolchain. Does not install an IDE/emulator."""
import hashlib,os,shutil,subprocess,urllib.request,xml.etree.ElementTree as ET,zipfile
from pathlib import Path
root=Path(__file__).resolve().parents[1]
deps=root/'.deps';sdk=deps/'android-sdk';downloads=deps/'android-downloads'
sdk.mkdir(parents=True,exist_ok=True);downloads.mkdir(parents=True,exist_ok=True)
def fetch(url,target,digest=None,algorithm='sha256'):
 if target.exists() and (not digest or hashlib.new(algorithm,target.read_bytes()).hexdigest()==digest):return
 temporary=target.with_suffix(target.suffix+'.part')
 print('Downloading',url,flush=True)
 with urllib.request.urlopen(url,timeout=90) as source,temporary.open('wb') as output:shutil.copyfileobj(source,output,1024*1024)
 if digest and hashlib.new(algorithm,temporary.read_bytes()).hexdigest()!=digest:temporary.unlink();raise RuntimeError('Checksum mismatch: '+url)
 temporary.replace(target)
manager=sdk/'cmdline-tools/latest/bin/sdkmanager'
if not manager.exists():
 metadata=ET.fromstring(urllib.request.urlopen('https://dl.google.com/android/repository/repository2-1.xml',timeout=60).read())
 for package in metadata.findall('remotePackage'):
  if package.attrib['path']=='cmdline-tools;latest':
   for archive in package.findall('archives/archive'):
    if archive.findtext('host-os')=='linux':
     complete=archive.find('complete');name=complete.findtext('url');file=downloads/name
     fetch('https://dl.google.com/android/repository/'+name,file,complete.findtext('checksum'),'sha1')
     temporary=downloads/'command-line-unpacked';temporary.mkdir(exist_ok=True)
     with zipfile.ZipFile(file) as z:z.extractall(temporary)
     (sdk/'cmdline-tools').mkdir(exist_ok=True)
     shutil.move(str(temporary/'cmdline-tools'),str(sdk/'cmdline-tools/latest'))
     for f in (sdk/'cmdline-tools/latest/bin').iterdir():f.chmod(0o755)
 env=dict(os.environ,ANDROID_HOME=str(sdk),ANDROID_SDK_ROOT=str(sdk))
else:env=dict(os.environ,ANDROID_HOME=str(sdk),ANDROID_SDK_ROOT=str(sdk))
# The requested SDK installation includes its license acceptance step.
licenses=subprocess.run([str(manager),'--sdk_root='+str(sdk),'--licenses'],input='y\n'*100,text=True,env=env,stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
if licenses.returncode:raise RuntimeError(licenses.stdout[-3000:])
subprocess.run([str(manager),'--sdk_root='+str(sdk),'platform-tools','platforms;android-36','build-tools;35.0.0','ndk;28.1.13356709','cmake;3.22.1'],check=True,env=env)
gradle=deps/'gradle-8.13/bin/gradle'
if not gradle.exists():
 url='https://services.gradle.org/distributions/gradle-8.13-bin.zip'
 digest=urllib.request.urlopen(url+'.sha256',timeout=60).read().decode().strip();file=downloads/'gradle-8.13-bin.zip'
 fetch(url,file,digest)
 with zipfile.ZipFile(file) as z:z.extractall(deps)
 gradle.chmod(0o755)
project=root/'mobile/android';(project/'local.properties').write_text('sdk.dir='+str(sdk)+'\n')
print('Android SDK:',sdk,flush=True);print('Gradle:',gradle,flush=True)
