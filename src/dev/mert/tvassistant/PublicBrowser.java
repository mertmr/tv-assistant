package dev.mert.tvassistant;

import android.content.Context;
import android.webkit.*;
import android.view.View;
import org.json.*;

/** One isolated public-page view; it never reads the external browser's session. */
final class PublicBrowser {
  final WebView web;
  String snapshot = "";
  volatile boolean loading;
  PublicBrowser(Context context) {
    web = new WebView(context);
    web.getSettings().setJavaScriptEnabled(true);
    web.getSettings().setDomStorageEnabled(true);
    web.getSettings().setAllowFileAccess(false);
    web.getSettings().setAllowContentAccess(false);
    web.getSettings().setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
    web.getSettings().setSupportMultipleWindows(false);
    web.getSettings().setMediaPlaybackRequiresUserGesture(true);
    web.getSettings().setUseWideViewPort(true);
    web.getSettings().setLoadWithOverviewMode(true);
    web.getSettings().setUserAgentString("Mozilla/5.0 (Linux; Android 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36");
    web.measure(View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY));
    web.layout(0, 0, 1280, 720);
    web.setWebViewClient(new WebViewClient() {
      public void onPageStarted(WebView view, String url, android.graphics.Bitmap icon) { loading = true; snapshot = ""; }
      public void onPageFinished(WebView view, String url) {
        view.evaluateJavascript("(()=>{let m=document.querySelector('meta[name=viewport]');if(!m){m=document.createElement('meta');m.name='viewport';document.head.appendChild(m);}m.content='width=1280';})()", null);
        loading = false;
      }
      public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) { return !BrowserActivity.allowed(request.getUrl().toString()); }
      public boolean shouldOverrideUrlLoading(WebView view, String url) { return !BrowserActivity.allowed(url); }
      public void onReceivedSslError(WebView view, SslErrorHandler handler, android.net.http.SslError error) { handler.cancel(); }
    });
  }
  void inspect(ValueCallback<String> callback) {
    snapshot = java.util.UUID.randomUUID().toString();
    web.evaluateJavascript(BrowserActivity.DOM, callback);
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
        "(()=>{try{"
            + BrowserActivity.TYPE_HELPER
            + "const e=document.querySelector('[data-tvassistant-id=\""
            + id
            + "\"]');if(!e||!e.isConnected)return 'missing';if(e.type==='password')return"
            + " 'password';";
    code +=
        "if(e.disabled||e.readOnly)return 'disabled';const label=" + BrowserActivity.LABEL_JS
            + ";if(label!==" + JSONObject.quote(expectedLabel) + ")return 'stale';";
    if (operation.equals("click"))
      code += "e.scrollIntoView({block:'center'});if(typeof e.click==='function')e.click();else e.dispatchEvent(new MouseEvent('click',{bubbles:true,cancelable:true,view:window}));return 'clicked';";
    else
      code +=
          "if(e.tagName!=='INPUT'&&e.tagName!=='TEXTAREA')return 'not editable';const"
              + " p=e.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;const"
              + " setter=Object.getOwnPropertyDescriptor(p,'value').set;setter.call(e,"
              + JSONObject.quote(text)
              + ");typeEvents(e," + JSONObject.quote(text) + ");return 'typed';";
    web.evaluateJavascript(code + "}catch(err){return 'js:'+(err&&err.message||err);}})()", callback);
  }
}
