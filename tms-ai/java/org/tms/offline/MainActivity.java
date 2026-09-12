package org.tms.offline;
import android.app.*;
import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class MainActivity extends Activity {
 private static final int BG=0xff0e1522,PANEL=0xff182437,FG=0xffedf3fa,MUTED=0xffb7c8da,ACCENT=0xff70dac7;
 private static final int PICK_PDF=11,PICK_MODEL=12,PICK_OLD=13;
 private Store store;private LinearLayout body,root,compose;private TextView status,draft;private EditText input;private ProgressBar progress;private Spinner scope;private Messenger engine;private boolean bound,busy;private String tab="chat",lastQuestion="";private JSONArray sources=new JSONArray(),answers=new JSONArray();
 private final ArrayList<Long> scopeIds=new ArrayList<>();private final ExecutorService io=Executors.newSingleThreadExecutor();private final AtomicBoolean cancelImport=new AtomicBoolean();
 private final Handler ui=new Handler(Looper.getMainLooper());
 private final Messenger receiver=new Messenger(new Handler(Looper.getMainLooper()){
  @Override public void handleMessage(Message m){String text=m.getData().getString("text","");try{
   if(m.what==AiService.STATUS){status.setText(text);return;}
   if(m.what==AiService.SOURCES){sources=new JSONArray(text);if(tab.equals("chat"))showChat();return;}
   if(m.what==AiService.DRAFT){if(draft!=null){draft.setText("مسودة أثناء التوليد — لم تُفحص بعد\n"+text);draft.setVisibility(View.VISIBLE);}return;}
   if(m.what==AiService.ANSWER){answers.put(new JSONObject(text));if(tab.equals("chat"))showChat();return;}
   if(m.what==AiService.ERROR){alert("تنبيه محلي",text);return;}
   if(m.what==AiService.DONE){setBusy(false);if(draft!=null)draft.setVisibility(View.GONE);if(!tab.equals("chat"))render();return;}
  }catch(Exception x){status.setText(x.getMessage());setBusy(false);}}
 });
 private final ServiceConnection connection=new ServiceConnection(){
  @Override public void onServiceConnected(ComponentName n,IBinder binder){engine=new Messenger(binder);bound=true;}
  @Override public void onServiceDisconnected(ComponentName n){engine=null;bound=false;setBusy(false);status.setText("توقف محرك الذكاء المحلي. بقيت مكتبتك محفوظة؛ أوقف النموذج الأكبر ثم حاول مجدداً.");}
  @Override public void onBindingDied(ComponentName n){engine=null;bound=false;setBusy(false);try{unbindService(this);}catch(Exception ignored){}bindService(new Intent(MainActivity.this,AiService.class),this,BIND_AUTO_CREATE);}
 };
 @Override public void onCreate(Bundle saved){super.onCreate(saved);getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);store=new Store(this);
  root=new LinearLayout(this);root.setOrientation(1);root.setBackgroundColor(BG);root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);root.setPadding(dp(14),dp(12),dp(14),dp(8));
  root.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets b=insets.getInsets(WindowInsets.Type.systemBars());v.setPadding(dp(14)+b.left,dp(8)+b.top,dp(14)+b.right,dp(8)+b.bottom);return insets;});
  TextView title=text("TMs Local AI",23,FG);title.setTypeface(null,Typeface.BOLD);root.addView(title);TextView by=text("by sakher altheeb",13,ACCENT);by.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);root.addView(by);
  LinearLayout nav=row();for(String[] t:new String[][]{{"chat","المحادثة"},{"library","الملفات"},{"models","النماذج"}}){Button b=button(t[1],()->{if(busy){toast("انتظر اكتمال العملية أو اضغط إيقاف.");return;}tab=t[0];render();});nav.addView(b,new LinearLayout.LayoutParams(0,dp(48),1));}root.addView(nav);
  status=text("محلي بالكامل • لا اتصال بالشبكة",12,MUTED);root.addView(status);progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setIndeterminate(true);progress.setVisibility(View.GONE);root.addView(progress,new LinearLayout.LayoutParams(-1,dp(4)));
  ScrollView sc=new ScrollView(this);sc.setFillViewport(true);body=new LinearLayout(this);body.setOrientation(1);body.setPadding(0,dp(12),0,dp(10));sc.addView(body);root.addView(sc,new LinearLayout.LayoutParams(-1,0,1));
  compose=new LinearLayout(this);compose.setOrientation(1);scope=new Spinner(this);scope.setPopupBackgroundDrawable(new android.graphics.drawable.ColorDrawable(PANEL));compose.addView(scope);
  LinearLayout entry=row();input=new EditText(this);input.setHint("اكتب السؤال أو الكلمات...");input.setTextColor(FG);input.setHintTextColor(MUTED);input.setTextSize(16);input.setMaxLines(4);input.setMinLines(1);input.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);input.setImeOptions(EditorInfo.IME_ACTION_SEND);input.setOnEditorActionListener((v,action,event)->{if(action==EditorInfo.IME_ACTION_SEND){ask();return true;}return false;});entry.addView(input,new LinearLayout.LayoutParams(0,-2,1));entry.addView(button("اسأل",this::ask),new LinearLayout.LayoutParams(dp(78),dp(52)));compose.addView(entry);root.addView(compose);
  LinearLayout foot=row();foot.addView(button("إيقاف",this::stop),new LinearLayout.LayoutParams(0,dp(46),1));foot.addView(button("سجل الأسئلة",this::history),new LinearLayout.LayoutParams(0,dp(46),1));foot.addView(button("مساعدة",this::help),new LinearLayout.LayoutParams(0,dp(46),1));root.addView(foot);
  setContentView(root);bindService(new Intent(this,AiService.class),connection,BIND_AUTO_CREATE);render();
 }
 private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
 private TextView text(String s,int size,int color){TextView v=new TextView(this);v.setText(s);v.setTextSize(size);v.setTextColor(color);v.setPadding(dp(5),dp(5),dp(5),dp(5));v.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);return v;}
 private LinearLayout row(){LinearLayout r=new LinearLayout(this);r.setOrientation(0);r.setGravity(Gravity.CENTER_VERTICAL);return r;}
 private Button button(String label,Runnable action){Button b=new Button(this);b.setText(label);b.setTextSize(13);b.setTextColor(FG);b.setAllCaps(false);b.setBackgroundTintList(ColorStateList.valueOf(PANEL));b.setOnClickListener(v->action.run());return b;}
 private void card(String title,String message){LinearLayout box=new LinearLayout(this);box.setOrientation(1);box.setBackgroundColor(PANEL);box.setPadding(dp(10),dp(9),dp(10),dp(9));TextView t=text(title,17,ACCENT);t.setTypeface(null,Typeface.BOLD);box.addView(t);TextView content=text(message,15,FG);content.setTextIsSelectable(true);box.addView(content);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(10);body.addView(box,p);}
 private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
 private void alert(String title,String message){new AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("حسناً",null).show();}
 private void confirm(String title,String message,Runnable action){new AlertDialog.Builder(this).setTitle(title).setMessage(message).setNegativeButton("إلغاء",null).setPositiveButton("تأكيد",(d,w)->action.run()).show();}
 private void setBusy(boolean value){busy=value;progress.setVisibility(value?View.VISIBLE:View.GONE);input.setEnabled(!value);if(value)getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);}
 private void render(){try{body.removeAllViews();compose.setVisibility(tab.equals("chat")?View.VISIBLE:View.GONE);if(tab.equals("library"))showLibrary();else if(tab.equals("models"))showModels();else showChat();}catch(Exception e){alert("تعذر عرض البيانات",e.getMessage());}}
 private void showChat()throws JSONException{
  body.removeAllViews();refreshScope();if(lastQuestion.isEmpty()){
   card("اسأل ملفاتك","إجابات محلية مرتبطة بصفحاتها الأصلية. فعّل E5 للبحث بالمعنى، وثبّت نموذج توليد من صفحة النماذج لصياغة الجواب.");
   if(store.count("SELECT COUNT(*) FROM docs")==0){body.addView(button("نقل المكتبة من التطبيق السابق",this::importInstalled));body.addView(button("أضف ملفات PDF",()->pick(PICK_PDF,"application/pdf",true)));}
   card("حدود الاعتماد","صياغة النموذج قد تخطئ. افتح الصفحة الكاملة للأرقام والوحدات والجداول والتحذيرات، ولا تتخذ قرار صيانة اعتماداً على جواب مولّد وحده.");
  }else{
   card("سؤالك",lastQuestion);
   for(int i=0;i<answers.length();i++){JSONObject a=answers.getJSONObject(i);card(a.optString("model","الجواب"),a.getString("text")+(a.optBoolean("accepted")?"\n\nفُحصت الإحالات والأرقام آلياً؛ صحة المعنى غير مضمونة.":""));}
   if(sources.length()>0)body.addView(text("المصادر — اضغط لفتح الصفحة",17,ACCENT));
   for(int i=0;i<sources.length();i++){JSONObject h=sources.getJSONObject(i);final long id=h.getLong("page");String label="["+(i+1)+"] "+h.getString("name")+" • صفحة PDF "+(h.getInt("pageno")+1);card(label,h.getString("text")+(h.optBoolean("ocr")?"\nتنبيه: نص آلي أو رموز تحتاج مراجعة الصفحة الأصلية.":""));body.addView(button("افتح الصفحة الأصلية ["+(i+1)+"]",()->openPage(id,lastQuestion)));}
  }
  draft=text("",15,MUTED);draft.setVisibility(View.GONE);body.addView(draft);
 }
 private void refreshScope()throws JSONException{long previous=scope.getSelectedItemPosition()>=0&&scope.getSelectedItemPosition()<scopeIds.size()?scopeIds.get(scope.getSelectedItemPosition()):-1;scopeIds.clear();scopeIds.add(-1L);List<String> labels=new ArrayList<>();labels.add("جميع الملفات المفعّلة");JSONArray d=store.docs();int select=0;for(int i=0;i<d.length();i++){JSONObject v=d.getJSONObject(i);if(v.getBoolean("enabled")){scopeIds.add(v.getLong("id"));labels.add(v.getString("name"));if(v.getLong("id")==previous)select=scopeIds.size()-1;}}ArrayAdapter<String> a=new ArrayAdapter<String>(this,android.R.layout.simple_spinner_item,labels){@Override public View getView(int p,View v,android.view.ViewGroup g){TextView t=(TextView)super.getView(p,v,g);t.setTextColor(MUTED);return t;}@Override public View getDropDownView(int p,View v,android.view.ViewGroup g){TextView t=(TextView)super.getDropDownView(p,v,g);t.setTextColor(FG);return t;}};a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);scope.setAdapter(a);scope.setSelection(select);}
 private void ask(){if(busy){toast("توجد عملية قيد التشغيل.");return;}if(engine==null){toast("محرك المعالجة يبدأ الآن؛ حاول بعد لحظات.");return;}String q=input.getText().toString().trim();if(q.length()<2||q.length()>1500){toast("اكتب سؤالاً بين حرفين و1500 حرف.");return;}try{lastQuestion=q;sources=new JSONArray();answers=new JSONArray();showChat();setBusy(true);Bundle b=new Bundle();b.putString("question",q);int p=scope.getSelectedItemPosition();b.putLong("doc",p>=0&&p<scopeIds.size()?scopeIds.get(p):-1);b.putBoolean("e5",getPreferences(MODE_PRIVATE).getBoolean("e5",true));b.putInt("tokens",384);send(AiService.QUERY,b);}catch(Exception x){setBusy(false);alert("خطأ",x.getMessage());}}
 private void send(int what,Bundle data)throws RemoteException{Message m=Message.obtain(null,what);m.replyTo=receiver;m.setData(data);engine.send(m);}
 private void stop(){cancelImport.set(true);try{if(engine!=null)send(AiService.STOP,new Bundle());}catch(Exception ignored){}status.setText("طلب الإيقاف أُرسل؛ جارٍ إنهاء العملية بأمان...");}
 private void showLibrary()throws Exception{
  card("مكتبة خاصة على هذا الجهاز","الإضافة تنسخ الملف إلى التطبيق. الحذف يزيل النسخة الداخلية وفهرسها فقط، ولا يمس ملفك الأصلي خارج التطبيق. ملفات الصور دون نص تبقى قابلة للقراءة، ولا يوجد OCR محلي في هذه النسخة.");
  body.addView(button("إضافة PDF من الجهاز",()->pick(PICK_PDF,"application/pdf",true)));body.addView(button("نقل مكتبة النسخة السابقة",this::importInstalled));body.addView(button("اختيار ZIP أو APK للنسخة السابقة",()->pick(PICK_OLD,"*/*",false)));body.addView(button("إكمال الفهرسة الدلالية للملفات الجديدة",this::embed));
  JSONArray docs=store.docs();body.addView(text(docs.length()+" ملفاً",17,ACCENT));
  for(int i=0;i<docs.length();i++){JSONObject d=docs.getJSONObject(i);long id=d.getLong("id");card(d.getString("name"),d.getInt("pages")+" صفحة • "+String.format(Locale.ROOT,"%.1f MB",d.getLong("bytes")/1048576.0)+(d.getString("status").equals("scanned")?"\nملف مصوّر دون نص قابل للبحث":"")+(d.getLong("pending")>0?"\n"+d.getLong("pending")+" مقطعاً ينتظر فهرسة E5":""));
   Switch active=new Switch(this);active.setText("تضمين هذا الملف في البحث");active.setTextColor(FG);active.setChecked(d.getBoolean("enabled"));active.setOnCheckedChangeListener((v,on)->store.docEnabled(id,on));body.addView(active);
   LinearLayout actions=row();actions.addView(button("افتح",()->{long page=store.pageId(id,0);if(page>=0)openPage(page,"");}),new LinearLayout.LayoutParams(0,dp(48),1));actions.addView(button("حذف",()->confirm("حذف النسخة الداخلية؟","سيُحذف PDF وفهرسه من هذا التطبيق فقط. النسخة الموجودة في جهازك أو التطبيق السابق لن تتغير.",()->runImport(()->{store.removeDoc(id);return "تم حذف النسخة الداخلية والفهرس.";}))),new LinearLayout.LayoutParams(0,dp(48),1));body.addView(actions);
  }
 }
 private void showModels()throws Exception{
  card("نماذج محلية مستقلة","يمكن تشغيل E5 مع نموذج توليد واحد أو أكثر. عند تفعيل نموذجين للتوليد يعملان بالتتابع، ويعرض كل منهما جواباً مستقلاً من المصادر نفسها، لا تصويتاً يضمن الصحة. جميع المعالجة على الجهاز.");
  Switch e5=new Switch(this);e5.setText("E5 • البحث بالمعنى • تشغيل / إيقاف");e5.setTextColor(FG);e5.setChecked(getPreferences(MODE_PRIVATE).getBoolean("e5",true));e5.setOnCheckedChangeListener((b,on)->getPreferences(MODE_PRIVATE).edit().putBoolean("e5",on).apply());body.addView(e5);card("multilingual-e5-small • MIT","مضمن داخل التطبيق. يدعم العربية والإنجليزية لاسترجاع المقاطع، ولا يولّد نصاً. لا يتم تنزيله عند التشغيل.");
  body.addView(button("استيراد حزم النماذج ZIP من الجهاز",()->pick(PICK_MODEL,"application/zip",true)));
  JSONArray catalog=new JSONArray(Store.read(getAssets().open("model-catalog.json"),128*1024)),installed=store.models();Map<String,JSONObject> local=new HashMap<>();for(int i=0;i<installed.length();i++)local.put(installed.getJSONObject(i).getString("id"),installed.getJSONObject(i));
  for(int i=0;i<catalog.length();i++){JSONObject c=catalog.getJSONObject(i);String id=c.getString("id");JSONObject m=local.get(id);card(c.getString("title"),"Apache-2.0 • توليد جواب محلي\n"+String.format(Locale.ROOT,"%.0f MB",c.getLong("bytes")/1048576.0)+" • "+c.getInt("parts")+" جزء/أجزاء للاستيراد\n"+(m==null?"غير مثبت — أضف حزمة النموذج المحلية.":"مثبت — تشغيله لا يحتاج إنترنت."));Switch toggle=new Switch(this);toggle.setText("تشغيل / إيقاف هذا النموذج");toggle.setTextColor(FG);toggle.setEnabled(m!=null&&NativeEngine.AVAILABLE);toggle.setChecked(m!=null&&m.getBoolean("enabled"));toggle.setOnCheckedChangeListener((b,on)->store.modelEnabled(id,on));body.addView(toggle);if(m!=null)body.addView(button("حذف هذا النموذج",()->confirm("حذف النموذج؟","سيبقى البحث وملفات PDF محفوظة، ويمكنك إعادة استيراد النموذج لاحقاً.",()->runImport(()->{store.removeModel(id);return "تم حذف النموذج.";}))));}
  card("الذاكرة والدقة","ابدأ بالنموذج 0.6B. النموذج 1.7B أكبر وأكثر استهلاكاً، وقد يكون أبطأ. كلاهما نموذج عام وليس خبير صيانة معتمداً. صِغ أسئلة محددة وراجع المصدر. إذا لم تكفِ الذاكرة يبقى البحث متاحاً.");
  if(!NativeEngine.AVAILABLE)card("حالة المحرك",NativeEngine.ERROR);
 }
 private void pick(int request,String mime,boolean multiple){if(busy){toast("أوقف العملية الحالية أولاً.");return;}Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType(mime);i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,multiple);i.putExtra(Intent.EXTRA_LOCAL_ONLY,true);try{startActivityForResult(i,request);}catch(Exception e){alert("مدير الملفات",e.getMessage());}}
 @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(result!=RESULT_OK||data==null)return;List<Uri> files=new ArrayList<>();if(data.getClipData()!=null){for(int i=0;i<data.getClipData().getItemCount();i++)files.add(data.getClipData().getItemAt(i).getUri());}else if(data.getData()!=null)files.add(data.getData());runImport(()->{StringBuilder report=new StringBuilder();Importer importer=new Importer(this,store,cancelImport,s->ui.post(()->status.setText(s)));for(Uri u:files){if(cancelImport.get())break;try{if(request==PICK_PDF){importer.pdf(u);report.append("أضيف PDF: ").append(importer.name(u)).append('\n');}else if(request==PICK_MODEL)report.append(importer.model(u)).append('\n');else if(request==PICK_OLD){importer.legacyUri(u);report.append("نُقلت المكتبة الأصلية دون تعديل ملفاتها.\n");}}catch(Exception e){report.append(importer.name(u)).append(": ").append(e.getMessage()).append('\n');}}return report.toString();});}
 private interface Work{String run()throws Exception;}
 private void runImport(Work task){if(busy){toast("أوقف العملية الحالية أولاً.");return;}cancelImport.set(false);setBusy(true);status.setText("جارٍ العمل محلياً...");io.execute(()->{String message;try{message=task.run();}catch(Throwable e){message=e instanceof OutOfMemoryError?"الذاكرة غير كافية. لم تُعدّل الملفات الأصلية.":e.getMessage();}final String done=message;ui.post(()->{setBusy(false);status.setText("اكتملت العملية المحلية");render();alert("النتيجة",done);});});}
 private void importInstalled(){confirm("نقل المكتبة محلياً","سيُنسخ محتوى تطبيق TMs القديم إلى هذه النسخة دون حذفه أو إرساله لأي جهة. اترك التطبيق مفتوحاً أثناء النقل، مع مساحة حرة لا تقل عن 1 GB.",()->runImport(()->{new Importer(this,store,cancelImport,s->ui.post(()->status.setText(s))).legacyInstalled();return "تم نقل مكتبة النسخة السابقة مع التحقق من بصمات ملفات PDF.";}));}
 private void embed(){if(busy||engine==null){toast("انتظر جاهزية المحرك.");return;}try{setBusy(true);Bundle b=new Bundle();b.putLong("doc",-1);send(AiService.EMBED,b);}catch(Exception e){setBusy(false);alert("الفهرسة",e.getMessage());}}
 private void openPage(long id,String q){Intent i=new Intent(this,ReaderActivity.class);i.putExtra("page",id);i.putExtra("query",q);startActivity(i);}
 private void history(){if(busy)return;try{JSONArray h=store.history();String[] titles=new String[h.length()];for(int i=0;i<h.length();i++)titles[i]=h.getJSONObject(i).getString("question");new AlertDialog.Builder(this).setTitle("سجل محلي — آخر 100 سؤال").setItems(titles,(d,w)->{try{JSONObject v=h.getJSONObject(w),p=new JSONObject(v.getString("payload"));lastQuestion=v.getString("question");sources=p.getJSONArray("sources");answers=p.getJSONArray("answers");tab="chat";render();}catch(Exception e){alert("السجل",e.getMessage());}}).setNeutralButton("مسح السجل",(d,w)->{store.getWritableDatabase().delete("history",null,null);toast("تم مسح سجل الأسئلة المحلي.");}).setNegativeButton("إغلاق",null).show();}catch(Exception e){alert("السجل",e.getMessage());}}
 private void help(){alert("TMs Local AI • by sakher altheeb","1. انقل مكتبتك من التطبيق السابق أو أضف PDF محلياً.\n2. E5 مضمّن للبحث بالمعنى. للملفات الجديدة شغّل الفهرسة الدلالية من الملفات.\n3. أضف ZIP النموذج الصغير، أو جميع أجزاء النموذج الأكبر، ثم فعّل مفاتيحه.\n4. اكتب السؤال وافتح مصدر الجواب.\n\nلا إنترنت ولا حسابات ولا إرسال بيانات. لا OCR للملفات المصوّرة. لا يُضمن خلو التوليد من الخطأ. فحص الإحالات والأرقام لا يثبت صحة المعنى.\n\nهذه نسخة مستقلة؛ التطبيق السابق وملفاته لا يتغيران. لا تحذفهما قبل نقل المكتبة والتحقق منها.");}
 @Override protected void onDestroy(){cancelImport.set(true);try{if(bound)unbindService(connection);}catch(Exception ignored){}io.shutdownNow();super.onDestroy();}
}
