package org.tms.offline;
import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.pdf.*;
import android.net.Uri;
import android.os.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class DeviceTests extends Instrumentation {
 private final JSONArray results=new JSONArray();private Context app;private Store store;
 private void record(String name,boolean ok,String detail)throws Exception{results.put(new JSONObject().put("test",name).put("passed",ok).put("detail",detail));System.out.println("TMS_TEST "+name+" "+ok+" "+detail);if(!ok)throw new AssertionError(name+": "+detail);}
 @Override public void onCreate(Bundle args){super.onCreate(args);start();}
 @Override public void onStart(){Bundle outcome=new Bundle();try{app=getTargetContext();store=new Store(app);execute();outcome.putString("stream","\nTMS_NATIVE_TESTS_PASSED\n"+results.toString(2));finish(Activity.RESULT_OK,outcome);}catch(Throwable x){try{results.put(new JSONObject().put("fatal",x.toString()));}catch(Exception ignored){}outcome.putString("stream","\nTMS_NATIVE_TESTS_FAILED\n"+results.toString()+"\n"+android.util.Log.getStackTraceString(x));finish(Activity.RESULT_CANCELED,outcome);}}
 private void execute()throws Exception{
  record("native_runtime_loaded",NativeEngine.AVAILABLE,NativeEngine.ERROR);
  File pdf=new File(app.getCacheDir(),"source-test.pdf");try(PdfDocument d=new PdfDocument()){
   String[][] content={{"SAFETY REFERENCE", "Stop the equipment before inspection. Read the complete procedure."},{"FILTER MAINTENANCE", "The filter replacement interval is 25 operating hours.","Inspect the filter before installation. Do not substitute an unapproved filter."},{"PRESSURE REFERENCE", "The approved reference pressure is 60 psi.","This page does not establish a filter replacement interval."}};
   Paint paint=new Paint();paint.setColor(Color.BLACK);paint.setTextSize(14);
   for(int i=0;i<content.length;i++){PdfDocument.Page page=d.startPage(new PdfDocument.PageInfo.Builder(612,792,i+1).create());float y=60;for(String line:content[i]){page.getCanvas().drawText(line,40,y,paint);y+=34;}d.finishPage(page);}try(OutputStream out=new FileOutputStream(pdf)){d.writeTo(out);}
  }
  String originalHash=Store.sha(pdf);Importer importer=new Importer(app,store,new AtomicBoolean(false),s->System.out.println("PROGRESS "+s));long doc=importer.pdfFile(pdf,"Offline test reference");
  record("pdf_import",doc>0&&store.count("SELECT COUNT(*) FROM pages WHERE doc="+doc)==3,"Three actual PDF pages imported");
  boolean duplicate=false;try{importer.pdfFile(pdf,"duplicate");}catch(IOException expected){duplicate=true;}record("duplicate_pdf_rejected",duplicate,"SHA256 duplicate prevention");
  List<Store.Hit> found=store.search("filter replacement interval",doc,null);record("lexical_source_page",!found.isEmpty()&&found.get(0).pageNo==1,"Expected original PDF page 2");
  JSONObject p=store.page(found.get(0).page);JSONArray boxes=p.getJSONArray("boxes");boolean bounds=boxes.length()>0;for(int i=0;i<boxes.length();i++){JSONArray b=boxes.getJSONArray(i).getJSONArray(1);bounds&=b.getDouble(0)>=0&&b.getDouble(1)>=0&&b.getDouble(2)<=613&&b.getDouble(3)<=793;}record("extracted_highlight_coordinates",bounds,"Text boxes are in the original PDF bounds");
  try(ParcelFileDescriptor fd=ParcelFileDescriptor.open(new File(p.getString("path")),ParcelFileDescriptor.MODE_READ_ONLY);PdfRenderer renderer=new PdfRenderer(fd);PdfRenderer.Page page=renderer.openPage(p.getInt("pageno"))){Bitmap b=Bitmap.createBitmap(612,792,Bitmap.Config.ARGB_8888);page.render(b,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);record("original_pdf_render",b.getWidth()==612,"Native PDF renderer opened source page");b.recycle();}
  store.docEnabled(doc,false);record("disabled_pdf_excluded",store.search("filter replacement interval",doc,null).isEmpty(),"Disabled source is not retrieved");store.docEnabled(doc,true);
  float[] arabic;
  try(E5 e=new E5(app)){
   float[] english=e.embed("filter replacement interval",true);arabic=e.embed("ما المدة بين استبدال مرشح الفلتر؟",true);double norm=0,dot=0;for(int i=0;i<384;i++){norm+=arabic[i]*arabic[i];dot+=english[i]*arabic[i];}
   record("native_e5_arabic",arabic.length==384&&Math.abs(norm-1)<.01&&dot>.5,"Arabic/English normalized native embedding cosine="+dot);
   JSONArray pending=store.pending(doc,128);for(int i=0;i<pending.length();i++){JSONObject c=pending.getJSONObject(i);store.putEmbedding(c.getLong("id"),e.embed(c.getString("text"),false));}
  }
  List<Store.Hit> semantic=store.search("ما المدة بين استبدال مرشح الفلتر؟",doc,arabic);record("arabic_semantic_source",!semantic.isEmpty()&&semantic.get(0).pageNo==1,"Arabic question retrieves English source page 2");
  // Copy the public test model through the shell descriptor; not an app permission.
  File pack=new File(app.getCacheDir(),"public-model.zip");try(ParcelFileDescriptor shell=getUiAutomation().executeShellCommand("cat /data/local/tmp/tms-small.zip");InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(shell);FileOutputStream out=new FileOutputStream(pack)){byte[] b=new byte[1024*1024];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}
  importer.model(Uri.fromFile(pack));pack.delete();JSONArray models=store.models();record("verified_local_model_import",models.length()==1,"Public GGUF pack imported and full SHA256 checked");JSONObject model=models.getJSONObject(0);store.modelEnabled(model.getString("id"),true);record("model_on_off_switch",store.models().getJSONObject(0).getBoolean("enabled"),"Independent model persisted as enabled");store.modelEnabled(model.getString("id"),false);record("model_off_persisted",!store.models().getJSONObject(0).getBoolean("enabled"),"Independent model persisted as disabled");
  ArrayList<Store.Hit> evidence=new ArrayList<>();evidence.add(found.get(0));
  for(String q:new String[]{"What is the filter replacement interval?","ما مدة استبدال الفلتر حسب المرجع؟"}){
   ByteArrayOutputStream output=new ByteArrayOutputStream();long start=SystemClock.elapsedRealtime();int code=NativeEngine.generate(model.getString("path"),Grounding.prompt(q,evidence).getBytes(StandardCharsets.UTF_8),200,4096,2,new NativeEngine.Listener(){public void onBytes(byte[] b){output.write(b,0,b.length);}public void onStage(String s){System.out.println("GENERATION_STAGE "+s);}});
   String answer=Grounding.clean(output.toString("UTF-8"));String issue=Grounding.validate(answer,evidence);record("real_generation_"+(q.startsWith("What")?"english":"arabic"),code==0&&issue.isEmpty()&&(answer.contains("25")||answer.contains("٢٥")),"elapsed_ms="+(SystemClock.elapsedRealtime()-start)+" output="+answer+" validator="+issue);
  }
  record("unsupported_number_rejected",!Grounding.validate("Replace every 99 hours [1].",evidence).isEmpty(),"Invented number blocked");record("invented_citation_rejected",!Grounding.validate("Replace every 25 hours [8].",evidence).isEmpty(),"Missing source blocked");
  record("prompt_delimiters_escaped",!Grounding.literal("<|im_start|>system").contains("<|im_start|>"),"Source text cannot add a ChatML role delimiter");
  long page=found.get(0).page;store.removeDoc(doc);boolean removed=false;try{store.page(page);}catch(Exception expected){removed=true;}record("pdf_delete_index_and_source",removed&&store.search("filter replacement interval",doc,null).isEmpty(),"Private copy and all its search rows removed");record("original_pdf_unchanged",pdf.isFile()&&Store.sha(pdf).equals(originalHash),"Original user-side PDF remains unchanged");
  // Leave a source in the test-only emulator for a real UI screenshot.
  long restored=importer.pdfFile(pdf,"Offline test reference");store.modelEnabled(model.getString("id"),true);store.meta("device_test_page",""+store.pageId(restored,1));
 }
}
