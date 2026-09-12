#!/usr/bin/env python3
"""Build a native Android APK from pinned public dependencies; never reads user PDFs."""
from pathlib import Path
import os,subprocess,zipfile,hashlib,json,urllib.request,shutil,glob,struct
ROOT=Path(__file__).resolve().parents[1]
WORK=Path.cwd(); SDK=Path(os.environ['ANDROID_HOME']); TOOLS=SDK/'build-tools'/'35.0.0'; ANDROID=SDK/'platforms'/'android-35'/'android.jar'
def run(*cmd):subprocess.run([str(x) for x in cmd],check=True)
def sha(p):
 with open(p,'rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()
def fetch(url,p,expected=None):
 p.parent.mkdir(parents=True,exist_ok=True)
 if not p.exists():
  with urllib.request.urlopen(url,timeout=180) as r,p.open('wb') as f:shutil.copyfileobj(r,f,1024*1024)
 if expected and sha(p)!=expected:raise ValueError('Hash mismatch: '+str(p))
 return p
assets=ROOT/'assets';res=ROOT/'res';libs=ROOT/'libs';jni=ROOT/'jniLibs';build=ROOT/'build'
for p in [assets,res/'values',res/'drawable',libs,jni,build]:p.mkdir(parents=True,exist_ok=True)
# Android 9 compatibility: do not use WindowInsets.Type before API 30.
main=ROOT/'java/org/tms/offline/MainActivity.java';s=main.read_text();s=s.replace('android.graphics.Insets b=insets.getInsets(WindowInsets.Type.systemBars());v.setPadding(dp(14)+b.left,dp(8)+b.top,dp(14)+b.right,dp(8)+b.bottom);','v.setPadding(dp(14)+insets.getSystemWindowInsetLeft(),dp(8)+insets.getSystemWindowInsetTop(),dp(14)+insets.getSystemWindowInsetRight(),dp(8)+insets.getSystemWindowInsetBottom());');main.write_text(s)
# Match Python codepoint offsets in legacy semantic mappings, not Java UTF-16 indices.
p=ROOT/'java/org/tms/offline/Importer.java';s=p.read_text();s=s.replace('b>t.length())throw new IOException("Semantic character mapping is inconsistent");t=t.substring(a,b);','b>t.codePointCount(0,t.length()))throw new IOException("Semantic character mapping is inconsistent");t=t.substring(t.offsetByCodePoints(0,a),t.offsetByCodePoints(0,b));');p.write_text(s)
# AAR jars, local CMaps and only 64-bit native runtimes. No font binaries are distributed.
for name in ['pdfbox','onnxruntime']:
 with zipfile.ZipFile(WORK/'deps'/f'{name}.aar') as z:
  (libs/f'{name}.jar').write_bytes(z.read('classes.jar'))
  for n in z.namelist():
   if n.startswith('assets/') and not n.endswith('/') and not n.lower().endswith(('.ttf','.otf','.woff','.woff2')):
    target=assets/n[7:];target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(z.read(n))
   if n.startswith(('jni/arm64-v8a/','jni/x86_64/')) and n.endswith('.so'):
    target=jni/n[4:];target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(z.read(n))
shutil.copy2(WORK/'deps/commons-logging.jar',libs/'commons-logging.jar')
for abi in ['arm64-v8a','x86_64']:
 (jni/abi).mkdir(exist_ok=True);shutil.copy2(WORK/'native'/abi/'libtms_local_ai.so',jni/abi/'libtms_local_ai.so')
e5=assets/'models/e5';e5.mkdir(parents=True,exist_ok=True)
fetch('https://huggingface.co/Xenova/multilingual-e5-small/resolve/main/onnx/model_quantized.onnx',e5/'model_quantized.onnx','dd476dd0c2514e9b9be83aeb3853fac0763e0bdf4a71645407587d77c48a2d88')
shutil.copy2(WORK/'deps/sentencepiece.bpe.model',e5/'sentencepiece.bpe.model')
models=[]
for label in ['small','balanced']:
 meta=json.loads((WORK/'model-info'/label/'model-info.json').read_text());models.append(meta)
(assets/'model-catalog.json').write_text(json.dumps(models,ensure_ascii=False,indent=2))
(assets/'asset-hashes.json').write_text(json.dumps({str(p.relative_to(assets)):{'bytes':p.stat().st_size,'sha256':sha(p)} for p in [e5/'model_quantized.onnx',e5/'sentencepiece.bpe.model']},indent=2))
(assets/'licenses').mkdir(exist_ok=True)
fetch('https://raw.githubusercontent.com/ggml-org/llama.cpp/v0.4.0/LICENSE',assets/'licenses/llama-MIT.txt')
fetch('https://raw.githubusercontent.com/google/sentencepiece/v0.2.0/LICENSE',assets/'licenses/sentencepiece-Apache-2.0.txt')
fetch('https://www.apache.org/licenses/LICENSE-2.0.txt',assets/'licenses/Apache-2.0.txt')
(assets/'licenses/E5-NOTICE.txt').write_text('multilingual-e5-small by intfloat. MIT license. ONNX quantized conversion by Xenova. Exact model SHA256: dd476dd0c2514e9b9be83aeb3853fac0763e0bdf4a71645407587d77c48a2d88\n')
(assets/'licenses/NOTICE.txt').write_text('llama.cpp MIT; ONNX Runtime MIT; E5 MIT; SentencePiece Apache-2.0; PDFBox Android Apache-2.0; commons-logging Apache-2.0; Qwen3 imported model packs Apache-2.0. Runtime uses no network.\n')
(res/'values/styles.xml').write_text('''<resources><style name="AppTheme" parent="android:style/Theme.Material.NoActionBar"><item name="android:fontFamily">sans</item><item name="android:windowLightStatusBar">false</item><item name="android:colorAccent">#70DAC7</item><item name="android:windowActionModeOverlay">true</item><item name="android:windowLightNavigationBar">false</item><item name="android:windowBackground">#0E1522</item></style></resources>''')
(res/'drawable/icon.xml').write_text('''<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="48dp" android:height="48dp" android:viewportWidth="48" android:viewportHeight="48"><path android:fillColor="#0E1522" android:pathData="M0,0h48v48h-48z"/><path android:fillColor="#70DAC7" android:pathData="M10,10h28v22h-12l-8,7v-7h-8z"/><path android:fillColor="#0E1522" android:pathData="M15,17h18v3h-18zM15,24h12v3h-12z"/></vector>''')
manifest='''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="org.tms.localai" android:versionCode="50" android:versionName="5.0.0"><uses-sdk android:minSdkVersion="28" android:targetSdkVersion="35"/><queries><package android:name="org.tms.offline"/></queries><application android:label="TMs Local AI" android:icon="@drawable/icon" android:theme="@style/AppTheme" android:allowBackup="false" android:usesCleartextTraffic="false" android:supportsRtl="true" android:extractNativeLibs="false"><activity android:name="org.tms.offline.MainActivity" android:exported="true" android:configChanges="orientation|screenSize|keyboardHidden" android:windowSoftInputMode="adjustResize"><intent-filter><action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LAUNCHER"/></intent-filter></activity><activity android:name="org.tms.offline.ReaderActivity" android:exported="false" android:configChanges="orientation|screenSize"/><service android:name="org.tms.offline.AiService" android:exported="false" android:process=":local_ai"/></application></manifest>'''
(ROOT/'AndroidManifest.xml').write_text(manifest)
run(TOOLS/'aapt2','compile','--dir',res,'-o',build/'compiled.zip')
run(TOOLS/'aapt2','link','-o',build/'resources.apk','-I',ANDROID,'--manifest',ROOT/'AndroidManifest.xml','--java',build/'gen','-A',assets,'-0','onnx','--min-sdk-version','28','--target-sdk-version','35',build/'compiled.zip')
(build/'classes').mkdir(exist_ok=True);sources=list((ROOT/'java').rglob('*.java'))+list((build/'gen').rglob('*.java'));(build/'sources.txt').write_text('\n'.join(map(str,sources)))
classpath=os.pathsep.join([str(ANDROID)]+[str(p) for p in libs.glob('*.jar')])
run('javac','-encoding','UTF-8','-source','8','-target','8','-cp',classpath,'-d',build/'classes','@'+str(build/'sources.txt'))
run('jar','cf',build/'app.jar','-C',build/'classes','.')
(build/'dex').mkdir(exist_ok=True)
run(TOOLS/'d8','--release','--min-api','28','--lib',ANDROID,'--output',build/'dex',build/'app.jar',*libs.glob('*.jar'))
shutil.copy2(build/'resources.apk',build/'unsigned.apk')
with zipfile.ZipFile(build/'unsigned.apk','a',compression=zipfile.ZIP_DEFLATED,compresslevel=6) as z:
 for p in (build/'dex').glob('*.dex'):z.write(p,p.name)
 for p in jni.rglob('*.so'):z.write(p,'lib/'+str(p.relative_to(jni)),compress_type=zipfile.ZIP_STORED)
run(TOOLS/'zipalign','-f','-P','16','4',build/'unsigned.apk',build/'aligned.apk')
# Testable release-candidate signing; private key is never uploaded or printed.
keystore=Path(os.environ.get('TMS_KEYSTORE',str(WORK/'private-signing.p12')))
if not keystore.exists():run('keytool','-genkeypair','-noprompt','-keystore',keystore,'-storetype','PKCS12','-storepass','local-build-only','-keypass','local-build-only','-alias','tms','-keyalg','RSA','-keysize','3072','-validity','3650','-dname','CN=TMs Local AI, OU=Offline Application, O=Sakher Altheeb')
run(TOOLS/'apksigner','sign','--ks',keystore,'--ks-key-alias','tms','--ks-pass','pass:local-build-only','--key-pass','pass:local-build-only','--out',build/'TMs_Local_AI.apk',build/'aligned.apk')
run(TOOLS/'apksigner','verify','--verbose','--print-certs',build/'TMs_Local_AI.apk')
run(TOOLS/'zipalign','-c','-P','16','4',build/'TMs_Local_AI.apk')
(build/'SHA256.txt').write_text(sha(build/'TMs_Local_AI.apk')+'  TMs_Local_AI.apk\n')
with zipfile.ZipFile(build/'TMs_Local_AI_APK.zip','w',zipfile.ZIP_DEFLATED,compresslevel=6) as z:z.write(build/'TMs_Local_AI.apk','TMs_Local_AI.apk');z.write(build/'SHA256.txt','SHA256.txt')
print('BUILD_COMPLETE',str(build/'TMs_Local_AI.apk'),(build/'TMs_Local_AI.apk').stat().st_size)
