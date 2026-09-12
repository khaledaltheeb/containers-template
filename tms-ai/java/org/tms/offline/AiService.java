package org.tms.offline;
import android.app.*;
import android.content.*;
import android.os.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class AiService extends Service {
 public static final int QUERY=1,EMBED=2,STOP=3,STATUS=100,SOURCES=101,DRAFT=102,ANSWER=103,ERROR=104,DONE=105;
 private final ExecutorService worker=Executors.newSingleThreadExecutor();
 private final AtomicBoolean busy=new AtomicBoolean(),cancelled=new AtomicBoolean();
 private Store store;private Messenger client;
 private final Messenger endpoint=new Messenger(new Handler(Looper.getMainLooper()){
  @Override public void handleMessage(Message m){
   if(m.what==STOP){cancelled.set(true);if(NativeEngine.AVAILABLE)NativeEngine.cancel();return;}
   if(m.what!=QUERY&&m.what!=EMBED)return;
   if(!busy.compareAndSet(false,true)){sendTo(m.replyTo,ERROR,"هناك عملية محلية قيد التشغيل؛ انتظر أو أوقفها.");return;}
   client=m.replyTo;cancelled.set(false);Bundle b=new Bundle(m.getData());int what=m.what;
   worker.execute(()->{try{if(what==QUERY)query(b);else index(b);}catch(Throwable x){send(ERROR,friendly(x));}finally{if(NativeEngine.AVAILABLE)NativeEngine.releaseIndex();busy.set(false);send(DONE,"");}});
  }
 });
 @Override public void onCreate(){super.onCreate();store=new Store(this);}
 @Override public IBinder onBind(Intent i){return endpoint.getBinder();}
 private static void sendTo(Messenger c,int type,String text){if(c==null)return;try{Message m=Message.obtain(null,type);Bundle b=new Bundle();b.putString("text",text);m.setData(b);c.send(m);}catch(Exception ignored){}}
 private void send(int type,String text){sendTo(client,type,text);}
 private static String friendly(Throwable x){if(x instanceof OutOfMemoryError)return "الذاكرة غير كافية. أوقف النموذج الأكبر واستعمل البحث أو النموذج الأصغر.";String s=x.getMessage();return s==null?x.getClass().getSimpleName():s;}
 private void check()throws IOException{if(cancelled.get())throw new IOException("تم إيقاف العملية المحلية.");}
 private boolean memory(long required){ActivityManager am=(ActivityManager)getSystemService(ACTIVITY_SERVICE);ActivityManager.MemoryInfo info=new ActivityManager.MemoryInfo();am.getMemoryInfo(info);return !info.lowMemory&&info.availMem>required;}
 private void query(Bundle args)throws Exception{
  String q=args.getString("question","").trim();if(q.length()<2||q.length()>1500)throw new IOException("اكتب سؤالاً بين حرفين و1500 حرف.");long scope=args.getLong("doc",-1);boolean useE5=args.getBoolean("e5",true);float[] vector=null;
  if(useE5){send(STATUS,"البحث بالمعنى على الجهاز...");try(E5 e=new E5(this)){vector=e.embed(q,true);}catch(Exception|LinkageError x){send(STATUS,"تعذر تشغيل E5؛ يستمر البحث بالكلمات فقط: "+friendly(x));}}
  check();List<Store.Hit> found=store.search(q,scope,vector);JSONArray shown=new JSONArray();for(Store.Hit h:found)shown.put(h.json());send(SOURCES,shown.toString());
  JSONArray answers=new JSONArray();List<Store.Hit> evidence=new ArrayList<>(found.subList(0,Math.min(4,found.size())));
  if(found.isEmpty()){send(ANSWER,new JSONObject().put("model","البحث المحلي").put("text","لم أجد دليلاً مناسباً في الملفات المفعّلة. جرّب صياغة أدق أو أضف مرجعاً مناسباً.").put("accepted",false).toString());return;}
  boolean suitable=false;for(Store.Hit h:evidence)if(h.lexical>0||h.semantic>=.82)suitable=true;
  if(!suitable){send(ANSWER,new JSONObject().put("model","البحث المحلي").put("text","وجدت مقاطع محتملة، لكن التطابق غير كافٍ للتوليد المسؤول. راجع المصادر أو أعد صياغة السؤال.").put("accepted",false).toString());return;}
  JSONArray models=store.models();int enabled=0;
  for(int i=0;i<models.length();i++){
   JSONObject model=models.getJSONObject(i);if(!model.getBoolean("enabled"))continue;enabled++;check();String title=model.getString("title");File f=new File(model.getString("path"));
   if(!NativeEngine.AVAILABLE){send(ERROR,"محرك النماذج غير متوافق مع هذا الجهاز. البحث النصي متاح.");break;}
   if(!f.isFile()||f.length()!=model.getLong("bytes")){send(ERROR,title+": الملف غير موجود أو غير مكتمل. أعد استيراد حزمة النموذج.");continue;}
   long reserve=model.getLong("bytes")+450L*1024*1024;if(!memory(reserve)){send(ERROR,title+": الذاكرة الحرة غير كافية الآن؛ أغلق التطبيقات الأخرى أو أوقف النموذج الأكبر.");continue;}
   send(STATUS,"توليد موثّق محلياً — "+title);
   ByteArrayOutputStream out=new ByteArrayOutputStream();final long[] last={0};
   int state;try{state=NativeEngine.generate(f.getAbsolutePath(),Grounding.prompt(q,evidence).getBytes(StandardCharsets.UTF_8),args.getInt("tokens",384),4096,Math.max(1,Math.min(3,Runtime.getRuntime().availableProcessors())),new NativeEngine.Listener(){
    @Override public void onBytes(byte[] bytes){out.write(bytes,0,bytes.length);long now=SystemClock.elapsedRealtime();if(now-last[0]>400){last[0]=now;send(DRAFT,title+"\n"+Grounding.clean(new String(out.toByteArray(),StandardCharsets.UTF_8)));}if(cancelled.get())NativeEngine.cancel();}
    @Override public void onStage(String stage){if(cancelled.get())NativeEngine.cancel();send(STATUS,title+" — "+("loading_model".equals(stage)?"تحميل النموذج من الذاكرة المحلية":"reading_evidence".equals(stage)?"قراءة المقاطع":"صياغة الإجابة"));}
   });}catch(Exception|LinkageError x){send(ERROR,title+": "+friendly(x));continue;}
   check();String text=Grounding.clean(new String(out.toByteArray(),StandardCharsets.UTF_8));String issue=Grounding.validate(text,evidence);
   if(state==2&&issue.isEmpty())issue="توقف التوليد عند حد الطول. الإجابة غير مكتملة؛ اعتمد المقاطع الأصلية.";
   JSONObject answer=new JSONObject().put("model",title).put("accepted",issue.isEmpty()).put("text",issue.isEmpty()?text:issue).put("warning","التحقق الآلي يفحص الإحالات والأرقام فقط، ولا يثبت صحة المعنى أو ملاءمة الإجراء.");
   answers.put(answer);send(ANSWER,answer.toString());
  }
  if(enabled==0){JSONObject answer=new JSONObject().put("model","المصادر المحلية").put("accepted",false).put("text","التوليد متوقف. المقاطع الأصلية أدناه متاحة؛ فعّل نموذجاً من صفحة النماذج للحصول على صياغة جواب.");answers.put(answer);send(ANSWER,answer.toString());}
  store.saveHistory(q,new JSONObject().put("sources",shown).put("answers",answers).toString());
 }
 private void index(Bundle args)throws Exception{
  long scope=args.getLong("doc",-1);int done=0;try(E5 e=new E5(this)){
   while(true){check();JSONArray batch=store.pending(scope,24);if(batch.length()==0)break;for(int i=0;i<batch.length();i++){check();JSONObject p=batch.getJSONObject(i);store.putEmbedding(p.getLong("id"),e.embed(p.getString("text"),false));done++;if(done%4==0)send(STATUS,"فهرسة دلالية محلية — "+done+" مقطعاً. يمكن الإيقاف والاستئناف.");}}
  }send(STATUS,"اكتملت الفهرسة الدلالية: "+done+" مقطعاً في هذه الجولة.");
 }
 @Override public void onDestroy(){cancelled.set(true);if(NativeEngine.AVAILABLE)NativeEngine.cancel();worker.shutdownNow();super.onDestroy();}
}
