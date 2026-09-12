package org.tms.offline;
import android.app.*;
import android.graphics.*;
import android.graphics.pdf.PdfRenderer;
import android.os.*;
import android.view.*;
import android.widget.*;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.io.MemoryUsageSetting;
import com.tom_roush.pdfbox.pdmodel.*;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class ReaderActivity extends Activity {
 private Store store;private long pageId,docId;private int pageNo,pageCount;private String query="",pageText="";private TextView title,status;private PageView canvas;private final ExecutorService worker=Executors.newSingleThreadExecutor();private final AtomicInteger request=new AtomicInteger();private final Handler ui=new Handler(Looper.getMainLooper());
 private final int BG=0xff0e1522,FG=0xffedf3fa;
 @Override public void onCreate(Bundle b){super.onCreate(b);store=new Store(this);pageId=getIntent().getLongExtra("page",-1);query=getIntent().getStringExtra("query");if(query==null)query="";
  LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setBackgroundColor(BG);root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);root.setPadding(12,12,12,12);root.setOnApplyWindowInsetsListener((v,insets)->{v.setPadding(12+insets.getSystemWindowInsetLeft(),12+insets.getSystemWindowInsetTop(),12+insets.getSystemWindowInsetRight(),12+insets.getSystemWindowInsetBottom());return insets;});
  title=text("الصفحة الأصلية",17);root.addView(title);status=text("تحميل محلي...",12);root.addView(status);canvas=new PageView();root.addView(canvas,new LinearLayout.LayoutParams(-1,0,1));
  LinearLayout nav=new LinearLayout(this);for(String[] item:new String[][]{{"prev","السابقة"},{"jump","صفحة"},{"next","التالية"},{"text","النص"},{"back","عودة"}}){Button bt=new Button(this);bt.setText(item[1]);bt.setAllCaps(false);bt.setTextSize(12);bt.setOnClickListener(v->{switch(item[0]){case "prev":move(-1);break;case "next":move(1);break;case "jump":jump();break;case "text":showText();break;default:finish();}});nav.addView(bt,new LinearLayout.LayoutParams(0,50*getResources().getDisplayMetrics().densityDpi/160,1));}root.addView(nav);root.addView(text("by sakher altheeb • كبّر بإصبعين • انقر مرتين لإعادة الضبط",11));setContentView(root);load(pageId);
 }
 private TextView text(String s,int size){TextView t=new TextView(this);t.setText(s);t.setTextColor(FG);t.setTextSize(size);t.setPadding(8,8,8,8);return t;}
 private void load(long id){int ticket=request.incrementAndGet();status.setText("جارٍ عرض الصفحة من الملف المحلي...");worker.execute(()->{Bitmap bitmap=null;try{
   JSONObject p=store.page(id);JSONArray boxes=p.getJSONArray("boxes");File file=new File(p.getString("path"));int n=p.getInt("pageno");
   try(ParcelFileDescriptor fd=ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY);PdfRenderer renderer=new PdfRenderer(fd);PdfRenderer.Page page=renderer.openPage(n)){
    double scale=Math.min(2.5,Math.sqrt(4000000.0/(page.getWidth()*(double)page.getHeight())));int width=Math.max(1,(int)Math.round(page.getWidth()*scale)),height=Math.max(1,(int)Math.round(page.getHeight()*scale));bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);bitmap.eraseColor(Color.WHITE);page.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
   }
   if(boxes.length()==0&&!query.isEmpty()){
    try{PDFBoxResourceLoader.init(getApplicationContext());try(PDDocument pdf=PDDocument.load(file,MemoryUsageSetting.setupMixed(16L*1024*1024).setTempDir(getCacheDir()))){Importer.Extract extract=new Importer.Extract();extract.setStartPage(n+1);extract.setEndPage(n+1);extract.getText(pdf);boxes=extract.boxes;PDPage pg=pdf.getPage(n);float w=pg.getCropBox().getWidth(),h=pg.getCropBox().getHeight();if(Math.abs(pg.getRotation())%180!=0){float tmp=w;w=h;h=tmp;}store.updateBoxes(id,boxes,w,h);p.put("width",w).put("height",h);}}catch(Exception ignored){}
   }
   ArrayList<RectF> marked=new ArrayList<>();List<String> terms=Store.terms(query);double w=p.getDouble("width"),h=p.getDouble("height");for(int i=0;i<boxes.length();i++){JSONArray line=boxes.getJSONArray(i);String txt=Store.normalize(line.getString(0));boolean match=false;for(String term:terms)if(txt.contains(term)){match=true;break;}if(match){JSONArray r=line.getJSONArray(1);float x0=(float)(r.getDouble(0)/w),y0=(float)(r.getDouble(1)/h),x1=(float)(r.getDouble(2)/w),y1=(float)(r.getDouble(3)/h);if(x1>=x0&&y1>=y0&&x0>=0&&y0>=0&&x1<=1.03&&y1<=1.03)marked.add(new RectF(x0,y0,Math.min(1,x1),Math.min(1,y1)));}}
   final Bitmap ready=bitmap;bitmap=null;final JSONObject info=p;ui.post(()->{if(ticket!=request.get()||isFinishing()){ready.recycle();return;}pageId=id;docId=info.optLong("doc");pageNo=info.optInt("pageno");pageCount=info.optInt("pages");pageText=info.optString("text");title.setText(info.optString("name")+"\nصفحة PDF "+(pageNo+1)+" / "+pageCount+" • ترقيم المرجع: "+info.optString("label"));status.setText(marked.isEmpty()?"الصفحة الأصلية — راجع السياق والأرقام والتحذيرات.":"تم تمييز كلمات السؤال؛ التمييز ليس إثباتاً لصحة الجواب.");canvas.set(ready,marked);});
  }catch(Throwable x){if(bitmap!=null)bitmap.recycle();ui.post(()->{if(ticket==request.get())status.setText("تعذر عرض المصدر: "+(x.getMessage()==null?x.getClass().getSimpleName():x.getMessage()));});}});}
 private void move(int delta){int n=pageNo+delta;if(n<0||n>=pageCount)return;long id=store.pageId(docId,n);if(id>=0)load(id);}
 private void jump(){EditText input=new EditText(this);input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);input.setText(""+(pageNo+1));new AlertDialog.Builder(this).setTitle("رقم صفحة PDF من 1 إلى "+pageCount).setView(input).setPositiveButton("افتح",(d,w)->{try{int n=Integer.parseInt(input.getText().toString())-1;if(n<0||n>=pageCount)throw new Exception();long id=store.pageId(docId,n);if(id>=0)load(id);}catch(Exception e){Toast.makeText(this,"رقم الصفحة غير صالح",Toast.LENGTH_SHORT).show();}}).setNegativeButton("إلغاء",null).show();}
 private void showText(){ScrollView sc=new ScrollView(this);TextView t=new TextView(this);t.setPadding(24,20,24,20);t.setTextSize(16);t.setTextIsSelectable(true);t.setText(pageText.isEmpty()?"لا يوجد نص قابل للاستخراج في هذه الصفحة؛ راجع الصورة الأصلية.":pageText);sc.addView(t);new AlertDialog.Builder(this).setTitle("النص المستخرج — قد يختلف ترتيب الجداول").setView(sc).setPositiveButton("إغلاق",null).show();}
 @Override protected void onDestroy(){request.incrementAndGet();worker.shutdownNow();canvas.release();super.onDestroy();}
 private final class PageView extends View {
  private Bitmap image;private ArrayList<RectF> marks=new ArrayList<>();private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);private final Paint highlight=new Paint();private float zoom=1,x=0,y=0,lastX,lastY;
  private final ScaleGestureDetector scaler;private final GestureDetector gestures;
  PageView(){super(ReaderActivity.this);setBackgroundColor(0xff293341);highlight.setColor(0x70ffcd38);scaler=new ScaleGestureDetector(ReaderActivity.this,new ScaleGestureDetector.SimpleOnScaleGestureListener(){@Override public boolean onScale(ScaleGestureDetector d){zoom=Math.max(1,Math.min(6,zoom*d.getScaleFactor()));bound();invalidate();return true;}});gestures=new GestureDetector(ReaderActivity.this,new GestureDetector.SimpleOnGestureListener(){@Override public boolean onDown(android.view.MotionEvent e){return true;}@Override public boolean onDoubleTap(android.view.MotionEvent e){zoom=zoom>1?1:2.5f;x=0;y=0;bound();invalidate();return true;}});}
  void set(Bitmap b,ArrayList<RectF> r){if(image!=null&&!image.isRecycled())image.recycle();image=b;marks=r;zoom=1;x=0;y=0;invalidate();}
  void release(){if(image!=null&&!image.isRecycled())image.recycle();image=null;}
  float fit(){if(image==null)return 1;return Math.min(getWidth()/(float)image.getWidth(),getHeight()/(float)image.getHeight());}
  void bound(){if(image==null)return;float w=image.getWidth()*fit()*zoom,h=image.getHeight()*fit()*zoom;x=Math.max(-Math.max(0,(w-getWidth())/2),Math.min(Math.max(0,(w-getWidth())/2),x));y=Math.max(-Math.max(0,(h-getHeight())/2),Math.min(Math.max(0,(h-getHeight())/2),y));}
  @Override protected void onDraw(Canvas c){super.onDraw(c);if(image==null||image.isRecycled())return;float s=fit()*zoom,w=image.getWidth()*s,h=image.getHeight()*s,left=(getWidth()-w)/2+x,top=(getHeight()-h)/2+y;c.drawBitmap(image,null,new RectF(left,top,left+w,top+h),paint);for(RectF r:marks)c.drawRect(left+r.left*w,top+r.top*h,left+r.right*w,top+r.bottom*h,highlight);}
  @Override public boolean onTouchEvent(android.view.MotionEvent e){scaler.onTouchEvent(e);gestures.onTouchEvent(e);if(e.getActionMasked()==android.view.MotionEvent.ACTION_DOWN){lastX=e.getX();lastY=e.getY();}else if(e.getActionMasked()==android.view.MotionEvent.ACTION_MOVE&&!scaler.isInProgress()){x+=e.getX()-lastX;y+=e.getY()-lastY;bound();invalidate();lastX=e.getX();lastY=e.getY();}else if(e.getActionMasked()==android.view.MotionEvent.ACTION_UP)performClick();return true;}
  @Override public boolean performClick(){super.performClick();return true;}
 }
}
