from pathlib import Path
import os,shutil,subprocess,zipfile,json
from reportlab.pdfgen import canvas
ROOT=Path(__file__).resolve().parent
B=ROOT/'build';B.mkdir(exist_ok=True)
SDK=Path(os.environ['ANDROID_HOME']);BT=SDK/'build-tools/35.0.0';JAR=SDK/'platforms/android-35/android.jar'
def run(a):subprocess.run(list(map(str,a)),check=True)
manifest='''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="org.tms.offline" android:versionCode="2" android:versionName="2.0.0"><uses-sdk android:minSdkVersion="28" android:targetSdkVersion="35"/><application android:label="TMs Verification" android:theme="@android:style/Theme.Material.Light.NoActionBar" android:debuggable="false" android:allowBackup="false" android:usesCleartextTraffic="false" android:hardwareAccelerated="true"><activity android:name=".MainActivity" android:exported="true" android:configChanges="orientation|screenSize|keyboardHidden"><intent-filter><action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LAUNCHER"/></intent-filter></activity></application></manifest>'''
(B/'AndroidManifest.xml').write_text(manifest)
run([BT/'aapt2','link','-I',JAR,'--manifest',B/'AndroidManifest.xml','-o',B/'base.apk'])
(B/'classes').mkdir(exist_ok=True);(B/'dex').mkdir(exist_ok=True)
run(['javac','-source','8','-target','8','-bootclasspath',str(JAR)+os.pathsep+str(BT/'core-lambda-stubs.jar'),'-d',B/'classes',*ROOT.glob('*.java')])
run(['jar','cf',B/'classes.jar','-C',B/'classes','.'])
run([BT/'d8','--min-api','28','--lib',JAR,'--output',B/'dex',B/'classes.jar'])
assets=B/'assets';(assets/'pdf').mkdir(parents=True,exist_ok=True);ui=assets/'ui';ui.mkdir(exist_ok=True)
c=canvas.Canvas(str(assets/'pdf/fixture.pdf'))
for i in range(3):
 c.drawString(60,760,'Synthetic PDF verification - page '+str(i+1));c.drawString(60,730,'NOT A USER DOCUMENT. Testing the local Android renderer.');c.rect(60,600,300,90);c.showPage()
c.save()
shutil.copytree('neural/models',assets/'models',dirs_exist_ok=True)
(ui/'vendor').mkdir(exist_ok=True)
for name in ['transformers.min.js','ort-wasm-simd-threaded.jsep.mjs','ort-wasm-simd-threaded.jsep.wasm']:shutil.copy(Path('neural/vendor/transformers')/name,ui/'vendor'/name)
(ui/'index.html').write_text('''<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'self' 'wasm-unsafe-eval'; worker-src 'self' blob:; style-src 'self' 'unsafe-inline'; img-src 'self'; connect-src 'self'"></head><body><h2>TMs Offline verification</h2><pre id="status">Starting...</pre><img id="pdf" style="width:100%"><script src="probe.js"></script></body></html>''')
(ui/'probe.js').write_text('''let pdfDone=false,modelDone=false,step=0;const status=document.getElementById('status');function log(x){status.textContent+='\\n'+x;LocalPdf.logError(x);}function done(){if(pdfDone&&modelDone)log('TMS_PROBE_PASS');}window.addEventListener('error',e=>log('PROBE_ERROR '+e.message));log('PROBE_NATIVE_READY '+LocalPdf.diagnostics());window.TMS_PDF_RESULT=r=>{try{if(step<3){if(!r.ok)throw Error(r.error);const im=document.getElementById('pdf');im.onload=()=>{log('PROBE_PDF_PAGE '+step+' '+im.naturalWidth+'x'+im.naturalHeight);step++;if(step<3)LocalPdf.renderPageAsync('fixture.pdf',step,1800,step+1);else LocalPdf.renderPageAsync('fixture.pdf',999,1200,4);};im.src=r.url;}else if(step===3){if(r.ok)throw Error('Accepted invalid page');step++;LocalPdf.renderPageAsync('../private.pdf',0,1200,5);}else{if(r.ok)throw Error('Accepted invalid path');pdfDone=true;log('PROBE_PDF_PASS');done();}}catch(e){log('PROBE_ERROR '+e.message);}};LocalPdf.renderPageAsync('fixture.pdf',0,1200,1);const w=new Worker('probe-worker.js',{type:'module'});w.onmessage=e=>{if(!e.data.ok){log('PROBE_ERROR '+e.data.error);return;}modelDone=true;log('PROBE_MODEL_PASS '+JSON.stringify(e.data));done();};w.onerror=e=>log('PROBE_ERROR worker '+e.message);''')
(ui/'probe-worker.js').write_text('''import {pipeline,env} from './vendor/transformers.min.js';env.allowRemoteModels=false;env.allowLocalModels=true;env.localModelPath=new URL('../models/',self.location.href).href;env.useBrowserCache=false;env.useFSCache=false;env.backends.onnx.wasm.wasmPaths=new URL('./vendor/',self.location.href).href;env.backends.onnx.wasm.numThreads=1;env.backends.onnx.wasm.proxy=false;(async()=>{try{const t=performance.now();const model=await pipeline('feature-extraction','e5',{device:'wasm',dtype:'q8',local_files_only:true});const out=await model(['query: ما هو ضغط النظام الهيدروليكي','passage: Hydraulic system pressure is supplied by hydraulic pumps and stored in accumulators.','passage: Bananas are used in a sweet cake recipe.','passage: A calendar contains months and days.'],{pooling:'mean',normalize:true,truncation:true,max_length:256});const v=Array.from(out.data);if(v.length!==1536||!v.every(Number.isFinite))throw Error('Invalid embedding dimensions');const scores=[1,2,3].map(n=>{let s=0;for(let i=0;i<384;i++)s+=v[i]*v[n*384+i];return s;});if(scores[0]<=scores[1]||scores[0]<=scores[2])throw Error('Cross-language retrieval test failed');self.postMessage({ok:true,scores,seconds:(performance.now()-t)/1000,dims:out.dims});}catch(e){self.postMessage({ok:false,error:String(e.message||e)});}})();''')
shutil.copy(B/'base.apk',B/'unsigned.apk')
with zipfile.ZipFile(B/'unsigned.apk','a',compression=zipfile.ZIP_DEFLATED) as z:
 for f in (B/'dex').glob('*.dex'):z.write(f,f.name)
 for f in assets.rglob('*'):
  if f.is_file():z.write(f,'assets/'+str(f.relative_to(assets)),compress_type=zipfile.ZIP_STORED if f.suffix in ['.onnx','.wasm'] else zipfile.ZIP_DEFLATED)
run([BT/'zipalign','-f','4',B/'unsigned.apk',B/'aligned.apk'])
run(['keytool','-genkeypair','-keystore',B/'test.jks','-storepass','android','-keypass','android','-alias','test','-keyalg','RSA','-validity','10','-dname','CN=Temporary Android Test'])
run([BT/'apksigner','sign','--ks',B/'test.jks','--ks-pass','pass:android','--out',B/'probe.apk',B/'aligned.apk'])
run([BT/'apksigner','verify','--verbose',B/'probe.apk'])
print('Synthetic test APK created; no user documents or user signing keys.')
