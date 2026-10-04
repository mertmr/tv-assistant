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

  /**
   * One label definition, shared by DOM extraction and the action stale check so an observed node
   * always matches. Whitespace is collapsed because card titles carry newlines that would otherwise
   * never compare equal, and class names are only a last resort.
   */
  static final String LABEL_JS =
      "(e.innerText||e.getAttribute('aria-label')||e.getAttribute('placeholder')"
          + "||e.getAttribute('title')||e.getAttribute('name')||e.getAttribute('class')"
          + "||'').replace(/\\s+/g,' ').trim().slice(0,180)";

  static final String DOM =
      "(()=>{const rows=[];const label=e=>" + LABEL_JS + ";"
          + "for(const e of document.querySelectorAll("
          + "'a,button,input,textarea,select,h1,h2,h3,[role=heading],[role=button],[role=link],[aria-label],svg')){"
          + "const r=e.getBoundingClientRect(),style=getComputedStyle(e);"
          + "if(!r.width||!r.height||style.visibility==='hidden')continue;"
          + "const tag=e.tagName,svg=tag.toLowerCase()==='svg',text=label(e);"
          + "if(svg&&!e.getAttribute('aria-label')&&style.cursor!=='pointer')continue;"
          // Decorative icons outnumber real controls, so rank usable controls and links ahead of
          // everything else and let the node budget fall where the model actually looks.
          + "const rank=svg?2:(text&&(tag==='A'||tag==='BUTTON'||tag==='INPUT'||tag==='TEXTAREA'"
          + "||tag==='SELECT'||e.getAttribute('role')||/^H[1-4]$/.test(tag))?0:1);"
          + "if(rows.length>=600)break;"
          + "rows.push({rank:rank,e:e,tag:tag,text:text,type:svg?'':e.type||'',"
          + "disabled:svg?false:!!e.disabled,value:e.type==='password'?'[redacted]':String(e.value||'').slice(0,120),"
          + "href:tag==='A'?e.href:''});}"
          + "rows.sort((a,b)=>a.rank-b.rank);const nodes=[];"
          // Only non-default fields are emitted: most nodes are plain links, and the omitted
          // booleans and empty values would otherwise dominate every page the model reads.
          + "const node=(id,tag,text,href,type,disabled,value)=>{const o={id:id,tag:tag,label:text};"
          + "if(href)o.href=href;if(type)o.type=type;if(disabled)o.disabled=true;"
          + "if(value==='[redacted]')o.password=true;else if(value)o.value=value;return o;};"
          + "for(const w of rows){if(nodes.length>=120)break;"
          + "const id=nodes.length+1;w.e.setAttribute('data-tvassistant-id',String(id));"
          + "nodes.push(node(id,w.tag,w.text,w.href,w.type,w.disabled,w.value));}"
          + "return JSON.stringify({url:location.href,title:document.title,"
          + "viewport:{width:innerWidth,height:innerHeight},"
          // Card grids repeat a year/rating per tile; collapsing runs of whitespace keeps the same
          // visible words in far fewer tokens. The limit stays generous so wait_text can still
          // verify a heading that sits deep in a long page.
          + "text:document.body.innerText.replace(/[ \\t]+/g,' ').replace(/\\n{2,}/g,'\\n').trim().slice(0,9000),"
          + "nodes});})()";

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
        "(()=>{"
            + TYPE_HELPER
            + "const e=document.querySelector('[data-tvassistant-id=\""
            + id
            + "\"]');if(!e||!e.isConnected)return 'missing';if(e.type==='password')return"
            + " 'password';";
    code +=
        "if(e.disabled)return 'disabled';const label=" + LABEL_JS + ";if(label!=="
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
              + ");typeEvents(e," + JSONObject.quote(text) + ");return 'typed';";
    web.evaluateJavascript(code + "})()", callback);
  }

  /**
   * Types a whole phrase the way a person would. Sites that filter as you type listen for key
   * events, so a bare value change leaves their results panel empty. Declared inside the action so
   * both the public page and the internal browser share one implementation.
   */
  static final String TYPE_HELPER =
      "const typeEvents=(e,text)=>{e.focus();e.dispatchEvent(new Event('input',{bubbles:true}));"
          + "const last=text.slice(-1)||'a';for(const type of ['keydown','keypress','keyup'])"
          + "e.dispatchEvent(new KeyboardEvent(type,{key:last,bubbles:true,cancelable:true}));"
          + "e.dispatchEvent(new Event('change',{bubbles:true}));};";
}
