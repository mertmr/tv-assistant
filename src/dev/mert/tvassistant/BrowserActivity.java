package dev.mert.tvassistant;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.webkit.*;
import android.widget.*;
import java.lang.ref.WeakReference;
import org.json.*;

public final class BrowserActivity extends Activity {
  static WeakReference<BrowserActivity> current = new WeakReference<>(null);
  WebView web;
  String snapshot = "";
  volatile boolean loading;

  @Override
  public void onCreate(Bundle b) {
    super.onCreate(b);
    current = new WeakReference<>(this);
    LinearLayout layout = new LinearLayout(this);
    layout.setOrientation(1);
    layout.setBackgroundColor(Color.rgb(10, 17, 31));
    LinearLayout bar = new LinearLayout(this);
    Button back = new Button(this);
    back.setText("Back");
    bar.addView(back);
    Button assistant = new Button(this);
    assistant.setText("Assistant");
    bar.addView(assistant);
    TextView url = new TextView(this);
    url.setTextColor(Color.WHITE);
    bar.addView(url, new LinearLayout.LayoutParams(0, -2, 1));
    layout.addView(bar);
    web = new WebView(this);
    layout.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
    setContentView(layout);
    back.setOnClickListener(
        v -> {
          if (web.canGoBack()) web.goBack();
        });
    assistant.setOnClickListener(
        v ->
            startActivity(
                new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)));
    web.getSettings().setJavaScriptEnabled(true);
    web.getSettings().setDomStorageEnabled(true);
    web.getSettings().setAllowFileAccess(false);
    web.getSettings().setAllowContentAccess(false);
    web.getSettings().setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
    web.getSettings().setSupportMultipleWindows(false);
    web.setWebViewClient(
        new WebViewClient() {
          @Override
          public void onPageStarted(WebView v, String u, android.graphics.Bitmap icon) {
            loading = true;
            snapshot = "";
            url.setText(Json.clip(u, 100));
          }

          @Override
          public void onPageFinished(WebView v, String u) {
            loading = false;
          }

          @Override
          public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
            return !allowed(r.getUrl().toString());
          }

          @Override
          public void onReceivedSslError(
              WebView v, android.webkit.SslErrorHandler h, android.net.http.SslError e) {
            h.cancel();
          }
        });
    String u = getIntent().getStringExtra("url");
    if (allowed(u)) web.loadUrl(u);
  }

  static boolean allowed(String url) {
    try {
      Uri u = Uri.parse(url);
      return "https".equals(u.getScheme())
          && u.getHost() != null
          && !u.getHost().equals("auth.openai.com");
    } catch (Exception e) {
      return false;
    }
  }

  @Override
  protected void onDestroy() {
    if (current.get() == this) current.clear();
    web.destroy();
    super.onDestroy();
  }

  static final String DOM =
      "(()=>{let i=0;const nodes=[];for(const e of"
          + " document.querySelectorAll('a,button,input,textarea,select,h1,h2,h3,[role=heading],[role=button],[role=link]')){const"
          + " r=e.getBoundingClientRect();if(!r.width||!r.height||getComputedStyle(e).visibility==='hidden')continue;if(++i>120)break;e.setAttribute('data-tvassistant-id',String(i));nodes.push({id:i,tag:e.tagName,label:(e.innerText||e.getAttribute('aria-label')||e.getAttribute('placeholder')||'').slice(0,180),type:e.type||'',password:e.type==='password',disabled:!!e.disabled,value:e.type==='password'?'[redacted]':String(e.value||'').slice(0,120),href:e.tagName==='A'?e.href:''});}return"
          + " JSON.stringify({url:location.href,title:document.title,text:document.body.innerText.slice(0,9000),nodes});})()";

  void inspect(ValueCallback<String> callback) {
    snapshot = java.util.UUID.randomUUID().toString();
    web.evaluateJavascript(DOM, callback);
  }

  void action(
      String snap,
      int id,
      String operation,
      String text,
      String expectedLabel,
      ValueCallback<String> callback) {
    if (!snapshot.equals(snap)) {
      callback.onReceiveValue("\"stale\"");
      return;
    }
    String code =
        "(()=>{const e=document.querySelector('[data-tvassistant-id=\""
            + id
            + "\"]');if(!e||!e.isConnected)return 'missing';if(e.type==='password')return"
            + " 'password';";
    code +=
        "if(e.disabled)return 'disabled';const"
            + " label=(e.innerText||e.getAttribute('aria-label')||e.getAttribute('placeholder')||'').slice(0,180);if(label!=="
            + JSONObject.quote(expectedLabel)
            + ")return 'stale';";
    if (operation.equals("click"))
      code += "e.scrollIntoView({block:'center'});e.click();return 'clicked';";
    else
      code +=
          "if(e.tagName!=='INPUT'&&e.tagName!=='TEXTAREA')return 'not editable';const"
              + " p=e.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;const"
              + " setter=Object.getOwnPropertyDescriptor(p,'value').set;setter.call(e,"
              + JSONObject.quote(text)
              + ");e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new"
              + " Event('change',{bubbles:true}));return 'typed';";
    web.evaluateJavascript(code + "})()", callback);
  }
}
