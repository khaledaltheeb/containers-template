package org.tms.offline;
import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.pdf.PdfRenderer;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
public final class LocalPdf implements AutoCloseable {
 private final MainActivity context;private final WebView web;private final ExecutorService work=Executors.newSingleThreadExecutor();private final AtomicInteger latest=new AtomicInteger();private final Set<String> allowed;private volatile boolean closed;
 LocalPdf(MainActivity ctx,WebView view)throws Exception{context=ctx;web=view;allowed=new HashSet<>(Arrays.asList(ctx.getAssets().list("pdf")));}
 public static String deviceInfo(Context context){String version="unknown";try{if(WebView.getCurrentWebViewPackage()!=null)version=WebView.getCurrentWebViewPackage().versionName;}catch(Exception ignored){}ActivityManager am=(ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE);return "TMs Offline 2.0 | Android "+Build.VERSION.RELEASE+" (API "+Build.VERSION.SDK_INT+")\n"+Build.MANUFACTURER+" "+Build.MODEL+" | heap "+am.getMemoryClass()+" MB\nWebView "+version;}
 @JavascriptInterface public String diagnostics(){return deviceInfo(context)+"\nBundled PDFs: "+allowed.size()+"\nNetwork permission: absent";}
 @JavascriptInterface public void logError(String text){context.log(text==null?"JS error":text);}
 private File ensurePdf(String name)throws Exception{if(!allowed.contains(name)||name.contains("/")||name.contains("\\")||name.contains(".."))throw new SecurityException("Not a bundled PDF");File result=new File(context.getCacheDir(),name);if(result.isFile()&&result.length()>0){result.setLastModified(System.currentTimeMillis());return result;}File temp=new File(context.getCacheDir(),name+".part");try{try(InputStream in=context.getAssets().open("pdf/"+name);FileOutputStream out=new FileOutputStream(temp)){byte[] buffer=new byte[65536];int n;while((n=in.read(buffer))!=-1){if(closed)throw new IllegalStateException("Closed");out.write(buffer,0,n);}out.getFD().sync();}if(!temp.renameTo(result))throw new java.io.IOException("Cannot finalize local PDF");trimCache(result);return result;}finally{if(temp.exists())temp.delete();}}
 private void trimCache(File keep){File[] files=context.getCacheDir().listFiles(f->f.getName().endsWith(".pdf"));if(files==null)return;Arrays.sort(files,(a,b)->Long.compare(a.lastModified(),b.lastModified()));long total=0;for(File f:files)total+=f.length();for(File f:files)if(total>128L*1024*1024&&!f.equals(keep)){long n=f.length();if(f.delete())total-=n;}}
 @JavascriptInterface public void renderPageAsync(final String name,final int index,final int desiredWidth,final int request){if(closed)return;latest.set(request);try{work.execute(()->{
  if(closed||latest.get()!=request)return;JSONObject result=new JSONObject();Bitmap bitmap=null;
  try{result.put("request",request);File input=ensurePdf(name);try(ParcelFileDescriptor descriptor=ParcelFileDescriptor.open(input,ParcelFileDescriptor.MODE_READ_ONLY);PdfRenderer renderer=new PdfRenderer(descriptor)){if(index<0||index>=renderer.getPageCount())throw new IllegalArgumentException("Page outside document");try(PdfRenderer.Page page=renderer.openPage(index)){int heap=((ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryClass();int limit=heap<=128?1200:1800;int width=Math.max(320,Math.min(limit,desiredWidth));int height=(int)Math.max(1,(long)page.getHeight()*width/Math.max(1,page.getWidth()));if((long)width*height>4000000L){double k=Math.sqrt(4000000.0/((double)width*height));width=(int)(width*k);height=(int)(height*k);}bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);bitmap.eraseColor(0xffffffff);page.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);if(closed||latest.get()!=request)return;File output=new File(context.getCacheDir(),request+".png");try(FileOutputStream out=new FileOutputStream(output)){if(!bitmap.compress(Bitmap.CompressFormat.PNG,100,out))throw new java.io.IOException("PNG encoding failed");}result.put("url",MainActivity.ORIGIN+"/__pdf/"+request+".png");result.put("width",width);result.put("height",height);result.put("ok",true);}}}catch(Throwable error){context.log("PDF: "+error.toString());try{result.put("request",request);result.put("ok",false);result.put("error",error.toString());}catch(Exception ignored){}}finally{if(bitmap!=null)bitmap.recycle();}
  if(!closed&&latest.get()==request){final String response=result.toString();context.runOnUiThread(()->{if(!closed)web.evaluateJavascript("window.TMS_PDF_RESULT && window.TMS_PDF_RESULT("+response+")",null);});File[] old=context.getCacheDir().listFiles(f->f.getName().matches("[0-9]+\\.png")&&!f.getName().equals(request+".png"));if(old!=null)for(File f:old)if(System.currentTimeMillis()-f.lastModified()>15000)f.delete();}
 });}catch(java.util.concurrent.RejectedExecutionException ignored){}}
 @Override public void close(){closed=true;work.shutdownNow();}
}
