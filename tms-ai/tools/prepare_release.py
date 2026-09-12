from pathlib import Path
import tarfile,zipfile,hashlib,urllib.request,json
root=Path('tms-ai');libs=root/'libs';libs.mkdir(parents=True,exist_ok=True)
for name in ['bcprov','bcpkix','bcutil']:
 url=f'https://repo.maven.apache.org/maven2/org/bouncycastle/{name}-jdk15to18/1.72/{name}-jdk15to18-1.72.jar'
 p=libs/f'{name}.jar'
 with urllib.request.urlopen(url) as r,p.open('wb') as w:
  while b:=r.read(1024*1024):w.write(b)
model=root/'assets/models/e5/model_quantized.onnx';model.parent.mkdir(parents=True,exist_ok=True)
archive=next(Path('legacy-model').glob('*.tar.gz'))
with tarfile.open(archive,'r:gz') as t:
 matches=[m for m in t.getmembers() if m.name.endswith('neural/models/e5/onnx/model_quantized.onnx')]
 assert len(matches)==1,[m.name for m in matches]
 with t.extractfile(matches[0]) as r,model.open('wb') as w:
  while b:=r.read(1024*1024):w.write(b)
actual=hashlib.file_digest(model.open('rb'),'sha256').hexdigest()
print('ORIGINAL_E5_SHA256',actual,'BYTES',model.stat().st_size)
assert actual=='dd476dd0c2514e9b9be83aeb3853fac0763e0bdf4a71645407587d77c48a2d88'
p=root/'java/org/tms/offline/MainActivity.java';s=p.read_text()
s=s.replace('active.setOnCheckedChangeListener((v,on)->store.docEnabled(id,on));','active.setOnCheckedChangeListener((v,on)->{if(!busy)store.docEnabled(id,on);});active.setOnTouchListener((v,event)->busy);')
s=s.replace('ui.post(()->{setBusy(false);status.setText("اكتملت العملية المحلية");','ui.post(()->{if(isFinishing()||isDestroyed())return;setBusy(false);status.setText("اكتملت العملية المحلية");')
p.write_text(s)
p=root/'tests/android/DeviceTests.java';s=p.read_text()
s=s.replace('try(PdfDocument d=new PdfDocument()){','PdfDocument d=new PdfDocument();try{')
s=s.replace('\n  }\n  String originalHash=', '\n  }finally{d.close();}\n  String originalHash=')
p.write_text(s)
