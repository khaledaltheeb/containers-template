package org.tms.offline;
import android.content.*;
import android.content.pm.ApplicationInfo;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.database.Cursor;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.io.MemoryUsageSetting;
import com.tom_roush.pdfbox.pdmodel.*;
import com.tom_roush.pdfbox.text.*;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.*;

public final class Importer {
 public interface Progress{void update(String text);}
 private final Context context;private final Store store;private final AtomicBoolean cancel;private final Progress progress;
 private static final long MB=1024L*1024L,PART=384L*1024L*1024L;
 public Importer(Context c,Store s,AtomicBoolean flag,Progress p){context=c;store=s;cancel=flag;progress=p;PDFBoxResourceLoader.init(c.getApplicationContext());}
 private void check()throws IOException{if(cancel.get())throw new IOException("تم إلغاء العملية؛ لم يتم تعديل الملف الأصلي.");}
 private void space(long bytes)throws IOException{if(context.getFilesDir().getUsableSpace()<bytes+100*MB)throw new IOException("المساحة الحرة غير كافية لإتمام النسخ والتحقق بأمان.");}
 public String name(Uri u){try(Cursor c=context.getContentResolver().query(u,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst())return c.getString(0);}catch(Exception ignored){}return "document.pdf";}
 private File copyUri(Uri uri,long max)throws Exception{space(Math.min(max,550*MB));File tmp=File.createTempFile("local-import-",".bin",context.getCacheDir());try(InputStream in=context.getContentResolver().openInputStream(uri)){if(in==null)throw new IOException("تعذر فتح الملف المحدد.");copy(in,tmp,max);return tmp;}catch(Exception x){tmp.delete();throw x;}}
 private void copy(InputStream in,File out,long max)throws Exception{try(InputStream r=in;FileOutputStream w=new FileOutputStream(out)){byte[] buf=new byte[256*1024];int n;long size=0;while((n=r.read(buf))!=-1){check();size+=n;if(size>max)throw new IOException("حجم الملف يتجاوز الحد الآمن لهذه العملية.");w.write(buf,0,n);}w.getFD().sync();}}
 public long pdf(Uri uri)throws Exception{String title=name(uri);File source=copyUri(uri,512*MB);try{return pdfFile(source,title);}finally{source.delete();}}
 public long pdfFile(File source,String title)throws Exception{
  check();String sha=Store.sha(source);long duplicate=store.docByHash(sha);if(duplicate>=0)throw new IOException("هذا الملف موجود بالفعل؛ لم تتم إضافة نسخة مكررة.");
  File target=new File(store.root("pdf"),sha+".pdf");space(source.length()+32*MB);copy(new FileInputStream(source),target,512*MB);long doc=-1;SQLiteDatabase db=store.getWritableDatabase();boolean success=false;
  try(PDDocument pdf=PDDocument.load(target,MemoryUsageSetting.setupMixed(24*MB).setTempDir(context.getCacheDir()))){
   if(pdf.isEncrypted()||!pdf.getCurrentAccessPermission().canExtractContent())throw new IOException("أضف نسخة PDF غير محمية تسمح باستخراج النص.");
   int count=pdf.getNumberOfPages();if(count<1||count>20000)throw new IOException("عدد الصفحات غير مدعوم.");
   db.beginTransaction();try{
    doc=store.addDoc(title,sha,count,target.length(),target.getName());int textPages=0;
    for(int i=0;i<count;i++){
     check();progress.update("فهرسة "+title+" — "+(i+1)+" / "+count);
     Extract extractor=new Extract();extractor.setStartPage(i+1);extractor.setEndPage(i+1);String text=extractor.getText(pdf).trim();
     if(text.length()>300000)throw new IOException("الصفحة تحتوي نصاً كبيراً جداً؛ أضف ملفاً مقسماً للحفاظ على سلامة الفهرسة.");
     PDPage page=pdf.getPage(i);float w=page.getCropBox().getWidth(),h=page.getCropBox().getHeight();if(Math.abs(page.getRotation())%180!=0){float t=w;w=h;h=t;}
     long pageId=store.addPage(doc,i,""+(i+1),text,w,h,extractor.boxes,text.indexOf('\uFFFD')>=0);
     if(text.length()>=3){textPages++;for(String chunk:split(text))store.addChunk(pageId,doc,chunk,-1);}
    }
    store.ready(doc,textPages==0?"scanned":"ready");db.setTransactionSuccessful();success=true;
   }finally{db.endTransaction();}
  }catch(com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException x){throw new IOException("الملف محمي بكلمة مرور. أضف نسخة غير محمية للبحث والقراءة محلياً.");}
  finally{if(!success)target.delete();}
  return doc;
 }
 public static List<String> split(String text){List<String> out=new ArrayList<>();int start=0;while(start<text.length()){
  int end=Math.min(text.length(),start+600);if(end<text.length()){int cut=text.lastIndexOf('\n',end);if(cut>start+300)end=cut;else{cut=text.lastIndexOf(' ',end);if(cut>start+350)end=cut;}if(end>start&&Character.isHighSurrogate(text.charAt(end-1)))end--;}
  String part=text.substring(start,end).trim();if(part.length()>=3)out.add(part);if(end>=text.length())break;start=Math.max(start+1,end-90);while(start<text.length()&&start>0&&!Character.isWhitespace(text.charAt(start-1))&&start<end)start++;
 }return out;}
 public static final class Extract extends PDFTextStripper {
  public final JSONArray boxes=new JSONArray();public Extract()throws IOException{setSortByPosition(true);setSuppressDuplicateOverlappingText(true);}
  @Override protected void writeString(String text,List<TextPosition> positions)throws IOException{
   if(!positions.isEmpty()&&!text.trim().isEmpty()){
    float left=Float.MAX_VALUE,top=Float.MAX_VALUE,right=0,bottom=0;
    for(TextPosition t:positions){float x=t.getX(),y=t.getY(),height=Math.max(2,t.getHeight());left=Math.min(left,x);top=Math.min(top,y-height*1.25f);right=Math.max(right,x+Math.max(t.getWidth(),2));bottom=Math.max(bottom,y+height*.25f);}
    try{boxes.put(new JSONArray().put(text).put(new JSONArray().put(Math.max(0,left)).put(Math.max(0,top)).put(right).put(bottom)));}catch(Exception ignored){}
   }super.writeString(text,positions);
  }
 }
 public void legacyInstalled()throws Exception{
  ApplicationInfo old=context.getPackageManager().getApplicationInfo("org.tms.offline",0);
  legacy(new File(old.sourceDir));
 }
 public void legacyUri(Uri u)throws Exception{File f=copyUri(u,900*MB);try{legacy(f);}finally{f.delete();}}
 public void legacy(File supplied)throws Exception{
  File nested=null;File archive=supplied;
  try{
   try(ZipFile z=new ZipFile(archive)){
    if(z.getEntry("assets/ui/data/catalog.js")==null){ZipEntry apk=null;Enumeration<? extends ZipEntry> entries=z.entries();while(entries.hasMoreElements()){ZipEntry e=entries.nextElement();if(e.getName().toLowerCase(Locale.ROOT).endsWith(".apk")){if(apk!=null)throw new IOException("الحزمة تحتوي أكثر من تطبيق؛ اختر APK الأصلي مباشرة.");apk=e;}}
     if(apk==null)throw new IOException("ليست حزمة مكتبة TMs السابقة.");nested=File.createTempFile("previous-app-",".apk",context.getCacheDir());copy(z.getInputStream(apk),nested,900*MB);archive=nested;
    }
   }
   try(ZipFile z=new ZipFile(archive)){legacyZip(z);}
  }finally{if(nested!=null)nested.delete();}
 }
 private static String jsPayload(String s)throws IOException{int equal=s.indexOf('=');if(equal<0)throw new IOException("Invalid local catalog");s=s.substring(equal+1).trim();if(s.endsWith(";"))s=s.substring(0,s.length()-1);return s;}
 private String zipText(ZipFile z,String name,int max)throws Exception{ZipEntry e=z.getEntry(name);if(e==null)throw new IOException("Missing library component: "+name);return Store.read(z.getInputStream(e),max);}
 private void legacyZip(ZipFile z)throws Exception{
  if(!store.meta("legacy_complete").isEmpty())throw new IOException("تم نقل المكتبة السابقة بالفعل. لإعادة ملف محذوف، أضفه من PDF الأصلي.");
  JSONObject catalog=new JSONObject(jsPayload(zipText(z,"assets/ui/data/catalog.js",8*1024*1024)));
  JSONArray docs=catalog.getJSONArray("docs"),pageInfo=catalog.getJSONArray("pages"),mapping=new JSONArray(zipText(z,"assets/semantic/chunks.json",8*1024*1024));
  if(docs.length()>500||pageInfo.length()>100000||mapping.length()>500000)throw new IOException("المكتبة تتجاوز الحدود الآمنة للاستيراد.");
  space(600*MB);Map<Integer,Long> docIds=new HashMap<>();Set<Integer> newDocs=new HashSet<>();List<File> addedFiles=new ArrayList<>();SQLiteDatabase db=store.getWritableDatabase();boolean success=false;
  Map<Integer,List<int[]>> chunks=new HashMap<>();for(int v=0;v<mapping.length();v++){JSONArray a=mapping.getJSONArray(v);int[] rec=new int[a.length()+1];rec[0]=v;for(int k=0;k<a.length();k++)rec[k+1]=a.getInt(k);List<int[]> list=chunks.get(rec[1]);if(list==null){list=new ArrayList<>();chunks.put(rec[1],list);}list.add(rec);}
  db.beginTransaction();try{
   for(int i=0;i<docs.length();i++){
    check();JSONObject d=docs.getJSONObject(i);String name=d.getString("name"),hash=d.getString("sha256");if(name.contains("/")||name.contains("\\")||!hash.matches("[0-9a-f]{64}"))throw new IOException("Invalid document catalog entry");
    long old=store.docByHash(hash);if(old>=0){docIds.put(d.getInt("id"),old);continue;}
    progress.update("نقل الملفات الأصلية — "+(i+1)+" / "+docs.length());ZipEntry pdf=z.getEntry("assets/pdf/"+name);if(pdf==null)throw new IOException("Missing original PDF");
    File f=new File(store.root("pdf"),hash+".pdf");addedFiles.add(f);copy(z.getInputStream(pdf),f,512*MB);if(!Store.sha(f).equals(hash))throw new IOException("Original PDF integrity check failed: "+name);
    long id=store.addDoc(d.optString("title",name),hash,d.getInt("count"),f.length(),f.getName());docIds.put(d.getInt("id"),id);newDocs.add(d.getInt("id"));
   }
   List<String> batches=new ArrayList<>();Enumeration<? extends ZipEntry> it=z.entries();while(it.hasMoreElements()){String n=it.nextElement().getName();if(n.matches("assets/ui/data/p[0-9]{4}\\.js"))batches.add(n);}Collections.sort(batches);
   int indexed=0;for(String batch:batches){check();JSONArray pages=new JSONArray(jsPayload(zipText(z,batch,16*1024*1024)));
    for(int i=0;i<pages.length();i++){
     JSONObject p=pages.getJSONObject(i);int oldId=p.getInt("id");JSONArray info=pageInfo.getJSONArray(oldId);int doc=info.getInt(0);if(!newDocs.contains(doc))continue;
     List<String> lines=new ArrayList<>();JSONArray blocks=p.optJSONArray("b");if(blocks!=null)for(int b=0;b<blocks.length();b++){JSONArray ls=blocks.getJSONObject(b).optJSONArray("l");if(ls!=null)for(int l=0;l<ls.length();l++)lines.add(ls.getJSONArray(l).getString(0));}
     String full=join(lines,0,lines.size());long docId=docIds.get(doc);long pageId=store.addPage(docId,info.getInt(1),info.optString(2,""+(info.getInt(1)+1)),full,(float)p.optDouble("w",612),(float)p.optDouble("h",792),new JSONArray(),p.optBoolean("ocr",false)||full.indexOf('\uFFFD')>=0);
     List<int[]> selected=chunks.get(oldId);if(selected!=null)for(int[] r:selected){int start=r[2],count=r[3];if(start<0||count<0||start+count>lines.size())throw new IOException("Semantic source mapping is inconsistent");String t=join(lines,start,start+count);if(r.length>=6){int a=r[4],b=r[5];if(a<0||b<a||b>t.length())throw new IOException("Semantic character mapping is inconsistent");t=t.substring(a,b);}store.addChunk(pageId,docId,t,r[0]);}
     indexed++;
    }progress.update("فهرسة المكتبة الأصلية — "+indexed+" / "+pageInfo.length());
   }
   for(Integer d:newDocs)store.ready(docIds.get(d),"ready");
   for(String name:new String[]{"vectors.i8","scales.f32"}){ZipEntry entry=z.getEntry("assets/semantic/"+name);if(entry==null)throw new IOException("Missing semantic index");File f=new File(store.root("semantic"),name);copy(z.getInputStream(entry),f,256*MB);long expected=(long)mapping.length()*(name.endsWith("i8")?384:4);if(f.length()!=expected)throw new IOException("Semantic index size mismatch");}
   store.meta("legacy_complete",""+System.currentTimeMillis());db.setTransactionSuccessful();success=true;
  }finally{db.endTransaction();if(!success){for(File f:addedFiles)f.delete();new File(store.root("semantic"),"vectors.i8").delete();new File(store.root("semantic"),"scales.f32").delete();}}
 }
 private static String join(List<String> l,int a,int b){StringBuilder s=new StringBuilder();for(int i=a;i<b;i++){if(i>a)s.append('\n');s.append(l.get(i));}return s.toString();}
 public String model(Uri uri)throws Exception{File f=copyUri(uri,550*MB);try{return modelZip(f,0);}finally{f.delete();}}
 private JSONObject expected(String id)throws Exception{JSONArray a=new JSONArray(Store.read(context.getAssets().open("model-catalog.json"),128*1024));for(int i=0;i<a.length();i++)if(a.getJSONObject(i).getString("id").equals(id))return a.getJSONObject(i);throw new IOException("نموذج غير معتمد في هذه النسخة؛ لم يتم تحميله أو تشغيله.");}
 private String modelZip(File file,int depth)throws Exception{
  check();try(ZipFile z=new ZipFile(file)){
   ZipEntry manifest=z.getEntry("manifest.json"),payload=z.getEntry("payload.part");
   if(manifest==null||payload==null){
    if(depth>=1)throw new IOException("ليست حزمة نموذج صالحة.");ZipEntry inner=null;Enumeration<? extends ZipEntry> entries=z.entries();while(entries.hasMoreElements()){ZipEntry e=entries.nextElement();if(e.getName().endsWith(".zip")){if(inner!=null)throw new IOException("اختر حزمة نموذج واحدة في كل ملف.");inner=e;}}
    if(inner==null)throw new IOException("لم أجد ملف النموذج داخل ZIP.");File temp=File.createTempFile("model-inner-",".zip",context.getCacheDir());try{copy(z.getInputStream(inner),temp,550*MB);return modelZip(temp,depth+1);}finally{temp.delete();}
   }
   JSONObject m=new JSONObject(Store.read(z.getInputStream(manifest),32*1024)),known=expected(m.getString("id"));
   if(!"tms-model-pack-v1".equals(m.optString("format"))||!known.getString("sha256").equals(m.getString("sha256"))||known.getLong("bytes")!=m.getLong("bytes")||known.getInt("parts")!=m.getInt("parts"))throw new IOException("حزمة النموذج لا تطابق البصمة المعتمدة.");
   int part=m.getInt("part"),parts=known.getInt("parts");long size=known.getLong("bytes"),partSize=Math.min(PART,size-part*PART);if(part<0||part>=parts||m.getLong("part_bytes")!=partSize||payload.getSize()!=partSize)throw new IOException("جزء النموذج غير صحيح.");
   File staging=new File(store.root("model-staging"),known.getString("id"));if(!staging.isDirectory()&&!staging.mkdirs())throw new IOException("Cannot prepare model staging");space(size+partSize);
   File tmp=new File(staging,part+".tmp"),done=new File(staging,part+".part");try{progress.update("التحقق من نموذج "+known.getString("title")+" — الجزء "+(part+1)+" / "+parts);copy(z.getInputStream(payload),tmp,partSize);if(tmp.length()!=partSize||!Store.sha(tmp).equals(m.getString("part_sha256")))throw new IOException("فشل التحقق من جزء النموذج؛ أعد تنزيل هذا الجزء.");if(done.exists()&&!done.delete())throw new IOException("Cannot replace staged part");if(!tmp.renameTo(done))throw new IOException("Cannot commit model part");}finally{tmp.delete();}
   int present=0;for(int i=0;i<parts;i++)if(new File(staging,i+".part").isFile())present++;
   if(present<parts)return "تم حفظ "+present+" / "+parts+" أجزاء. أضف الأجزاء المتبقية؛ النموذج لم يصبح جاهزاً بعد.";
   File assembled=new File(store.root("models"),known.getString("id")+".tmp"),target=new File(store.root("models"),known.getString("id")+".gguf");
   try(FileOutputStream out=new FileOutputStream(assembled)){byte[] b=new byte[1024*1024];for(int i=0;i<parts;i++)try(InputStream in=new FileInputStream(new File(staging,i+".part"))){int n;while((n=in.read(b))!=-1){check();out.write(b,0,n);}}out.getFD().sync();}
   check();progress.update("فحص البصمة الكاملة للنموذج قبل تفعيله...");if(assembled.length()!=size||!Store.sha(assembled).equals(known.getString("sha256"))){assembled.delete();throw new IOException("بصمة النموذج الكامل غير صحيحة؛ لم يتم تفعيله.");}
   if(target.exists()&&!target.delete())throw new IOException("Cannot replace model file");if(!assembled.renameTo(target))throw new IOException("Cannot activate model file");store.installModel(known,target);
   for(int i=0;i<parts;i++)new File(staging,i+".part").delete();staging.delete();return "تم تثبيت "+known.getString("title")+" محلياً. فعّله من صفحة النماذج.";
  }
 }
}
