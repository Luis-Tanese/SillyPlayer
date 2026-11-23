#!/usr/bin/env python3
"""Publish standalone preview APKs after signature, ABI and native-alignment checks."""
import hashlib,json,re,shutil,struct,subprocess,zipfile
from pathlib import Path
root=Path(__file__).resolve().parents[1]
project=root/'mobile/android';outputs=project/'app/build/outputs/apk/release'
metadata=json.loads((outputs/'output-metadata.json').read_text())
version=metadata['elements'][0]['versionName']
tools=root/'.deps/android-sdk/build-tools/35.0.0';dist=root/'dist/android'/version;dist.mkdir(parents=True,exist_ok=True)
manifest={'applicationId':metadata['applicationId'],'kind':'release','minSdk':26,'targetSdk':36,'artifacts':[]}
for entry in metadata['elements']:
 abi=next(item['value'] for item in entry['filters'] if item['filterType']=='ABI')
 source=outputs/entry['outputFile']
 verification=subprocess.run([str(tools/'apksigner'),'verify','--verbose','--print-certs',str(source)],capture_output=True,text=True,check=True).stdout
 subprocess.run([str(tools/'zipalign'),'-c','-P','16','4',str(source)],capture_output=True,text=True,check=True)
 native=[]
 with zipfile.ZipFile(source) as archive:
  assert any(name.startswith('assets/licenses/') for name in archive.namelist()),'Missing third-party notices'
  for name in archive.namelist():
   if not name.startswith('lib/') or not name.endswith('.so'):continue
   assert name.startswith('lib/'+abi+'/'),'Unexpected ABI in split APK'
   data=archive.read(name);assert data[:4]==b'\x7fELF'
   wide=data[4]==2
   offset=struct.unpack_from('<Q' if wide else '<I',data,32 if wide else 28)[0]
   stride,count=struct.unpack_from('<HH',data,54 if wide else 42)
   alignment=[struct.unpack_from('<Q' if wide else '<I',data,offset+i*stride+(48 if wide else 28))[0] for i in range(count) if struct.unpack_from('<I',data,offset+i*stride)[0]==1]
   assert alignment and (abi!='arm64-v8a' or min(alignment)>=16384),'Native library is not 16-KiB aligned: '+name
   native.append({'name':name,'bytes':len(data),'loadAlignment':min(alignment)})
 target=dist/f"SillyPlayer-{entry['versionName']}-android-{abi}.apk";shutil.copy2(source,target)
 digest=hashlib.sha256(target.read_bytes()).hexdigest();Path(str(target)+'.sha256').write_text(digest+'  '+target.name+'\n')
 certificate=re.search(r'Signer #1 certificate SHA-256 digest: (.+)',verification)
 manifest['artifacts'].append({'name':target.name,'bytes':target.stat().st_size,'sha256':digest,'abi':abi,'version':entry['versionName'],'versionCode':entry['versionCode'],'signerSha256':certificate.group(1) if certificate else None,'native':native,'zipAligned16KiB':True})
 print(f'{target.name}: {target.stat().st_size/1048576:.1f} MiB; signature and alignment verified')
(dist/f'SillyPlayer-{version}-android-artifacts.json').write_text(json.dumps(manifest,indent=2)+'\n')
shutil.copy2(project/'README.md',dist/f'SillyPlayer-{version}-android-README.md')
shutil.copy2(project/'NOTICE.md',dist/f'SillyPlayer-{version}-android-NOTICE.md')
