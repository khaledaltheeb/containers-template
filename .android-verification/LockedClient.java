package org.tms.offline;
import android.net.Uri;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
public final class LockedClient extends WebViewClient {
 private final MainActivity activity;
 LockedClient(MainActivity value){activity=value;}
 static boolean local(Uri u){return "https".equals(u.getScheme())&&"appassets.androidplatform.net".equals(u.getHost())&&u.getUserInfo()==null&&(u.getPort()==-1||u.getPort()==443);}
 private static WebResourceResponse fail(int status,String text){return new WebResourceResponse("text/plain","utf-8",status,text,new HashMap<String,String>(),new ByteArrayInputStream(text.getBytes()));}
 private WebResourceResponse serve(Uri uri){
  if(!local(uri))return fail(403,"External access blocked");String path=uri.getPath();if(path==null||path.contains("..")||path.indexOf('\\')>=0||path.indexOf('\0')>=0)return fail(403,"Invalid path");
  try{InputStream stream;if(path.startsWith("/assets/"))stream=activity.getAssets().open(path.substring(8));else if(path.matches("/__pdf/[0-9]+\\.png"))stream=new FileInputStream(new File(activity.getCacheDir(),path.substring(7)));else return fail(404,"Not found");String mime="application/octet-stream";if(path.endsWith(".html"))mime="text/html";else if(path.endsWith(".js")||path.endsWith(".mjs"))mime="application/javascript";else if(path.endsWith(".css"))mime="text/css";else if(path.endsWith(".json"))mime="application/json";else if(path.endsWith(".wasm"))mime="application/wasm";else if(path.endsWith(".png"))mime="image/png";else if(path.endsWith(".svg"))mime="image/svg+xml";Map<String,String> headers=new HashMap<>();headers.put("Cache-Control","no-store");headers.put("X-Content-Type-Options","nosniff");headers.put("Cross-Origin-Resource-Policy","same-origin");return new WebResourceResponse(mime,"utf-8",200,"OK",headers,stream);}catch(Exception e){activity.log("Asset unavailable: "+path);return fail(404,"Asset not found");}
 }
 @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request){return "GET".equals(request.getMethod())?serve(request.getUrl()):fail(405,"Read only");}
 @Override public WebResourceResponse shouldInterceptRequest(WebView view,String url){return serve(Uri.parse(url));}
 @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){Uri u=request.getUrl();return !local(u)||!"/assets/ui/index.html".equals(u.getPath());}
 @Override public boolean shouldOverrideUrlLoading(WebView view,String url){Uri u=Uri.parse(url);return !local(u)||!"/assets/ui/index.html".equals(u.getPath());}
 @Override public void onReceivedError(WebView view,WebResourceRequest request,WebResourceError error){if(request.isForMainFrame())activity.showFailure("WebView "+error.getErrorCode()+": "+error.getDescription());}
 @Override public boolean onRenderProcessGone(WebView view,RenderProcessGoneDetail detail){activity.showFailure("WebView renderer stopped. Low memory: "+!detail.didCrash());return true;}
}
