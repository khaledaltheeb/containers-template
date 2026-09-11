package org.tms.offline;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.pm.ApplicationInfo;
import android.os.Bundle;
import android.webkit.ConsoleMessage;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
public final class MainActivity extends Activity {
 static final String ORIGIN="https://appassets.androidplatform.net";
 static final String HOME=ORIGIN+"/assets/ui/index.html";
 private WebView web; private LocalPdf pdf; private FrameLayout root; private boolean shuttingDown;
 @Override public void onCreate(Bundle state){super.onCreate(state);root=new FrameLayout(this);root.setBackgroundColor(0xfff7f9fc);root.setFitsSystemWindows(true);setContentView(root);
 try{WebView.setWebContentsDebuggingEnabled((getApplicationInfo().flags&ApplicationInfo.FLAG_DEBUGGABLE)!=0);web=new WebView(this);WebSettings s=web.getSettings();s.setJavaScriptEnabled(true);s.setDomStorageEnabled(true);s.setBlockNetworkLoads(true);s.setAllowFileAccess(false);s.setAllowContentAccess(false);s.setAllowFileAccessFromFileURLs(false);s.setAllowUniversalAccessFromFileURLs(false);s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);s.setSupportMultipleWindows(false);s.setJavaScriptCanOpenWindowsAutomatically(false);s.setMediaPlaybackRequiresUserGesture(true);s.setSupportZoom(false);s.setTextZoom(100);s.setCacheMode(WebSettings.LOAD_NO_CACHE);pdf=new LocalPdf(this,web);web.addJavascriptInterface(pdf,"LocalPdf");web.setWebViewClient(new LockedClient(this));web.setWebChromeClient(new WebChromeClient(){@Override public boolean onConsoleMessage(ConsoleMessage m){if(m.messageLevel()==ConsoleMessage.MessageLevel.ERROR)log("JS: "+m.message());return true;}});web.setBackgroundColor(0xfff7f9fc);root.addView(web,new FrameLayout.LayoutParams(-1,-1));web.loadUrl(HOME);}catch(Throwable error){showFailure(error.toString());}}
 void log(String message){android.util.Log.i("TMSOffline",message);try{File file=new File(getFilesDir(),"diagnostics.txt");try(FileOutputStream out=new FileOutputStream(file,file.length()<32768)){out.write((System.currentTimeMillis()+" "+message.substring(0,Math.min(3000,message.length()))+"\n").getBytes(StandardCharsets.UTF_8));}}catch(Exception ignored){}}
 void showFailure(final String reason){log(reason);runOnUiThread(new Runnable(){public void run(){if(isFinishing()||shuttingDown)return;root.removeAllViews();if(pdf!=null){pdf.close();pdf=null;}if(web!=null){web.removeJavascriptInterface("LocalPdf");web.destroy();web=null;}LinearLayout box=new LinearLayout(MainActivity.this);box.setOrientation(LinearLayout.VERTICAL);int p=(int)(24*getResources().getDisplayMetrics().density);box.setPadding(p,p,p,p);TextView title=new TextView(MainActivity.this);title.setText("TMs Offline\n\u062a\u0639\u0630\u0631 \u0641\u062a\u062d \u0627\u0644\u0648\u0627\u062c\u0647\u0629");title.setTextSize(24);box.addView(title);final String report=LocalPdf.deviceInfo(MainActivity.this)+"\n"+reason;TextView details=new TextView(MainActivity.this);details.setText(report);details.setTextIsSelectable(true);details.setTextSize(15);box.addView(details,new LinearLayout.LayoutParams(-1,0,1));Button copy=new Button(MainActivity.this);copy.setText("\u0646\u0633\u062e \u062a\u0641\u0627\u0635\u064a\u0644 \u0627\u0644\u062e\u0637\u0623");copy.setOnClickListener(v->((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("TMs diagnostics",report)));box.addView(copy);root.addView(box,new FrameLayout.LayoutParams(-1,-1));}});}
 @Override public void onBackPressed(){if(web==null){super.onBackPressed();return;}web.evaluateJavascript("window.TMS_BACK ? window.TMS_BACK() : false",value->{if(!"true".equals(value))MainActivity.super.onBackPressed();});}
 @Override protected void onDestroy(){shuttingDown=true;if(pdf!=null)pdf.close();if(web!=null){web.stopLoading();web.removeJavascriptInterface("LocalPdf");root.removeView(web);web.destroy();web=null;}super.onDestroy();}
}
