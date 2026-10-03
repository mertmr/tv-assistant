package dev.mert.tvassistant;

import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.speech.tts.TextToSpeech;
import android.text.InputType;
import android.view.*;
import android.view.inputmethod.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

public final class MainActivity extends Activity
    implements Tools.Host, AssistantEngine.Listener, MicrophoneProbe.Listener {
  // Set by NavigationService when the remote's Alexa button launches the assistant.
  static volatile boolean voiceButtonRequest;

  private final Handler handler = new Handler(Looper.getMainLooper());
  private EditText command;
  private TextView answer, state, account, trace;
  private ScrollView traceScroll;
  private Tools tools;
  private ChatAuth auth;
  private AssistantEngine engine;
  private SharedPreferences prefs;
  private TextToSpeech tts;
  private boolean ttsReady, initiallyOpened, pendingKeyboard, pendingMicTest;
  private MicrophoneProbe micProbe;
  private final StringBuilder log = new StringBuilder();
  private String lastCommand = "";
  private JSONArray history = new JSONArray();

  @Override
  public void onCreate(Bundle b) {
    super.onCreate(b);
    prefs = getSharedPreferences("assistant", 0);
    try {
      history = new JSONArray(prefs.getString("history", "[]"));
    } catch (Exception ignored) {
    }
    try {
      auth = new ChatAuth(this);
    } catch (Exception e) {
      auth = null;
    }
    tools = new Tools(this, this);
    engine = new AssistantEngine(this, tools, auth, this);
    tts =
        new TextToSpeech(
            this,
            status -> {
              ttsReady = status == TextToSpeech.SUCCESS;
            });
    LinearLayout page = new LinearLayout(this);
    page.setOrientation(1);
    page.setPadding(dp(34), dp(20), dp(34), dp(20));
    page.setBackgroundColor(Color.rgb(10, 17, 31));
    setContentView(page);
    LinearLayout header = new LinearLayout(this);
    header.setBaselineAligned(false);
    TextView title = text("TV Assistant", 29, Color.WHITE);
    title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
    account = text("", 13, Color.rgb(155, 179, 202));
    header.addView(account);
    page.addView(header);
    TextView subtitle =
        text(
            "Use the keyboard mic to dictate, then choose Next or Search. Mic test checks"
                + " raw audio access locally; it does not transcribe.",
            15,
            Color.rgb(174, 197, 218));
    subtitle.setPadding(0, dp(5), 0, dp(10));
    page.addView(subtitle);
    command = new EditText(this);
    command.setId(View.generateViewId());
    command.setSingleLine(true);
    command.setInputType(InputType.TYPE_CLASS_TEXT);
    command.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
    command.setTextColor(Color.WHITE);
    command.setHintTextColor(Color.rgb(149, 171, 194));
    command.setTextSize(19);
    command.setHint("Open Stremio and find The Wire");
    command.setPadding(dp(14), dp(8), dp(14), dp(8));
    command.setBackground(panel(Color.rgb(25, 40, 62), Color.rgb(70, 126, 159)));
    page.addView(command, new LinearLayout.LayoutParams(-1, dp(52)));
    LinearLayout controls = row(page);
    button("Mic test", controls, () -> microphoneTest());
    button("Speak", controls, () -> keyboard());
    button("Run", controls, () -> submit(false));
    button("Stop", controls, () -> stopAll());
    button("Settings", controls, () -> settings());
    button("Tools", controls, () -> toolList());
    button("Apps", controls, () -> appList());
    LinearLayout content = new LinearLayout(this);
    content.setOrientation(0);
    LinearLayout.LayoutParams contentParams = new LinearLayout.LayoutParams(-1, 0, 1);
    contentParams.topMargin = dp(8);
    page.addView(content, contentParams);
    LinearLayout left = new LinearLayout(this);
    left.setOrientation(1);
    left.setPadding(dp(14), dp(8), dp(14), dp(10));
    left.setBackground(panel(Color.rgb(18, 29, 46), Color.rgb(42, 60, 82)));
    LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, -1, 1.2f);
    half.rightMargin = dp(12);
    content.addView(left, half);
    left.addView(text("Assistant", 17, Color.rgb(125, 211, 252)));
    ScrollView answerScroll = new ScrollView(this);
    answer =
        text(
            "Ready for your command. When ChatGPT is connected, AI interprets your request and"
                + " chooses tools. Connect in Settings; basic commands also work offline.",
            16,
            Color.WHITE);
    answer.setPadding(0, dp(10), 0, 0);
    answerScroll.addView(answer);
    left.addView(answerScroll, new LinearLayout.LayoutParams(-1, 0, 1));
    LinearLayout right = new LinearLayout(this);
    right.setOrientation(1);
    right.setPadding(dp(14), dp(8), dp(14), dp(10));
    right.setBackground(panel(Color.rgb(18, 29, 46), Color.rgb(42, 60, 82)));
    content.addView(right, new LinearLayout.LayoutParams(0, -1, 1));
    right.addView(text("Actions", 17, Color.rgb(125, 211, 252)));
    traceScroll = new ScrollView(this);
    trace = text("Actions will appear here.", 13, Color.rgb(180, 200, 219));
    trace.setPadding(0, dp(8), 0, 0);
    traceScroll.addView(trace);
    right.addView(traceScroll, new LinearLayout.LayoutParams(-1, 0, 1));
    LinearLayout quick = row(page);
    button(
        "Find a show",
        quick,
        () -> {
          command.setText("Search The Wire in Stremio");
          submit(false);
        });
    button(
        "Browse web",
        quick,
        () -> {
          command.setText("Search web for today's science news");
          submit(false);
        });
    button(
        "Device info",
        quick,
        () -> {
          command.setText("Show device info");
          submit(false);
        });
    button("History", quick, () -> showHistory());
    button(
        "Clear",
        quick,
        () -> {
          if (!engine.busy()) {
            command.setText("");
            answer.setText("Ready for your command.");
            log.setLength(0);
            trace.setText("Actions will appear here.");
            engine.clear();
          }
        });
    state = text("Ready", 13, Color.rgb(146, 170, 193));
    state.setPadding(0, dp(5), 0, 0);
    page.addView(state);
    command.setOnEditorActionListener(
        (v, action, event) -> {
          boolean enter = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER;
          if (action == EditorInfo.IME_ACTION_SEARCH
              || action == EditorInfo.IME_ACTION_NEXT
              || action == EditorInfo.IME_ACTION_DONE
              || action == EditorInfo.IME_ACTION_GO
              || enter) {
            if (!enter || event.getAction() == KeyEvent.ACTION_UP) submit(false);
            return true;
          }
          return false;
        });
    command.requestFocus();
    updateAccount();
  }

  @Override
  public void onWindowFocusChanged(boolean focused) {
    super.onWindowFocusChanged(focused);
    if (focused && command != null) {
      if (pendingMicTest) {
        pendingMicTest = false;
        pendingKeyboard = false;
        initiallyOpened = true;
        startMicrophoneTest();
      } else if (pendingKeyboard || !initiallyOpened) {
        initiallyOpened = true;
        pendingKeyboard = false;
        command.post(() -> keyboard());
      }
    }
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    if (intent != null && intent.getBooleanExtra(NavigationService.PREF_VOICE_BUTTON, false))
      showKeyboard();
  }

  @Override
  protected void onResume() {
    super.onResume();
    if (account != null) updateAccount();
    if (voiceButtonRequest) {
      voiceButtonRequest = false;
      showKeyboard();
    }
  }

  private void showKeyboard() {
    if (command == null) return;
    if (hasWindowFocus()) command.post(() -> keyboard());
    else pendingKeyboard = true;
  }

  private void keyboard() {
    command.requestFocus();
    command.setSelection(command.length());
    ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(command, 0);
  }

  private void microphoneTest() {
    if (micProbe != null && micProbe.active()) {
      micProbe.cancel();
      state.setText("Microphone test stopped");
      return;
    }
    if (engine.busy()) {
      state("Stop the running task before testing the microphone");
      return;
    }
    new AlertDialog.Builder(this)
        .setTitle("Local microphone test")
        .setMessage(
            "ChatGPT plan-sharing does not include OpenAI transcription. This test checks raw"
                + " microphone access for 8 seconds. Only levels are measured; audio is not saved"
                + " or sent anywhere. Remote microphone access is unverified. Holding Alexa may"
                + " open its system UI and interrupt the test. The test stops when you leave the"
                + " app. Bluetooth SCO is optional in Settings and may temporarily change audio"
                + " routing.")
        .setPositiveButton("Test", (d, w) -> {
          if (!MicrophoneProbe.permitted(this))
            requestPermissions(new String[] {"android.permission.RECORD_AUDIO"}, 903);
          else queueMicrophoneTest();
        })
        .setNegativeButton("Cancel", null)
        .show();
  }

  private void queueMicrophoneTest() {
    pendingMicTest = true;
    command.post(() -> {
      if (pendingMicTest && hasWindowFocus()) {
        pendingMicTest = false;
        startMicrophoneTest();
      }
    });
  }

  private void startMicrophoneTest() {
    if (isFinishing() || !hasWindowFocus() || engine.busy()) return;
    ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
        .hideSoftInputFromWindow(command.getWindowToken(), 0);
    if (micProbe == null) micProbe = new MicrophoneProbe(this, handler, this);
    state.setText("Starting local microphone test…");
    micProbe.start(prefs.getInt(MicrophoneProbe.PREF_SOURCE, 0),
        prefs.getBoolean(MicrophoneProbe.PREF_SCO, false));
  }

  @Override
  public void onRequestPermissionsResult(int request, String[] names, int[] grants) {
    super.onRequestPermissionsResult(request, names, grants);
    if (request != 903) return;
    if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED)
      queueMicrophoneTest();
    else state("Microphone permission was denied; keyboard dictation remains available");
  }

  @Override
  public void microphoneState(String value) { state.setText(value); }

  @Override
  public void microphoneResult(String report) {
    state.setText("Local microphone test complete — no transcription attempted");
    answer.setText(report);
  }

  @Override
  public void microphoneError(String message) {
    state.setText("Microphone test unavailable");
    answer.setText(message);
  }

  @Override
  protected void onPause() {
    pendingMicTest = false;
    if (micProbe != null && micProbe.active()) {
      micProbe.cancel();
      state.setText("Microphone test stopped when the app lost focus");
    }
    super.onPause();
  }

  private void stopAll() {
    pendingMicTest = false;
    engine.stop();
    if (micProbe != null && micProbe.active()) {
      micProbe.cancel();
      state.setText("Microphone test stopped");
    }
  }

  private void submit(boolean force) {
    String text = command.getText().toString().trim();
    if (text.isEmpty()) {
      state("Say or type a command first");
      return;
    }
    if (micProbe != null && micProbe.active()) {
      state("Stop the microphone test before running a command");
      return;
    }
    if (engine.busy()) {
      state("A task is running. Use Stop before starting another.");
      return;
    }
    lastCommand = text;
    ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
        .hideSoftInputFromWindow(command.getWindowToken(), 0);
    answer.setText("Working on: " + text);
    log.setLength(0);
    trace.setText("Understanding command…");
    engine.run(text, force);
  }

  @Override
  public <T> T ui(Callable<T> task) throws Exception {
    if (Looper.myLooper() == Looper.getMainLooper()) return task.call();
    FutureTask<T> future = new FutureTask<>(task);
    handler.post(future);
    try {
      return future.get(20, TimeUnit.SECONDS);
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof Exception) throw (Exception) cause;
      throw e;
    }
  }

  @Override
  public boolean approve(String description) throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    boolean[] result = {false};
    handler.post(
        () -> {
          startActivity(
              new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
          handler.postDelayed(
              () -> {
                if (isFinishing()) {
                  latch.countDown();
                  return;
                }
                new AlertDialog.Builder(this)
                    .setTitle("Confirm action")
                    .setMessage(description)
                    .setPositiveButton(
                        "Allow",
                        (d, w) -> {
                          result[0] = true;
                          latch.countDown();
                        })
                    .setNegativeButton("Cancel", (d, w) -> latch.countDown())
                    .setOnCancelListener(d -> latch.countDown())
                    .show();
              },
              350);
        });
    while (!latch.await(250, TimeUnit.MILLISECONDS)) {
      if (engine.cancelled()) return false;
    }
    return result[0];
  }

  @Override
  public void trace(String value) {
    handler.post(
        () -> {
          if (log.length() > 13000) log.delete(0, 4000);
          log.append(value).append("\n\n");
          trace.setText(log.toString());
          traceScroll.post(() -> traceScroll.fullScroll(View.FOCUS_DOWN));
        });
  }

  @Override
  public boolean cancelled() {
    return engine != null && engine.cancelled();
  }

  @Override
  public void state(String value) {
    handler.post(() -> state.setText(value));
  }

  @Override
  public void answer(String value) {
    handler.post(
        () -> {
          answer.setText(value);
          history.put(
              Json.obj(
                  "command",
                  lastCommand,
                  "answer",
                  Json.clip(value, 3000),
                  "at",
                  System.currentTimeMillis()));
          while (history.length() > 20) history.remove(0);
          prefs.edit().putString("history", history.toString()).apply();
          if (prefs.getBoolean("speech", false)) speak(value);
        });
  }

  @Override
  public void finished() {
    handler.post(() -> updateAccount());
  }

  @Override
  public void speak(String value) {
    handler.post(
        () -> {
          if (prefs.getBoolean("speech", false) && ttsReady)
            tts.speak(Json.clip(value, 600), TextToSpeech.QUEUE_FLUSH, null, "assistant");
          else trace("Speech output is disabled or no TTS engine is available.");
        });
  }

  private void updateAccount() {
    JSONObject a = auth == null ? null : auth.active();
    String email = a == null ? "Local commands" : a.optString("email", "ChatGPT account");
    account.setText(
        email
            + (a != null && ChatAuth.hasPlan(a) ? " · ChatGPT" : "")
            + "\n"
            + tools.names().size()
            + " tools · navigation "
            + (NavigationService.instance == null ? "off" : "on"));
  }

  private void settings() {
    String[] choices = {
      "Continue with ChatGPT",
      "Add ChatGPT account",
      "Choose saved account",
      "Choose AI model",
      "Manage ChatGPT usage",
      "AI request limit",
      "Tool step limit",
      "Local-only mode: " + (prefs.getBoolean("local_only", false) ? "on" : "off"),
      "Spoken answers: " + (prefs.getBoolean("speech", false) ? "on" : "off"),
      "Enable screen navigation",
      "Enable playback control",
      "Run diagnostics",
      "Clear local history",
      "Sign out",
      CaptureService.instance == null ? "Enable screen vision" : "Stop screen vision",
      "Alexa button opens TV Assistant: "
          + (prefs.getBoolean(NavigationService.PREF_VOICE_BUTTON, true) ? "on" : "off"),
      "Microphone test source: "
          + MicrophoneProbe.sourceName(prefs.getInt(MicrophoneProbe.PREF_SOURCE, 0)),
      "Bluetooth SCO for microphone test: "
          + (prefs.getBoolean(MicrophoneProbe.PREF_SCO, false) ? "on" : "off")
    };
    new AlertDialog.Builder(this)
        .setTitle("Settings")
        .setItems(
            choices,
            (d, index) -> {
              switch (index) {
                case 0:
                  login(false);
                  break;
                case 1:
                  login(true);
                  break;
                case 2:
                  accountPicker();
                  break;
                case 3:
                  models();
                  break;
                case 4:
                  openUsage();
                  break;
                case 5:
                  limits(
                      "max_rounds",
                      new int[] {1, 2, 4, 6, 8, 12},
                      "Maximum AI requests per command");
                  break;
                case 6:
                  limits("max_tools", new int[] {4, 8, 10, 16}, "Maximum tool steps per command");
                  break;
                case 7:
                  prefs
                      .edit()
                      .putBoolean("local_only", !prefs.getBoolean("local_only", false))
                      .apply();
                  settings();
                  break;
                case 8:
                  prefs.edit().putBoolean("speech", !prefs.getBoolean("speech", false)).apply();
                  settings();
                  break;
                case 9:
                  permission(
                      "Screen navigation",
                      "This optional service can read visible app labels and click or type when you"
                          + " give a command. During AI tasks, relevant screen text goes to your"
                          + " selected model. Password fields are excluded. Enable TV Assistant"
                          + " navigation in the next screen.",
                      "accessibility");
                  break;
                case 10:
                  permission(
                      "Playback control",
                      "This optional permission gives TV Assistant access to active media sessions"
                          + " through Android notification access. Enable TV Assistant playback in"
                          + " the next screen. Supported players can then be paused, resumed or"
                          + " seeked.",
                      "playback");
                  break;
                case 11:
                  diagnostics();
                  break;
                case 12:
                  new AlertDialog.Builder(this)
                      .setTitle("Clear local history?")
                      .setMessage("Removes saved commands and answers on this TV.")
                      .setPositiveButton(
                          "Clear",
                          (x, w) -> {
                            history = new JSONArray();
                            prefs.edit().remove("history").apply();
                            engine.clear();
                          })
                      .setNegativeButton("Cancel", null)
                      .show();
                  break;
                case 13:
                  if (auth != null)
                    new Thread(
                            () -> {
                              try {
                                String result = auth.signOut();
                                handler.post(
                                    () -> {
                                      answer.setText(result);
                                      updateAccount();
                                    });
                              } catch (Exception e) {
                                state(ChatAuth.safe(e));
                              }
                            })
                        .start();
                  break;
                case 14:
                  if (CaptureService.instance != null) {
                    stopService(new Intent(this, CaptureService.class));
                    state("Screen vision stopped");
                  } else {
                    new AlertDialog.Builder(this)
                        .setTitle("Enable screen vision?")
                        .setMessage(
                            "Allows AI to see screenshots of the TV during your tasks, including"
                                + " visible account or page details. Images stay in memory and are"
                                + " sent to your chosen model only when requested. You can stop"
                                + " this session in Settings. Android will ask for screen-capture"
                                + " consent next.")
                        .setPositiveButton(
                            "Continue",
                            (x, w) -> {
                              try {
                                startActivityForResult(
                                    ((android.media.projection.MediaProjectionManager)
                                            getSystemService(MEDIA_PROJECTION_SERVICE))
                                        .createScreenCaptureIntent(),
                                    902);
                              } catch (Exception e) {
                                answer.setText(
                                    "Screen capture is unavailable on this device: "
                                        + ChatAuth.safe(e));
                              }
                            })
                        .setNegativeButton("Cancel", null)
                        .show();
                  }
                  break;
                case 15:
                  prefs
                      .edit()
                      .putBoolean(
                          NavigationService.PREF_VOICE_BUTTON,
                          !prefs.getBoolean(NavigationService.PREF_VOICE_BUTTON, true))
                      .apply();
                  settings();
                  break;
                case 16:
                  prefs.edit().putInt(MicrophoneProbe.PREF_SOURCE,
                      MicrophoneProbe.nextSource(prefs.getInt(MicrophoneProbe.PREF_SOURCE, 0))).apply();
                  settings();
                  break;
                case 17:
                  prefs.edit().putBoolean(MicrophoneProbe.PREF_SCO,
                      !prefs.getBoolean(MicrophoneProbe.PREF_SCO, false)).apply();
                  settings();
                  break;
              }
            })
        .show();
  }

  @Override
  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (request != 902) return;
    if (result != RESULT_OK || data == null) {
      state("Screen vision was not enabled");
      return;
    }
    Intent service =
        new Intent(this, CaptureService.class).putExtra("result", result).putExtra("grant", data);
    if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(service);
    else startService(service);
    handler.postDelayed(
        () ->
            answer.setText(
                CaptureService.instance != null
                    ? "Screen vision enabled. AI can now inspect the TV during your tasks."
                    : "Screen vision could not start. " + CaptureService.lastError),
        1200);
  }

  private void permission(String title, String message, String panel) {
    new AlertDialog.Builder(this)
        .setTitle(title)
        .setMessage(message)
        .setPositiveButton(
            "Open settings",
            (d, w) ->
                new Thread(
                        () -> {
                          JSONObject r = tools.execute("open_settings", Json.obj("panel", panel));
                          if (r.has("error")) state(r.optString("error"));
                        })
                    .start())
        .setNegativeButton("Later", null)
        .show();
  }

  private void login(boolean add) {
    if (auth == null) {
      answer.setText("Secure account storage could not initialize on this device.");
      return;
    }
    if (engine.busy()) {
      state("Finish or stop the current task before changing accounts");
      return;
    }
    new AlertDialog.Builder(this)
        .setTitle("Connect ChatGPT")
        .setMessage(
            "Sign in using the browser on this TV. Eligible Plus/Pro plans can power AI tasks. You"
                + " choose whether to grant plan usage. Plus usage shares your five-hour allowance"
                + " across participating apps. Basic commands remain available without sign-in.")
        .setPositiveButton(
            "Continue with ChatGPT",
            (d, w) -> {
              state("Opening ChatGPT sign-in…");
              auth.signIn(
                  add,
                  new ChatAuth.Listener() {
                    public void ready(String url) {
                      handler.post(
                          () -> {
                            try {
                              Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                              if (getPackageManager().getLaunchIntentForPackage("com.amazon.cloud9")
                                  != null) i.setPackage("com.amazon.cloud9");
                              startActivity(i);
                            } catch (Exception e) {
                              auth.cancelLogin();
                              state("No system browser is installed");
                            }
                          });
                    }

                    public void result(String message) {
                      handler.post(
                          () -> {
                            answer.setText(message);
                            updateAccount();
                            state("Return to TV Assistant after sign-in");
                          });
                    }
                  });
            })
        .setNegativeButton("Later", null)
        .show();
  }

  private void accountPicker() {
    if (auth == null) return;
    JSONArray profiles = auth.profiles();
    String[] labels = new String[profiles.length()];
    for (int i = 0; i < labels.length; i++) {
      JSONObject p = profiles.optJSONObject(i);
      labels[i] =
          (i + 1)
              + " · "
              + p.optString("email")
              + " · "
              + p.optString("client_id")
                  .substring(Math.max(0, p.optString("client_id").length() - 7));
    }
    new AlertDialog.Builder(this)
        .setTitle("Saved ChatGPT connections")
        .setItems(
            labels,
            (d, w) -> {
              if (engine.busy()) {
                state("Stop the current task before switching accounts");
                return;
              }
              try {
                auth.select(profiles.optJSONObject(w).getString("client_id"));
                prefs.edit().remove("model").apply();
                engine.clear();
                updateAccount();
              } catch (Exception e) {
                state(ChatAuth.safe(e));
              }
            })
        .setNegativeButton("Close", null)
        .show();
  }

  private void models() {
    if (auth == null || !ChatAuth.hasPlan(auth.active())) {
      answer.setText("Connect ChatGPT and grant plan usage first.");
      return;
    }
    state("Loading eligible models…");
    new Thread(
            () -> {
              try {
                JSONArray catalog = engine.catalog();
                String[] labels = new String[catalog.length()];
                for (int i = 0; i < labels.length; i++)
                  labels[i] = catalog.getJSONObject(i).getString("display_name");
                handler.post(
                    () ->
                        new AlertDialog.Builder(this)
                            .setTitle("Choose AI model")
                            .setItems(
                                labels,
                                (d, w) -> {
                                  prefs
                                      .edit()
                                      .putString(
                                          "model", catalog.optJSONObject(w).optString("slug"))
                                      .apply();
                                  state("Selected " + labels[w]);
                                })
                            .setNegativeButton("Close", null)
                            .show());
              } catch (Exception e) {
                state(ChatAuth.safe(e));
              }
            })
        .start();
  }

  private void openUsage() {
    try {
      startActivity(
          new Intent(Intent.ACTION_VIEW, Uri.parse("https://chatgpt.com/settings/usage")));
    } catch (Exception e) {
      state("No browser is available");
    }
  }

  private void limits(String key, int[] values, String title) {
    String[] labels = new String[values.length];
    for (int i = 0; i < values.length; i++) labels[i] = String.valueOf(values[i]);
    new AlertDialog.Builder(this)
        .setTitle(title)
        .setItems(
            labels,
            (d, w) -> {
              prefs.edit().putInt(key, values[w]).apply();
              state("Limit set to " + values[w]);
            })
        .show();
  }

  private void toolList() {
    JSONArray schemas = tools.schemas();
    String[] labels = new String[schemas.length()];
    for (int i = 0; i < labels.length; i++) labels[i] = schemas.optJSONObject(i).optString("name");
    new AlertDialog.Builder(this)
        .setTitle(labels.length + " tools")
        .setItems(
            labels,
            (d, w) ->
                new AlertDialog.Builder(this)
                    .setTitle(labels[w])
                    .setMessage(schemas.optJSONObject(w).optString("description"))
                    .setPositiveButton("Close", null)
                    .show())
        .setNegativeButton("Close", null)
        .show();
  }

  private void appList() {
    JSONArray apps = tools.apps();
    String[] labels = new String[apps.length()];
    for (int i = 0; i < labels.length; i++) labels[i] = apps.optJSONObject(i).optString("label");
    new AlertDialog.Builder(this)
        .setTitle("Installed apps")
        .setItems(
            labels,
            (d, w) -> {
              command.setText("Open " + apps.optJSONObject(w).optString("package"));
              submit(false);
            })
        .setNegativeButton("Close", null)
        .show();
  }

  private void showHistory() {
    String[] labels = new String[history.length()];
    for (int i = 0; i < labels.length; i++)
      labels[i] =
          Json.clip(history.optJSONObject(history.length() - 1 - i).optString("command"), 100);
    new AlertDialog.Builder(this)
        .setTitle("Recent commands")
        .setItems(
            labels,
            (d, w) -> {
              JSONObject item = history.optJSONObject(history.length() - 1 - w);
              new AlertDialog.Builder(this)
                  .setTitle(item.optString("command"))
                  .setMessage(item.optString("answer"))
                  .setPositiveButton(
                      "Run again",
                      (x, j) -> {
                        command.setText(item.optString("command"));
                        submit(false);
                      })
                  .setNegativeButton("Close", null)
                  .show();
            })
        .setNeutralButton("Use AI for current command", (d, w) -> submit(true))
        .setNegativeButton("Close", null)
        .show();
  }

  private void diagnostics() {
    if (engine.busy()) {
      state("Finish the current task before diagnostics");
      return;
    }
    answer.setText("Checking local capabilities…");
    tools.startTask(16);
    new Thread(
            () -> {
              StringBuilder report = new StringBuilder();
              String[] names = {
                "device_info", "list_apps", "clock", "calculate", "volume", "preferences"
              };
              JSONObject[] args = {
                Json.obj(),
                Json.obj(),
                Json.obj(),
                Json.obj("expression", "(12+8)*3/2"),
                Json.obj("action", "get"),
                Json.obj("action", "get")
              };
              int passed = 0;
              for (int i = 0; i < names.length; i++) {
                JSONObject result = tools.execute(names[i], args[i]);
                if (!result.has("error")) passed++;
                report
                    .append(result.has("error") ? "FAIL " : "PASS ")
                    .append(names[i])
                    .append("\n");
              }
              report
                  .append("\n")
                  .append(passed)
                  .append("/6 local checks passed.\nNavigation: ")
                  .append(NavigationService.instance != null ? "enabled" : "needs permission")
                  .append("\nChatGPT: ")
                  .append(
                      auth != null && ChatAuth.hasPlan(auth.active())
                          ? "connected; inference not tested by diagnostics"
                          : "needs sign-in for AI");
              String text = report.toString();
              handler.post(
                  () -> {
                    answer.setText(text);
                    state("Diagnostics complete");
                  });
            })
        .start();
  }

  private LinearLayout row(LinearLayout parent) {
    LinearLayout r = new LinearLayout(this);
    r.setOrientation(0);
    r.setPadding(0, dp(6), 0, 0);
    parent.addView(r);
    return r;
  }

  private void button(String label, LinearLayout row, Runnable action) {
    Button b = new Button(this);
    b.setText(label);
    b.setTextSize(13);
    b.setAllCaps(false);
    b.setTextColor(Color.WHITE);
    b.setId(View.generateViewId());
    b.setPadding(dp(4), 0, dp(4), 0);
    b.setBackground(panel(Color.rgb(37, 55, 77), Color.rgb(52, 75, 99)));
    b.setOnFocusChangeListener(
        (v, focused) ->
            b.setBackground(
                panel(
                    focused ? Color.rgb(45, 101, 130) : Color.rgb(37, 55, 77),
                    focused ? Color.rgb(125, 211, 252) : Color.rgb(52, 75, 99))));
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(38), 1);
    p.rightMargin = dp(6);
    row.addView(b, p);
    b.setOnClickListener(v -> action.run());
  }

  private TextView text(String value, int size, int color) {
    TextView t = new TextView(this);
    t.setText(value);
    t.setTextSize(size);
    t.setTextColor(color);
    return t;
  }

  private GradientDrawable panel(int color, int stroke) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(dp(7));
    d.setStroke(dp(1), stroke);
    return d;
  }

  private int dp(int v) {
    return Math.round(v * getResources().getDisplayMetrics().density);
  }

  @Override
  protected void onDestroy() {
    if (micProbe != null) micProbe.cancel();
    if (auth != null) auth.cancelLogin();
    if (engine != null) engine.shutdown();
    if (tools != null) tools.close();
    if (tts != null) tts.shutdown();
    super.onDestroy();
  }
}
