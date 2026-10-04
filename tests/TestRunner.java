package dev.mert.tvassistant;

import android.app.Instrumentation;
import android.content.*;
import android.os.*;
import java.io.*;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.util.concurrent.*;
import org.json.*;

public final class TestRunner extends Instrumentation {
  interface Check {
    void run() throws Exception;
  }

  private int passed, failed;
  private boolean youtubeOnly;
  private boolean visionOnly;
  private boolean benchmarkOnly;
  private boolean browseOnly;
  private boolean probeOnly;
  private boolean deviceStateOnly;
  private int requestedRounds;
  private String expectedSeries;
  private int expectedSeason, expectedEpisode;
  private String probeUrl = "https://www.hdfilmcehennemi.nl/";
  private String probeQuery = "succession";
  private String browsePrompt =
      "go to hdfilmcehennemi.nl and find the tv show succession, season 2 episode 4 for me";
  private boolean emulatorOnly;
  private boolean nativeOnly;
  private int repeats = 1;
  private final StringBuilder report = new StringBuilder();

  @Override
  public void onCreate(Bundle args) {
    super.onCreate(args);
    youtubeOnly = args != null && "youtube".equals(args.getString("group"));
    nativeOnly = args != null && "emulator-native".equals(args.getString("group"));
    emulatorOnly = args != null && "emulator".equals(args.getString("group"));
    benchmarkOnly = args != null && "benchmark".equals(args.getString("group"));
    browseOnly = args != null && "browse".equals(args.getString("group"));
    probeOnly = args != null && "probe".equals(args.getString("group"));
    deviceStateOnly = args != null && "device-state".equals(args.getString("group"));
    if (deviceStateOnly && args.getString("rounds") != null) {
      requestedRounds = Integer.parseInt(args.getString("rounds"));
      if (requestedRounds < 1 || requestedRounds > 12)
        throw new IllegalArgumentException("rounds must be 1–12");
    }
    if (args != null && args.getString("expect_series") != null) {
      expectedSeries = args.getString("expect_series");
      expectedSeason = Integer.parseInt(args.getString("expect_season"));
      expectedEpisode = Integer.parseInt(args.getString("expect_episode"));
    }
    if (args != null && args.getString("url") != null && !args.getString("url").isEmpty())
      probeUrl = args.getString("url");
    if (args != null && args.getString("query") != null) probeQuery = args.getString("query");
    if (args != null && args.getString("prompt") != null && !args.getString("prompt").isEmpty())
      browsePrompt = args.getString("prompt");
    if (args != null && args.getString("repeats") != null)
      repeats = Math.max(1, Math.min(10, Integer.parseInt(args.getString("repeats"))));
    visionOnly = args != null && "vision".equals(args.getString("group"));
    start();
  }

  private void test(String name, Check check) {
    try {
      check.run();
      passed++;
      report.append("PASS ").append(name).append('\n');
    } catch (Throwable e) {
      failed++;
      report.append("FAIL ").append(name).append(": ").append(e.getMessage()).append('\n');
    }
  }

  private static void yes(boolean condition) {
    if (!condition) throw new AssertionError("Assertion failed");
  }

  private static void rejects(Check check) throws Exception {
    try {
      check.run();
    } catch (Exception expected) {
      return;
    }
    throw new AssertionError("Expected rejection");
  }

  private static Net.Events events(boolean stopped) {
    return new Net.Events() {
      public void event(JSONObject o) {}

      public boolean cancelled() {
        return stopped;
      }
    };
  }

  private static JSONObject stream(String raw, boolean stopped) throws Exception {
    return Net.parseStream(new BufferedReader(new StringReader(raw)), events(stopped));
  }

  private String signed(KeyPair pair, JSONObject claims) throws Exception {
    String
        head = ChatAuth.b64(Json.obj("alg", "RS256", "kid", "test").toString().getBytes("UTF-8")),
        body = ChatAuth.b64(claims.toString().getBytes("UTF-8"));
    Signature s = Signature.getInstance("SHA256withRSA");
    s.initSign(pair.getPrivate());
    s.update((head + "." + body).getBytes("US-ASCII"));
    return head + "." + body + "." + ChatAuth.b64(s.sign());
  }

  private CaptureService testCapture() throws Exception {
    CaptureService service = new CaptureService();
    String[] names = {
      "width", "height", "physicalWidth", "physicalHeight", "snapshot", "packageName", "capturedAt"
    };
    Object[] values = {
      1280, 720, 1920, 1080, "snapshot", "test.app", SystemClock.elapsedRealtime()
    };
    for (int i = 0; i < names.length; i++) {
      java.lang.reflect.Field field = CaptureService.class.getDeclaredField(names[i]);
      field.setAccessible(true);
      field.set(service, values[i]);
    }
    return service;
  }

  private void visionTests() {
    test(
        "vision input includes a real image item",
        () -> {
          JSONArray input = Json.arr(Json.obj("role", "user", "content", "Inspect the screen"));
          AssistantEngine.attachScreenshot(input, "data:image/jpeg;base64,AAA");
          yes(
              input.length() == 2
                  && input
                      .getJSONObject(1)
                      .getJSONArray("content")
                      .getJSONObject(1)
                      .getString("type")
                      .equals("input_image"));
        });
    test(
        "vision retains only latest image while preserving text",
        () -> {
          JSONArray input = new JSONArray();
          AssistantEngine.attachScreenshot(input, "data:image/jpeg;base64,OLD");
          AssistantEngine.attachScreenshot(input, "data:image/jpeg;base64,NEW");
          yes(
              input.getJSONObject(0).getJSONArray("content").length() == 1
                  && input
                      .getJSONObject(1)
                      .getJSONArray("content")
                      .getJSONObject(1)
                      .getString("image_url")
                      .endsWith("NEW"));
        });
    test(
        "vision rejects non-screenshot remote images",
        () ->
            rejects(
                () ->
                    AssistantEngine.attachScreenshot(
                        new JSONArray(), "https://example.com/image.jpg")));
    test(
        "vision maps image pixels to display pixels",
        () -> {
          float[] point = testCapture().point("snapshot", 640, 360, "test.app");
          yes(point[0] == 960 && point[1] == 540);
        });
    test(
        "vision rejects coordinates outside screenshot",
        () -> rejects(() -> testCapture().point("snapshot", 1280, 720, "test.app")));
    test(
        "vision rejects changed app",
        () -> rejects(() -> testCapture().point("snapshot", 10, 10, "other.app")));
    test(
        "vision rejects stale snapshot ID",
        () -> rejects(() -> testCapture().point("old", 10, 10, "test.app")));
    test(
        "vision consumes snapshot after an action",
        () -> {
          CaptureService service = testCapture();
          service.point("snapshot", 10, 10, "test.app");
          rejects(() -> service.point("snapshot", 10, 10, "test.app"));
        });
    test(
        "vision maps both swipe endpoints",
        () -> {
          float[] points =
              testCapture().points("snapshot", new int[] {100, 200, 300, 400}, "test.app");
          yes(points.length == 4 && points[0] == 150 && points[3] == 600);
        });
  }

  private void youtubeTests() {
    final String channel = "UCxAS_aK7sS2x_bqnlJHDSHw";
    test(
        "YouTube public search parses channels and videos",
        () -> {
          JSONObject data =
              Json.obj(
                  "contents",
                  Json.arr(
                      Json.obj(
                          "channelRenderer",
                          Json.obj(
                              "channelId",
                              channel,
                              "title",
                              Json.obj("simpleText", "Example kitchen"))),
                      Json.obj(
                          "videoRenderer",
                          Json.obj(
                              "videoId",
                              "abcdefghijk",
                              "title",
                              Json.obj("runs", Json.arr(Json.obj("text", "A dish")))))));
          JSONObject result =
              YouTube.parseSearch("<script>var ytInitialData = " + data + ";</script>");
          yes(
              result
                  .getJSONArray("channels")
                  .getJSONObject(0)
                  .getString("channel_id")
                  .equals(channel));
          yes(result.getJSONArray("videos").getJSONObject(0).getString("title").equals("A dish"));
        });
    test(
        "YouTube missing metadata is explicit failure",
        () -> rejects(() -> YouTube.parseSearch("<html>Consent required</html>")));
    test(
        "YouTube invalid video IDs rejected",
        () -> yes(!YouTube.validVideo("../../account") && YouTube.validVideo("aB_123456-9")));
    test(
        "YouTube invalid channel IDs rejected before networking",
        () -> rejects(() -> YouTube.latest("../account")));
    test(
        "YouTube upload feed orders by publication date",
        () -> {
          String xml =
              "<feed xmlns=\"http://www.w3.org/2005/Atom\""
                  + " xmlns:yt=\"http://www.youtube.com/xml/schemas/2015\"><yt:channelId>"
                  + channel
                  + "</yt:channelId><title>Example"
                  + " kitchen</title><entry><yt:videoId>abcdefghijk</yt:videoId><title>Older"
                  + " dish</title><published>2026-09-01T12:00:00+00:00</published></entry><entry><yt:videoId>12345678901</yt:videoId><title>Latest"
                  + " dish</title><published>2026-10-01T12:00:00+00:00</published></entry></feed>";
          JSONObject result = YouTube.parseFeed(xml);
          yes(
              result.getJSONObject("latest").getString("title").equals("Latest dish")
                  && result.getString("channel_id").equals(channel));
        });
    test(
        "YouTube empty upload feed is explicit failure",
        () -> rejects(() -> YouTube.parseFeed("<feed/>")));
    test(
        "YouTube feed canonicalizes its unprefixed root channel ID",
        () -> {
          JSONObject result =
              YouTube.parseFeed(
                  "<feed xmlns=\"http://www.w3.org/2005/Atom\""
                      + " xmlns:yt=\"http://www.youtube.com/xml/schemas/2015\"><yt:channelId>"
                      + channel.substring(2)
                      + "</yt:channelId><entry><yt:videoId>abcdefghijk</yt:videoId><title>Dish</title><published>2026-10-01T12:00:00+00:00</published></entry></feed>");
          yes(result.getString("channel_id").equals(channel));
        });
    test(
        "YouTube XML entities rejected",
        () ->
            rejects(
                () ->
                    YouTube.parseFeed(
                        "<!DOCTYPE feed [<!ENTITY x SYSTEM 'file:///data/file'>]><feed/>")));
    test(
        "YouTube public channel discovery works on TV",
        () -> {
          JSONArray channels = YouTube.search("America's Test Kitchen").getJSONArray("channels");
          boolean found = false;
          for (int i = 0; i < channels.length(); i++)
            found |= channel.equals(channels.getJSONObject(i).optString("channel_id"));
          yes(found);
        });
    test(
        "YouTube live upload feed returns exact playable ID",
        () -> {
          JSONObject latest = YouTube.latest(channel).getJSONObject("latest");
          yes(
              YouTube.validVideo(latest.getString("video_id"))
                  && !latest.getString("published").isEmpty());
        });
    if (youtubeOnly)
      test(
          "AI chooses YouTube channel and latest-upload tools without playback",
          () -> {
            Context context = getTargetContext();
            CountDownLatch done = new CountDownLatch(1);
            StringBuilder trace = new StringBuilder();
            String[] answer = {""};
            Tools.Host host =
                new Tools.Host() {
                  public <T> T ui(Callable<T> task) {
                    throw new IllegalStateException(
                        "This validation is read-only; opening apps is disabled");
                  }

                  public boolean approve(String text) {
                    return false;
                  }

                  public void trace(String text) {
                    trace.append(text).append('\n');
                  }

                  public void speak(String text) {}

                  public boolean cancelled() {
                    return false;
                  }
                };
            Tools tools = new Tools(context, host);
            AssistantEngine engine =
                new AssistantEngine(
                    context,
                    tools,
                    new ChatAuth(context),
                    new AssistantEngine.Listener() {
                      public void state(String text) {}

                      public void answer(String text) {
                        answer[0] = text;
                      }

                      public void finished() {
                        done.countDown();
                      }
                    });
            try {
              engine.run(
                  "Find the American test kitchen latest video on YouTube. Use channel and upload"
                      + " lookup tools, and return its exact title and publication date. This is a"
                      + " read-only lookup: do not open or play anything.",
                  false);
              yes(done.await(60, TimeUnit.SECONDS));
              report.append("AI lookup answer: ").append(answer[0]).append('\n');
              yes(
                  trace.toString().contains("→ youtube search")
                      && trace.toString().contains("→ youtube latest")
                      && !answer[0].isEmpty()
                      && !answer[0].contains("configured AI request limit")
                      && !trace.toString().contains("✗"));
            } finally {
              engine.shutdown();
              tools.close();
            }
          });
  }

  private void speedTests(Tools tools) {
    test("socket continuation sends only new input and same response ID", () -> {
      JSONArray full = Json.arr(Json.obj("role", "user", "content", "hello"), Json.obj("type", "function_call_output", "call_id", "c", "output", "{}"));
      JSONObject payload = ResponseSocket.payload(Json.obj("stream", true, "store", false, "input", full), full, 1, "resp_test");
      yes(!payload.has("stream") && !payload.getBoolean("store") && payload.getString("type").equals("response.create")
          && payload.getString("previous_response_id").equals("resp_test") && payload.getJSONArray("input").length() == 1);
    });
    test("socket reset uses full context without previous ID", () -> {
      JSONArray full = Json.arr(Json.obj("role", "user", "content", "hello"));
      JSONObject payload = ResponseSocket.payload(Json.obj("stream", true), full, 1, "");
      yes(!payload.has("previous_response_id") && payload.getJSONArray("input").length() == 1);
    });
    test("plan references resolve arrays and scalar parameters", () -> {
      JSONArray results = Json.arr(Json.obj("results", Json.arr(Json.obj("id", "tt123"))));
      JSONObject args = Json.obj("id", Json.obj("$ref", "0.results.0.id"), "title", Json.obj("$param", "title"));
      JSONObject resolved = (JSONObject) ActionPlan.resolve(args, results, Json.obj("title", "The Wire"));
      yes(resolved.getString("id").equals("tt123") && resolved.getString("title").equals("The Wire"));
    });
    test("plan rejects forward references and recursion before execution", () -> {
      rejects(() -> ActionPlan.validate(Json.arr(Json.obj("tool", "calculate", "args", Json.obj("expression", Json.obj("$ref", "0.value"))))));
      rejects(() -> ActionPlan.validate(Json.arr(Json.obj("tool", "action_plan", "args", Json.obj()))));
      rejects(() -> ActionPlan.validate(Json.arr(Json.obj("tool", "routines", "args", Json.obj()))));
    });
    test("failed delivery stops dependent plan actions", () -> {
      int[] runs = {0};
      JSONArray plan = Json.arr(Json.obj("tool", "screen_click", "args", Json.obj()), Json.obj("tool", "calculate", "args", Json.obj()));
      JSONObject result = ActionPlan.run(plan, Json.obj(), new ActionPlan.Runner() {
        public void validate(String tool, JSONObject args) {}
        public JSONObject run(String tool, JSONObject args) { runs[0]++; return Json.obj("performed", false); }
        public boolean cancelled() { return false; }
      });
      yes(runs[0] == 1 && result.optBoolean("stopped_early") && result.optInt("completed_steps") == 0);
    });
    test("cancelled plan executes no steps", () -> {
      JSONObject result = ActionPlan.run(Json.arr(Json.obj("tool", "clock", "args", Json.obj())), Json.obj(), new ActionPlan.Runner() {
        public void validate(String tool, JSONObject args) {}
        public JSONObject run(String tool, JSONObject args) { throw new AssertionError("Executed after stop"); }
        public boolean cancelled() { return true; }
      });
      yes(result.getBoolean("stopped_early"));
    });
    test("plan enforces remaining tool budget before actions", () -> {
      Tools limited = new Tools(getTargetContext(), new Tools.Host() {
        public <T> T ui(Callable<T> task) { throw new AssertionError("Unexpected action"); }
        public boolean approve(String text) { return false; }
        public void trace(String text) {}
        public void speak(String text) {}
        public boolean cancelled() { return false; }
      });
      try {
        limited.startTask(1);
        JSONObject result = limited.execute("action_plan", Json.obj("steps", Json.arr(Json.obj("tool", "clock", "args", Json.obj()), Json.obj("tool", "clock", "args", Json.obj())).toString()));
        yes(result.has("error") && limited.usedSteps() == 0);
      } finally { limited.close(); }
    });
    test("fresh UI target rejects duplicate labels and password nodes", () -> {
      JSONObject a = Json.obj("id", 1, "label", "Find", "tag", "BUTTON");
      yes(Tools.uniqueTarget(Json.obj("nodes", Json.arr(a)), "Find", false, true, false).getInt("id") == 1);
      rejects(() -> Tools.uniqueTarget(Json.obj("nodes", Json.arr(a, Json.obj("id", 2, "label", "Find"))), "Find", false, true, false));
      yes(Tools.uniqueTarget(Json.obj("nodes", Json.arr(Json.obj("label", "Password", "password", true))), "Password", false, true, false) == null);
    });
    test("preferred model prefers the newest Sol and falls back safely", () -> {
      yes(AssistantEngine.preferredModel(Json.arr(
          Json.obj("slug", "gpt-6-luna"), Json.obj("slug", "gpt-6-sol"),
          Json.obj("slug", "gpt-6.1-sol"))).equals("gpt-6.1-sol"));
      yes(AssistantEngine.preferredModel(Json.arr(
          Json.obj("slug", "gpt-6-luna"), Json.obj("slug", "gpt-6-sol"))).equals("gpt-6-sol"));
      yes(AssistantEngine.preferredModel(Json.arr(
          Json.obj("slug", "gpt-6-luna"), Json.obj("slug", "gpt-5.6-terra"))).equals("gpt-6-luna"));
      yes(AssistantEngine.preferredModel(Json.arr(Json.obj("slug", "only-model"))).equals("only-model"));
      yes(AssistantEngine.preferredModel(new JSONArray()).isEmpty());
    });
    test("fresh UI scope rejects changed app and browser origin", () -> {
      rejects(() -> Tools.checkScope(Json.obj("package", "other.app"), "native", "test.app"));
      rejects(() -> Tools.checkScope(Json.obj("url", "https://other.com/path"), "browser", "https://example.com"));
      rejects(() -> Tools.checkScope(Json.obj("url", "https://example.com/path"), "browser", "https://example.com/path"));
      Tools.checkScope(Json.obj("url", "https://example.com/path"), "browser", "https://example.com");
    });
    test("cache excludes raw coordinate snapshots and typing", () -> {
      rejects(() -> ActionPlan.validateReusable(Json.arr(Json.obj("tool", "screen_tap", "args", Json.obj("snapshot", "old")))));
      rejects(() -> ActionPlan.validateReusable(Json.arr(Json.obj("tool", "ui_target", "args", Json.obj("scope", "browser", "context", "https://example.com", "label", "Search", "action", "type", "text", "query")))));
    });
  }

  private String sandboxShell(String command) throws Exception {
    try (android.os.ParcelFileDescriptor descriptor =
        getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            .executeShellCommand(command)) {
      return Net.read(new android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor), 16000).trim();
    }
  }

  private boolean containsLabel(JSONObject screen, String label) throws Exception {
    if (screen == null) return false;
    JSONArray nodes = screen.optJSONArray("nodes");
    if (nodes == null) return false;
    for (int i = 0; i < nodes.length(); i++) {
      JSONObject node = nodes.getJSONObject(i);
      if (node.optString("text").equals(label) || node.optString("description").equals(label)) return true;
    }
    return false;
  }

  private android.view.accessibility.AccessibilityNodeInfo consentButton(
      android.view.accessibility.AccessibilityNodeInfo root) {
    if (root == null) return null;
    String text = String.valueOf(root.getText());
    if (root.isClickable() && (text.equalsIgnoreCase("Start now") || text.equalsIgnoreCase("Allow")))
      return android.view.accessibility.AccessibilityNodeInfo.obtain(root);
    for (int i = 0; i < root.getChildCount(); i++) {
      android.view.accessibility.AccessibilityNodeInfo child = root.getChild(i);
      if (child != null) {
        android.view.accessibility.AccessibilityNodeInfo found = consentButton(child);
        child.recycle();
        if (found != null) return found;
      }
    }
    return null;
  }

  private void nativeSandbox() {
    if (!Build.HARDWARE.equals("ranchu")) {
      Bundle result = new Bundle(); result.putString("stream", "Refused: native sandbox requires the emulator.\n0 passed, 1 failed\n");
      finish(0, result); return;
    }
    Tools.Host host = new Tools.Host() {
      public <T> T ui(Callable<T> task) throws Exception {
        FutureTask<T> future = new FutureTask<>(task);
        new Handler(Looper.getMainLooper()).post(future);
        return future.get(15, TimeUnit.SECONDS);
      }
      public boolean approve(String text) { return false; }
      public void trace(String text) {}
      public void speak(String text) {}
      public boolean cancelled() { return false; }
    };
    Tools tools = new Tools(getTargetContext(), host);
    tools.startTask(32);
    try {
      // Instrumentation can mark the service crashed. Rebind only ours in the fresh sandbox.
      String service = "dev.mert.tvassistant/dev.mert.tvassistant.NavigationService";
      String existing = sandboxShell("settings get secure enabled_accessibility_services");
      java.util.List<String> others = new java.util.ArrayList<>();
      for (String value : existing.split(":")) if (!value.isEmpty() && !value.equals("null") && !value.equals(service)) others.add(value);
      String other = android.text.TextUtils.join(":", others);
      sandboxShell("settings put secure enabled_accessibility_services " + (other.isEmpty() ? "null" : other));
      others.add(service);
      sandboxShell("settings put secure enabled_accessibility_services " + android.text.TextUtils.join(":", others));
      sandboxShell("settings put secure accessibility_enabled 1");
      MainActivity main = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      for (int i = 0; NavigationService.instance == null && i < 40; i++) Thread.sleep(100);
      test("native accessibility service binds on API 34", () -> yes(NavigationService.instance != null));
      JSONObject initial = tools.execute("screen_read", Json.obj());
      test("actual native screen contains app controls", () -> yes(containsLabel(initial, "Apps") && !initial.has("error")));
      int field = -1;
      JSONArray nodes = initial.getJSONArray("nodes");
      for (int i = 0; i < nodes.length(); i++) if (nodes.getJSONObject(i).optBoolean("editable") && !nodes.getJSONObject(i).optBoolean("password")) field = nodes.getJSONObject(i).getInt("id");
      final int fieldId = field;
      test("native typing changes actual command field", () -> {
        JSONObject typed = tools.execute("screen_type", Json.obj("snapshot", initial.getString("snapshot"), "id", fieldId, "text", "sandbox text"));
        JSONObject after = null;
        for (int i = 0; i < 20; i++) {
          after = host.ui(() -> NavigationService.instance.inspect());
          if (containsLabel(after, "sandbox text")) break;
          Thread.sleep(100);
        }
        if (!typed.optBoolean("performed") || !containsLabel(after, "sandbox text"))
          throw new AssertionError("typing=" + typed + " observation=" + after);
        yes(typed.optBoolean("performed") && containsLabel(after, "sandbox text"));
      });
      test("native label typing verifies the field after its value changes", () -> {
        JSONObject typed = tools.execute("ui_target", Json.obj("scope", "native", "context", getTargetContext().getPackageName(),
            "label", "sandbox text", "action", "type", "text", "verified text"));
        yes(typed.optBoolean("performed") && typed.optBoolean("text_verified")
            && containsLabel(typed.getJSONObject("observation"), "verified text"));
      });
      test("native stale snapshot cannot type again", () -> yes(tools.execute("screen_type", Json.obj("snapshot", initial.getString("snapshot"), "id", fieldId, "text", "wrong")).has("error")));
      JSONArray plan = Json.arr(
          Json.obj("tool", "ui_target", "args", Json.obj("scope", "native", "context", getTargetContext().getPackageName(), "label", "Apps", "action", "click")),
          Json.obj("tool", "ui_target", "args", Json.obj("scope", "native", "context", getTargetContext().getPackageName(), "label", "Installed apps", "action", "wait")));
      test("native plan clicks and verifies actual app dialog", () -> {
        JSONObject result = tools.execute("action_plan", Json.obj("steps", plan.toString(), "cache_name", "sandbox-native"));
        yes(!result.has("error") && !result.optBoolean("stopped_early") && result.has("cached_workflow"));
      });
      tools.execute("navigate", Json.obj("direction", "back"));
      Thread.sleep(200);
      test("native verified workflow reuses fresh controls", () -> {
        JSONObject result = tools.execute("workflow", Json.obj("action", "run", "name", "sandbox-native"));
        yes(result.optBoolean("cache_hit") && containsLabel(tools.execute("screen_read", Json.obj()), "Installed apps"));
      });
      tools.execute("navigate", Json.obj("direction", "back"));
      tools.execute("workflow", Json.obj("action", "remove", "name", "sandbox-native"));
      test("native scope drift stops before a click", () -> yes(tools.execute("ui_target", Json.obj("scope", "native", "context", "other.app", "label", "Apps", "action", "click")).has("error")));
      test("screen vision starts through actual Android consent dialog", () -> {
        host.ui(() -> {
          android.media.projection.MediaProjectionManager manager = (android.media.projection.MediaProjectionManager) main.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
          main.startActivityForResult(manager.createScreenCaptureIntent(), 902); return true;
        });
        boolean clicked = false;
        for (int i = 0; i < 60 && !clicked; i++) {
          Thread.sleep(100);
          android.view.accessibility.AccessibilityNodeInfo root = getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).getRootInActiveWindow();
          if (root != null) {
            if (String.valueOf(root.getPackageName()).equals("com.android.systemui")) {
              android.view.accessibility.AccessibilityNodeInfo button = consentButton(root);
              if (button != null) { clicked = button.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK); button.recycle(); }
            }
            root.recycle();
          }
        }
        yes(clicked);
        for (int i = 0; CaptureService.instance == null && i < 50; i++) Thread.sleep(100);
        yes(CaptureService.instance != null);
      });
      JSONObject image = tools.execute("screen_see", Json.obj());
      test("screen_see returns a real nonblank emulator frame", () -> {
        yes(!image.has("error") && image.getString("image_url").startsWith("data:image/jpeg;base64,"));
        byte[] jpeg = android.util.Base64.decode(image.getString("image_url").substring("data:image/jpeg;base64,".length()), android.util.Base64.DEFAULT);
        android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
        yes(bitmap != null && bitmap.getWidth() == image.getInt("width") && bitmap.getHeight() == image.getInt("height"));
        java.util.Set<Integer> colors = new java.util.HashSet<>();
        for (int y = 0; y < bitmap.getHeight(); y += 40) for (int x = 0; x < bitmap.getWidth(); x += 40) colors.add(bitmap.getPixel(x, y));
        bitmap.recycle(); yes(colors.size() > 3);
      });
      test("visual tap opens the native dialog and returns a fresh frame", () -> {
        JSONObject screen = tools.execute("screen_read", Json.obj());
        JSONObject apps = Tools.uniqueTarget(screen, "Apps", false, false, false);
        JSONArray bounds = apps.getJSONArray("bounds");
        android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
        host.ui(() -> { main.getWindowManager().getDefaultDisplay().getRealMetrics(metrics); return true; });
        int x = (int) ((bounds.getInt(0) + bounds.getInt(2)) / 2.0 * image.getInt("width") / metrics.widthPixels);
        int y = (int) ((bounds.getInt(1) + bounds.getInt(3)) / 2.0 * image.getInt("height") / metrics.heightPixels);
        JSONObject result = tools.execute("screen_tap", Json.obj("snapshot", image.getString("snapshot"), "x", x, "y", y, "target", "Apps"));
        if (!result.optBoolean("performed") || !result.has("image_url")
            || result.optString("snapshot").equals(image.getString("snapshot"))
            || !containsLabel(result.optJSONObject("native_screen"), "Installed apps")) {
          result.remove("image_url");
          throw new AssertionError("tap=" + result);
        }
      });
      tools.execute("navigate", Json.obj("direction", "back"));
      test("target result exposes a usable fresh native snapshot with vision active", () -> {
        JSONObject result = tools.execute("ui_target", Json.obj("scope", "native",
            "context", getTargetContext().getPackageName(), "label", "Clear", "action", "click"));
        JSONObject nativeScreen = result.getJSONObject("native_screen");
        JSONObject appsNode = Tools.uniqueTarget(nativeScreen, "Apps", false, false, false);
        JSONObject clicked = tools.execute("screen_click", Json.obj("snapshot", nativeScreen.getString("snapshot"), "id", appsNode.getInt("id")));
        yes(clicked.optBoolean("performed") && containsLabel(clicked.getJSONObject("native_screen"), "Installed apps"));
        tools.execute("navigate", Json.obj("direction", "back"));
      });
    } catch (Exception error) {
      failed++; report.append("FAIL native sandbox setup: ").append(ChatAuth.safe(error)).append('\n');
    } finally {
      try { host.ui(() -> { getTargetContext().stopService(new Intent(getTargetContext(), CaptureService.class)); return true; }); } catch (Exception ignored) {}
      tools.close();
    }
    Bundle result = new Bundle(); result.putString("stream", report + "\n" + passed + " passed, " + failed + " failed\n");
    finish(failed == 0 ? -1 : 0, result);
  }

  private void benchmark() {
    test("live read-only AI benchmark", () -> {
      Context context = getTargetContext();
      CountDownLatch done = new CountDownLatch(1);
      StringBuilder trace = new StringBuilder();
      String[] answer = {""};
      Tools.Host host = new Tools.Host() {
        public <T> T ui(Callable<T> task) { throw new IllegalStateException("Read-only benchmark"); }
        public boolean approve(String text) { return false; }
        public void trace(String text) { trace.append(text).append('\n'); }
        public void speak(String text) {}
        public boolean cancelled() { return false; }
      };
      Tools tools = new Tools(context, host);
      AssistantEngine engine = new AssistantEngine(context, tools, new ChatAuth(context), new AssistantEngine.Listener() {
        public void state(String text) { if (text.startsWith("AI · request")) report.append(text).append('\n'); }
        public void answer(String text) { answer[0] = text; }
        public void finished() { done.countDown(); }
      });
      long started = SystemClock.elapsedRealtime();
      try {
        engine.run("Read the TV device info, read the current clock, and calculate 7 * 8. Use tools for all three, choose an efficient approach, then give a short answer. Do not open or change anything.", false);
        yes(done.await(60, TimeUnit.SECONDS));
        report.append("Benchmark elapsed_ms: ").append(SystemClock.elapsedRealtime() - started).append('\n');
        report.append(trace).append("Answer: ").append(answer[0]).append('\n');
        yes(answer[0].contains("56") && trace.toString().contains("→ device info") && trace.toString().contains("→ clock") && trace.toString().contains("→ calculate"));
      } finally { engine.shutdown(); tools.close(); }
    });
    Bundle result = new Bundle();
    result.putString("stream", report + "\n" + passed + " passed, " + failed + " failed\n");
    finish(failed == 0 ? -1 : 0, result);
  }

  /** Inspects isolated public pages directly, without the AI, to see what a site returns. */
  private void probe() throws Exception {
    Context context = getTargetContext();
    Tools.Host host =
        new Tools.Host() {
          public <T> T ui(Callable<T> task) throws Exception {
            FutureTask<T> f = new FutureTask<>(task);
            new Handler(Looper.getMainLooper()).post(f);
            return f.get(20, TimeUnit.SECONDS);
          }

          public boolean approve(String text) { return true; }
          public void trace(String text) {}
          public void speak(String text) {}
          public boolean cancelled() { return false; }
        };
    Tools tools = new Tools(context, host);
    tools.startTask(64);
    try {
      for (String url : probeUrl.split(",")) {
        if (url.trim().isEmpty()) continue;
        long t = SystemClock.elapsedRealtime();
        JSONObject opened = tools.execute("web_page", Json.obj("action", "open", "url", url.trim()));
        report.append("\n=== ").append(url.trim()).append('\n');
        report.append("ms=").append(SystemClock.elapsedRealtime() - t)
            .append(" err=").append(opened.optString("error", "none"))
            .append("\nurl=").append(Json.clip(opened.optString("url"), 200))
            .append("\ntitle=").append(opened.optString("title")).append('\n');
        if (probeQuery.isEmpty()) {
          report.append("nodes:\n").append(dumpNodes(opened)).append('\n');
          report.append("text:\n").append(Json.clip(opened.optString("text"), 2500)).append('\n');
          continue;
        }
        int input = searchInputId(opened);
        report.append("search input id=").append(input).append('\n');
        // The panel may need opening before it accepts a query.
        JSONObject clicked = tools.execute("web_page", Json.obj("action", "click",
            "snapshot", opened.optString("snapshot"), "id", input, "timeout_ms", 1500));
        report.append("click err=").append(clicked.optString("error", "none"))
            .append(" nodes=").append(clicked.optJSONArray("nodes") == null ? 0 : clicked.getJSONArray("nodes").length()).append('\n');
        JSONObject fresh = clicked.has("nodes") ? clicked
            : tools.execute("web_page", Json.obj("action", "read", "snapshot", opened.optString("snapshot"), "timeout_ms", 1500));
        t = SystemClock.elapsedRealtime();
        JSONObject typed = tools.execute("web_page", Json.obj("action", "type",
            "snapshot", fresh.optString("snapshot"), "id", searchInputId(fresh), "text", probeQuery,
            "timeout_ms", 6000));
        report.append("type ms=").append(SystemClock.elapsedRealtime() - t)
            .append(" err=").append(typed.optString("error", "none")).append('\n');
        report.append("page text contains query? ")
            .append(typed.optString("text").toLowerCase().contains(probeQuery.toLowerCase())).append('\n');
        report.append("nodes after search:\n").append(dumpNodes(typed)).append('\n');
      }
    } finally {
      tools.close();
    }
    Bundle result = new Bundle();
    result.putString("stream", report + "\n" + passed + " passed, " + failed + " failed\n");
    finish(0, result);
  }

  private static int searchInputId(JSONObject page) throws Exception {
    JSONArray nodes = page.optJSONArray("nodes");
    if (nodes != null)
      for (int i = 0; i < nodes.length(); i++) {
        JSONObject n = nodes.getJSONObject(i);
        if (n.optString("tag").matches("INPUT|TEXTAREA")) return n.getInt("id");
      }
    return 0;
  }

  private static String dumpNodes(JSONObject page) throws Exception {
    StringBuilder out = new StringBuilder();
    JSONArray nodes = page.optJSONArray("nodes");
    if (nodes == null) return "(none)";
    for (int i = 0; i < nodes.length(); i++) {
      JSONObject n = nodes.getJSONObject(i);
      out.append("  ").append(n.optInt("id")).append(' ').append(n.optString("tag"))
          .append(" | ").append(Json.clip(n.optString("label"), 70))
          .append(" | ").append(Json.clip(n.optString("href"), 90)).append('\n');
    }
    return out.toString();
  }

  private void browse() throws Exception {
    Context context = getTargetContext();
    for (int run = 0; run < repeats; run++) {
      CountDownLatch done = new CountDownLatch(1);
      StringBuilder trace = new StringBuilder();
      String[] answer = {""};
      Tools.Host host =
          new Tools.Host() {
            public <T> T ui(Callable<T> task) throws Exception {
              FutureTask<T> f = new FutureTask<>(task);
              new Handler(Looper.getMainLooper()).post(f);
              try { return f.get(15, TimeUnit.SECONDS); }
              catch (ExecutionException e) {
                if (e.getCause() instanceof Exception) throw (Exception) e.getCause();
                throw e;
              }
            }

            public boolean approve(String text) { return true; }

            public void trace(String text) { trace.append(text).append('\n'); }

            public void speak(String text) {}

            public boolean cancelled() { return false; }
          };
      Tools tools = new Tools(context, host);
      AssistantEngine engine =
          new AssistantEngine(
              context,
              tools,
              new ChatAuth(context),
              new AssistantEngine.Listener() {
                public void state(String text) {
                  if (text.startsWith("AI · request")) report.append(text).append('\n');
                }

                public void answer(String text) { answer[0] = text; }

                public void finished() { done.countDown(); }
              });
      long started = SystemClock.elapsedRealtime();
      try {
        engine.run(browsePrompt, false);
        yes(done.await(240, TimeUnit.SECONDS));
        long elapsed = SystemClock.elapsedRealtime() - started;
        report.append("--- browse run ").append(run + 1).append('/').append(repeats).append(" ---\n");
        report.append("Wall elapsed_ms: ").append(elapsed).append('\n');
        report.append(trace);
        report.append("Answer: ").append(answer[0]).append('\n');
        report.append("Actual model: ").append(context.getSharedPreferences("assistant", 0)
            .getString("model", "unselected")).append('\n');
        NavigationService navigation = NavigationService.instance;
        JSONObject screen = navigation == null ? Json.obj("error", "Navigation disconnected")
            : host.ui(() -> navigation.inspect());
        report.append("Final native screen: ").append(screen).append('\n');
        if (expectedSeries != null) test("requested episode is visible in Stremio, run " + (run + 1), () -> {
          yes(MediaEpisode.verified(screen, Json.obj("title", expectedSeries,
              "season", expectedSeason, "episode", expectedEpisode)));
        });
      } finally {
        engine.shutdown();
        tools.close();
      }
    }
    Bundle result = new Bundle();
    result.putString("stream", report + "\n" + passed + " passed, " + failed + " failed\n");
    finish(0, result);
  }

  @Override
  public void onStart() {
    if (nativeOnly) { nativeSandbox(); return; }
    if (benchmarkOnly) { benchmark(); return; }
    try {
      if (deviceStateOnly) {
        Context context = getTargetContext();
        SharedPreferences settings = context.getSharedPreferences("assistant", 0);
        if (requestedRounds > 0)
          yes(settings.edit().putInt("max_rounds", requestedRounds).commit());
        report.append("Model: ").append(settings.getString("model", "unselected"))
            .append("\nRequest limit: ").append(settings.getInt("max_rounds", 4))
            .append("\nTool limit: ").append(settings.getInt("max_tools", 10))
            .append("\nPlan enabled: ").append(ChatAuth.hasPlan(new ChatAuth(context).active()))
            .append("\nNavigation setting: ").append(android.provider.Settings.Secure.getString(
                context.getContentResolver(), "enabled_accessibility_services"));
        Bundle result = new Bundle();
        result.putString("stream", report.toString());
        finish(0, result);
        return;
      }
      if (browseOnly) { browse(); return; }
    if (probeOnly) { probe(); return; }
    } catch (Exception e) {
      report.append("FAIL browse: ").append(e.getMessage()).append('\n');
      failed++;
      Bundle out = new Bundle();
      out.putString("stream", report + "\n" + passed + " passed, " + failed + " failed\n");
      finish(0, out);
      return;
    }
    visionTests();
    if (visionOnly) {
      Bundle result = new Bundle();
      result.putString("stream", report + "\n" + passed + " passed, " + failed + " failed\n");
      finish(failed == 0 ? -1 : 0, result);
      return;
    }
    youtubeTests();
    if (youtubeOnly) {
      report.append("\n").append(passed).append(" passed, ").append(failed).append(" failed\n");
      Bundle result = new Bundle();
      result.putString("stream", report.toString());
      finish(failed == 0 ? -1 : 0, result);
      return;
    }
    Context c = getTargetContext();
    Tools.Host host =
        new Tools.Host() {
          public <T> T ui(Callable<T> task) throws Exception {
            FutureTask<T> f = new FutureTask<>(task);
            new Handler(Looper.getMainLooper()).post(f);
            return f.get(15, TimeUnit.SECONDS);
          }

          public boolean approve(String s) {
            return true;
          }

          public void trace(String s) {}

          public void speak(String s) {}

          public boolean cancelled() {
            return false;
          }
        };
    Tools tools = new Tools(c, host);
    tools.startTask(200);
    speedTests(tools);
    JSONArray apps = tools.apps();
    test(
        "connected requests always use AI",
        () -> yes(!AssistantEngine.useLocalRouting(false, false, true)));
    test(
        "explicit local-only mode uses local routing",
        () -> yes(AssistantEngine.useLocalRouting(false, true, true)));
    test(
        "disconnected basic commands use local routing",
        () -> yes(AssistantEngine.useLocalRouting(false, false, false)));
    test(
        "forced AI never uses local routing",
        () -> yes(!AssistantEngine.useLocalRouting(true, true, false)));
    test("arithmetic precedence", () -> yes(Calculator.evaluate("(12 + 8) * 3 / 2") == 30));
    test("arithmetic unary and decimals", () -> yes(Calculator.evaluate("-2.5 * -4 + 1") == 11));
    test("arithmetic zero division rejected", () -> rejects(() -> Calculator.evaluate("4/0")));
    test(
        "arithmetic executable text rejected",
        () -> rejects(() -> Calculator.evaluate("Runtime.exec('sh')")));
    test(
        "arithmetic unmatched parentheses rejected",
        () -> rejects(() -> Calculator.evaluate("(4+2")));
    test("arithmetic trailing tokens rejected", () -> rejects(() -> Calculator.evaluate("4 2")));
    test(
        "ASR incomplete Stremio request",
        () -> {
          LocalCommands.Call call = LocalCommands.parse("Open stremio and the wire", apps);
          yes(call.tool.equals("search_app") && call.args.getString("query").equals("the wire"));
        });
    test(
        "Stremio find title",
        () -> {
          LocalCommands.Call call = LocalCommands.parse("Open Stremio and find The Wire", apps);
          yes(call.args.getString("query").equals("The Wire"));
        });
    test(
        "spoken website",
        () -> {
          LocalCommands.Call call = LocalCommands.parse("Open example dot com", apps);
          yes(
              call.tool.equals("open_url")
                  && call.args.getString("url").equals("https://example.com"));
        });
    test(
        "volume percent",
        () ->
            yes(
                LocalCommands.parse("Set volume to 40 percent", apps).args.getInt("percent")
                    == 40));
    test(
        "timer units",
        () ->
            yes(
                LocalCommands.parse("Set a timer for 2 minutes called tea", apps)
                        .args
                        .getLong("seconds")
                    == 120));
    test(
        "weather routing",
        () -> yes(LocalCommands.parse("Weather in Istanbul", apps).tool.equals("weather")));
    test(
        "ambiguous command leaves AI choice",
        () -> yes(LocalCommands.parse("find something interesting for tonight", apps) == null));
    test(
        "function schemas are well formed",
        () -> {
          JSONArray schemas = tools.schemas();
          java.util.Set<String> names = new java.util.HashSet<>();
          for (int i = 0; i < schemas.length(); i++) {
            JSONObject schema = schemas.getJSONObject(i);
            yes(schema.getString("type").equals("function"));
            yes(names.add(schema.getString("name")));
            JSONObject parameters = schema.getJSONObject("parameters"),
                properties = parameters.getJSONObject("properties");
            JSONArray required = parameters.getJSONArray("required");
            for (int j = 0; j < required.length(); j++) yes(properties.has(required.getString(j)));
          }
        });
    test("installed app discovery", () -> yes(apps.length() > 0));
    test(
        "device information",
        () -> yes(tools.execute("device_info", Json.obj()).getInt("api") == Build.VERSION.SDK_INT));
    test(
        "read-only volume",
        () -> yes(tools.execute("volume", Json.obj("action", "get")).has("percent")));
    test(
        "missing arguments rejected",
        () -> yes(tools.execute("open_url", Json.obj()).has("error")));
    test(
        "wrong argument type rejected",
        () ->
            yes(
                tools
                    .execute("volume", Json.obj("action", "set", "percent", "loud"))
                    .has("error")));
    test(
        "extra arguments rejected",
        () -> yes(tools.execute("clock", Json.obj("command", "shell")).has("error")));
    test("unknown tool rejected", () -> yes(tools.execute("shell", Json.obj()).has("error")));
    test(
        "invalid enum rejected",
        () -> yes(tools.execute("navigate", Json.obj("direction", "factory_reset")).has("error")));
    test(
        "invalid volume range rejected",
        () -> yes(tools.execute("volume", Json.obj("action", "set", "percent", 200)).has("error")));
    test(
        "file URL rejected",
        () ->
            yes(
                tools
                    .execute(
                        "open_url", Json.obj("url", "file:///etc/passwd", "browser", "internal"))
                    .has("error")));
    test(
        "intent URL rejected",
        () ->
            yes(
                tools
                    .execute("open_url", Json.obj("url", "intent://settings", "browser", "system"))
                    .has("error")));
    test(
        "auth URL excluded from AI browser",
        () ->
            yes(
                tools
                    .execute(
                        "open_url",
                        Json.obj("url", "https://auth.openai.com/", "browser", "internal"))
                    .has("error")));
    test(
        "password preference rejected",
        () ->
            yes(
                tools
                    .execute(
                        "preferences",
                        Json.obj("action", "set", "key", "password", "value", "test"))
                    .has("error")));
    test(
        "bad media identifier rejected",
        () ->
            yes(
                tools
                    .execute("media_details", Json.obj("id", "../../settings", "type", "series"))
                    .has("error")));
    test("media episode schema requires both coordinates", () -> {
      yes(tools.execute("media_details", Json.obj("id", "tt0121955", "type", "series", "season", 3)).has("error"));
      yes(tools.execute("media_details", Json.obj("id", "tt0121955", "type", "series", "episode", 5)).has("error"));
    });
    test("media episode schema rejects movies and fractional coordinates", () -> {
      yes(tools.execute("media_details", Json.obj("id", "tt0121955", "type", "movie", "season", 3, "episode", 5)).has("error"));
      yes(tools.execute("media_details", Json.obj("id", "tt0121955", "type", "series", "season", 3.5, "episode", 5)).has("error"));
    });
    test(
        "sensitive action detection",
        () ->
            yes(
                Tools.sensitive("Buy now")
                    && Tools.sensitive("Delete account")
                    && !Tools.sensitive("The Wire")));
    test(
        "SSE completed",
        () ->
            yes(
                stream(
                        "data: {\"type\":\"response.output_text.delta\",\"delta\":\"hello\"}\n\n"
                            + "data:"
                            + " {\"type\":\"response.completed\",\"response\":{\"output\":[]}}\n\n",
                        false)
                    .has("output")));
    test(
        "SSE restores streamed tool calls from empty terminal output",
        () -> {
          JSONObject call =
              Json.obj(
                  "type",
                  "function_call",
                  "call_id",
                  "test-call",
                  "namespace",
                  "tv",
                  "name",
                  "calculate",
                  "arguments",
                  "{\"expression\":\"7*8\"}");
          JSONObject result =
              stream(
                  "data: "
                      + Json.obj(
                          "type", "response.output_item.done", "output_index", 0, "item", call)
                      + "\n\n"
                      + "data: {\"type\":\"response.completed\",\"response\":{\"output\":[]}}\n\n",
                  false);
          yes(result.getJSONArray("output").getJSONObject(0).toString().equals(call.toString()));
        });
    test(
        "SSE preserves completed output without duplication",
        () -> {
          JSONObject message =
              Json.obj(
                  "type",
                  "message",
                  "content",
                  Json.arr(Json.obj("type", "output_text", "text", "56")));
          JSONObject result =
              stream(
                  "data: "
                      + Json.obj(
                          "type", "response.output_item.done", "output_index", 0, "item", message)
                      + "\n\n"
                      + "data: "
                      + Json.obj(
                          "type",
                          "response.completed",
                          "response",
                          Json.obj("output", Json.arr(message)))
                      + "\n\n",
                  false);
          yes(result.getJSONArray("output").length() == 1);
        });
    test(
        "SSE abrupt ending rejected",
        () ->
            rejects(
                () ->
                    stream(
                        "data: {\"type\":\"response.output_text.delta\",\"delta\":\"hello\"}\n\n",
                        false)));
    test(
        "SSE late usage failure rejected",
        () ->
            rejects(
                () ->
                    stream(
                        "data:"
                            + " {\"type\":\"response.failed\",\"response\":{\"error\":{\"code\":\"subscription_sharing_usage_limit_exceeded\"}}}\n\n",
                        false)));
    test(
        "SSE incomplete rejected",
        () -> rejects(() -> stream("data: {\"type\":\"response.incomplete\"}\n\n", false)));
    test("SSE stop respected", () -> rejects(() -> stream("data: {}\n\n", true)));
    test(
        "PKCE RFC challenge",
        () ->
            yes(
                ChatAuth.b64(
                        MessageDigest.getInstance("SHA-256")
                            .digest(
                                "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk".getBytes("US-ASCII")))
                    .equals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM")));
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      KeyPair pair = generator.generateKeyPair();
      RSAPublicKey pub = (RSAPublicKey) pair.getPublic();
      JSONArray keys =
          Json.arr(
              Json.obj(
                  "kid",
                  "test",
                  "kty",
                  "RSA",
                  "n",
                  ChatAuth.b64(pub.getModulus().toByteArray()),
                  "e",
                  ChatAuth.b64(pub.getPublicExponent().toByteArray())));
      JSONObject claims =
          Json.obj(
              "iss",
              "https://auth.openai.com",
              "aud",
              "oaiapp_test",
              "sub",
              "test-user",
              "nonce",
              "nonce",
              "exp",
              System.currentTimeMillis() / 1000 + 3600);
      String jwt = signed(pair, claims);
      test(
          "OIDC valid signed identity",
          () ->
              yes(
                  ChatAuth.verifyWithKeys(jwt, "oaiapp_test", "nonce", keys)
                      .getString("sub")
                      .equals("test-user")));
      test(
          "OIDC wrong audience rejected",
          () -> rejects(() -> ChatAuth.verifyWithKeys(jwt, "other", "nonce", keys)));
      test(
          "OIDC wrong nonce rejected",
          () -> rejects(() -> ChatAuth.verifyWithKeys(jwt, "oaiapp_test", "other", keys)));
      test(
          "OIDC tampered signature rejected",
          () ->
              rejects(
                  () ->
                      ChatAuth.verifyWithKeys(
                          jwt.substring(0, jwt.length() - 10) + "AAAAAAAAAA",
                          "oaiapp_test",
                          "nonce",
                          keys)));
      test(
          "OIDC expired identity rejected",
          () -> {
            JSONObject expired = new JSONObject(claims.toString());
            expired.put("exp", 1);
            rejects(
                () -> ChatAuth.verifyWithKeys(signed(pair, expired), "oaiapp_test", "nonce", keys));
          });
      test(
          "OIDC wrong issuer rejected",
          () -> {
            JSONObject bad = new JSONObject(claims.toString());
            bad.put("iss", "https://evil.example");
            rejects(() -> ChatAuth.verifyWithKeys(signed(pair, bad), "oaiapp_test", "nonce", keys));
          });
    } catch (Exception e) {
      failed++;
      report.append("FAIL OIDC test setup: ").append(e.getMessage()).append('\n');
    }
    test(
        "plan scope required",
        () ->
            yes(
                !ChatAuth.hasPlan(Json.obj("scope", "openid email"))
                    && ChatAuth.hasPlan(Json.obj("scope", "openid chatgpt.tokens.use.direct"))));
    test(
        "readable local responses",
        () ->
            yes(
                LocalFormatter.format(
                        "device_info",
                        Json.obj(
                            "model",
                            "Test TV",
                            "manufacturer",
                            "Test",
                            "android",
                            "9",
                            "online",
                            true,
                            "navigation_enabled",
                            true))
                    .contains("Screen navigation: enabled")));
    test(
        "playback permission works",
        () -> yes(!tools.execute("list_sessions", Json.obj()).has("error")));
    test(
        "ChatGPT plan namespace contract",
        () -> {
          JSONArray api = tools.apiSchemas();
          JSONObject namespace = api.getJSONObject(0);
          yes(
              namespace.getString("type").equals("namespace")
                  && namespace.getString("name").equals("tv")
                  && namespace.getJSONArray("tools").length() > 0);
        });
    test(
        "stopped inference never opens a request",
        () -> rejects(() -> Net.stream(null, Json.obj(), events(true))));
    test(
        "large tool output stays valid JSON",
        () -> {
          JSONArray nodes = new JSONArray();
          for (int i = 0; i < 180; i++)
            nodes.put(Json.obj("id", i, "text", new String(new char[200]).replace('\0', 'x')));
          String output =
              AssistantEngine.toolOutput(
                  Json.obj(
                      "snapshot",
                      "test",
                      "text",
                      new String(new char[9000]).replace('\0', 'y'),
                      "nodes",
                      nodes));
          JSONObject parsed = new JSONObject(output);
          yes(
              output.length() <= 16000
                  && parsed.optBoolean("truncated")
                  && parsed.optString("snapshot").equals("test"));
        });
    test(
        "internal browser local route",
        () ->
            yes(
                LocalCommands.parse("Browse example.com", apps)
                    .args
                    .getString("browser")
                    .equals("internal")));
    try {
      JSONObject launched =
          tools.execute("open_url", Json.obj("url", "https://example.com", "browser", "internal"));
      if (launched.has("error")) throw new IllegalStateException(launched.optString("error"));
      for (int i = 0; i < 30 && BrowserActivity.current.get() == null; i++) Thread.sleep(100);
      host.ui(
          () -> {
            BrowserActivity b = BrowserActivity.current.get();
            if (b == null) throw new IllegalStateException("Browser did not open");
            b.web.stopLoading();
            b.web.loadDataWithBaseURL(
                "https://example.com/",
                "<h1>TV test page</h1><input aria-label='Search'><button"
                    + " onclick=\"document.querySelector('h1').innerText='Clicked'\">Find</button><input"
                    + " type='password' value='secret'><button disabled>Disabled</button>",
                "text/html",
                "UTF-8",
                null);
            return true;
          });
      JSONObject loaded = new JSONObject();
      for (int attempt = 0; attempt < 30; attempt++) {
        Thread.sleep(200);
        loaded = tools.execute("browser_read", Json.obj());
        if (loaded.optString("text").contains("TV test page") && !loaded.has("error")) break;
      }
      final JSONObject page = loaded;
      test(
          "real WebView page extraction",
          () -> yes(page.optString("text").contains("TV test page") && !page.has("error")));
      JSONArray nodes = page.getJSONArray("nodes");
      int field = 0, button = 0, password = 0, disabled = 0;
      for (int i = 0; i < nodes.length(); i++) {
        JSONObject n = nodes.getJSONObject(i);
        if (n.optString("label").equals("Search")) field = n.getInt("id");
        if (n.optString("label").equals("Find")) button = n.getInt("id");
        if (n.optString("label").equals("Disabled")) disabled = n.getInt("id");
        if (n.optBoolean("password")) password = n.getInt("id");
      }
      final int inputId = field, buttonId = button, passwordId = password, disabledId = disabled;
      String snap = page.getString("snapshot");
      test(
          "browser password redaction",
          () -> yes(passwordId > 0 && !page.toString().contains("secret")));
      test(
          "browser stale snapshot rejected",
          () ->
              yes(
                  tools
                      .execute(
                          "browser_action",
                          Json.obj(
                              "snapshot", "old", "id", inputId, "action", "type", "text", "hello"))
                      .has("error")));
      test(
          "browser password typing rejected",
          () ->
              yes(
                  tools
                      .execute(
                          "browser_action",
                          Json.obj(
                              "snapshot", snap, "id", passwordId, "action", "type", "text", "test"))
                      .has("error")));
      test(
          "browser text entry",
          () ->
              yes(
                  !tools
                      .execute(
                          "browser_action",
                          Json.obj(
                              "snapshot", snap, "id", inputId, "action", "type", "text", "hello"))
                      .has("error")));
      test(
          "browser disabled click rejected",
          () ->
              yes(
                  tools
                      .execute(
                          "browser_action",
                          Json.obj("snapshot", snap, "id", disabledId, "action", "click"))
                      .has("error")));
      test(
          "browser click",
          () ->
              yes(
                  !tools
                      .execute(
                          "browser_action",
                          Json.obj("snapshot", snap, "id", buttonId, "action", "click"))
                      .has("error")));
      JSONObject after = tools.execute("browser_read", Json.obj());
      test(
          "browser action verified in DOM",
          () ->
              yes(
                  after.optString("text").contains("Clicked")
                      && after.toString().contains("hello")));
      test("batched real WebView actions and verified workflow reuse", () -> {
        JSONArray plan = Json.arr(
            Json.obj("tool", "ui_target", "args", Json.obj("scope", "browser", "context", "https://example.com", "label", "Find", "action", "click")),
            Json.obj("tool", "ui_target", "args", Json.obj("scope", "browser", "context", "https://example.com", "label", "Clicked", "action", "wait")));
        JSONObject first = tools.execute("action_plan", Json.obj("steps", plan.toString(), "cache_name", "test-verified-path"));
        yes(!first.has("error") && !first.optBoolean("stopped_early") && first.has("cached_workflow"));
        JSONObject repeat = tools.execute("workflow", Json.obj("action", "run", "name", "test-verified-path"));
        yes(repeat.optBoolean("cache_hit") && tools.cacheHits() == 1);
        // Mutate the actual DOM: a cached path must stop, not click a replacement control.
        host.ui(() -> { BrowserActivity.current.get().web.evaluateJavascript("document.querySelector('button').innerText='Different'", null); return true; });
        JSONObject changed = tools.execute("workflow", Json.obj("action", "run", "name", "test-verified-path"));
        yes(changed.optBoolean("stopped_early") && !changed.optBoolean("cache_hit"));
        tools.execute("workflow", Json.obj("action", "remove", "name", "test-verified-path"));
      });
      test(
          "browser node filtering",
          () -> {
            JSONObject filtered = tools.execute("browser_read", Json.obj("query", "Search"));
            yes(
                filtered.getJSONArray("nodes").length() == 1
                    && filtered
                        .getJSONArray("nodes")
                        .getJSONObject(0)
                        .optString("label")
                        .equals("Search"));
          });
      final BrowserActivity foreground = BrowserActivity.current.get();
      PublicBrowser isolated = host.ui(() -> {
        PublicBrowser view = new PublicBrowser(c);
        view.web.loadDataWithBaseURL("https://example.com/",
            "<meta name='viewport' content='width=device-width'><style>@media(max-width:1000px){input[aria-label=Query]{display:none}}</style>"
                + "<h1>Public fixture</h1><input aria-label='Query' oninput=\"document.querySelector('h1').innerText='input:'+this.value\""
                + " onchange=\"document.querySelector('a').innerText='Result:'+this.value\"><a href='https://example.com/verified/'>Pending</a>"
                + "<input type='password' value='private-secret'><button disabled>Blocked</button>"
                + "<svg class='TestIcon' style='width:40px;height:40px;cursor:pointer' onclick=\"document.querySelector('h1').innerText='Icon clicked'\"></svg>", "text/html", "UTF-8", null);
        return view;
      });
      java.lang.reflect.Field fieldPublic = Tools.class.getDeclaredField("publicBrowser");
      fieldPublic.setAccessible(true); fieldPublic.set(tools, isolated);
      JSONObject isolatedLoaded = new JSONObject();
      for (int i = 0; i < 30; i++) {
        Thread.sleep(100);
        isolatedLoaded = tools.execute("web_page", Json.obj("action", "read"));
        if (isolatedLoaded.optString("text").contains("Public fixture")) break;
      }
      final JSONObject isolatedPage = isolatedLoaded;
      test("isolated public page extracts DOM without launching an activity", () -> {
        yes(isolatedPage.optString("text").contains("Public fixture") && !isolatedPage.toString().contains("private-secret"));
        yes(BrowserActivity.current.get() == foreground);
      });
      JSONObject input = Tools.uniqueTarget(isolatedPage, "Query", false, true, true);
      test("public TV viewport exposes desktop search controls", () -> yes(input != null && isolatedPage.getJSONObject("viewport").getInt("width") >= 1000));
      final int publicInputId = input.getInt("id");
      test("public typing dispatches input and change and yields a real result link", () -> {
        JSONObject result = tools.execute("web_page", Json.obj("action", "type", "snapshot", isolatedPage.getString("snapshot"),
            "id", publicInputId, "text", "wire"));
        yes(result.optString("text").contains("input:wire") && result.optString("text").contains("Result:wire"));
        JSONObject link = Tools.uniqueTarget(result, "Result:wire", false, true, false);
        yes(link.optString("href").equals("https://example.com/verified/"));
      });
      test("public stale, password, and disabled actions are rejected", () -> {
        yes(tools.execute("web_page", Json.obj("action", "type", "snapshot", isolatedPage.getString("snapshot"), "id", publicInputId, "text", "wrong")).has("error"));
        JSONObject fresh = tools.execute("web_page", Json.obj("action", "read"));
        JSONArray publicNodes = fresh.getJSONArray("nodes");
        for (int i = 0; i < publicNodes.length(); i++) {
          JSONObject node = publicNodes.getJSONObject(i);
          if (node.optBoolean("password") || node.optBoolean("disabled"))
            yes(tools.execute("web_page", Json.obj("action", "click", "snapshot", fresh.getString("snapshot"), "id", node.getInt("id"))).has("error"));
        }
        yes(tools.execute("web_page", Json.obj("action", "open", "url", "file:///private")).has("error"));
        yes(tools.execute("web_page", Json.obj("action", "open", "url", "https://auth.openai.com/")).has("error"));
      });
      test("public SVG controls are observed and clicked with fresh identity", () -> {
        JSONObject fresh = tools.execute("web_page", Json.obj("action", "read"));
        JSONObject icon = Tools.uniqueTarget(fresh, "TestIcon", false, true, false);
        yes(icon != null);
        JSONObject clicked = tools.execute("web_page", Json.obj("action", "click", "snapshot", fresh.getString("snapshot"), "id", icon.getInt("id")));
        yes(clicked.optString("text").contains("Icon clicked"));
      });
      // Asynchronous search fixture: an input value is observable before real results arrive.
      host.ui(() -> {
        isolated.web.loadDataWithBaseURL("https://example.com/",
            "<h1>Search fixture</h1><button onclick=\"document.querySelector('input').style.display='block'\">Search</button>"
                + "<input style='display:none' aria-label='Query' oninput=\"document.querySelector('h2').innerText='Pending';clearTimeout(window.pending);const q=this.value;window.pending=setTimeout(()=>{document.querySelector('h2').innerText='Results: '+q;document.querySelector('a').innerText='Title: '+q},650)\">"
                + "<h2>Pending</h2><a href='https://example.com/episode/1'>No result</a>", "text/html", "UTF-8", null);
        return true;
      });
      tools.execute("web_page", Json.obj("action", "read", "wait_text", "Search fixture", "timeout_ms", 4000));
      JSONArray searchPlan = Json.arr(
          Json.obj("tool", "ui_target", "args", Json.obj("scope", "public", "context", "https://example.com", "label", "Search", "action", "click")),
          Json.obj("tool", "ui_target", "args", Json.obj("scope", "public", "context", "https://example.com", "label", "Query", "action", "type", "text", "$param.title")),
          Json.obj("tool", "ui_target", "args", Json.obj("scope", "public", "context", "https://example.com", "label", Json.obj("$param", "heading"), "match", "text", "action", "wait", "timeout_ms", 2000)));
      test("public search batches fresh actions and waits for delayed real results", () -> {
        long start = android.os.SystemClock.elapsedRealtime();
        JSONObject result = tools.execute("action_plan", Json.obj("steps", searchPlan.toString(), "parameters", Json.obj("title", "Wire", "heading", "Results: Wire").toString(), "cache_name", "public-search-fixture"));
        long elapsed = android.os.SystemClock.elapsedRealtime() - start;
        yes(!result.has("error") && !result.optBoolean("stopped_early") && result.has("cached_workflow"));
        JSONObject observed = result.getJSONArray("results").getJSONObject(2).getJSONObject("observation");
        yes(observed.optString("text").contains("Title: Wire") && elapsed >= 600 && elapsed < 2000);
        report.append("Public search batch: ").append(elapsed).append(" ms; fixture delay650ms\n");
      });
      test("cached public search uses new parameters without retaining typed values", () -> {
        JSONObject stored = tools.execute("workflow", Json.obj("action", "list"));
        yes(!stored.toString().contains("Results: Wire") && !stored.toString().contains("\"Wire\""));
        JSONObject result = tools.execute("workflow", Json.obj("action", "run", "name", "public-search-fixture", "parameters", Json.obj("title", "Another show", "heading", "Results: Another show").toString()));
        yes(result.optBoolean("cache_hit") && result.getJSONArray("results").getJSONObject(2).getJSONObject("observation").optString("text").contains("Title: Another show"));
        yes(BrowserActivity.current.get() == foreground);
      });
      final String[] learnedSearchName = {""};
      test("successful literal public searches learn without an explicit cache name", () -> {
        JSONArray literal = new JSONArray(searchPlan.toString());
        literal.getJSONObject(1).getJSONObject("args").put("text", "Severance");
        literal.getJSONObject(2).getJSONObject("args").put("label", "Results: Severance");
        JSONObject result = tools.execute("action_plan", Json.obj("steps", literal.toString()));
        yes(!result.has("error") && !result.optBoolean("stopped_early") && result.optBoolean("learned_automatically"));
        learnedSearchName[0] = result.getString("cached_workflow");
        JSONObject saved = tools.execute("workflow", Json.obj("action", "list")).getJSONObject("workflows").getJSONObject(learnedSearchName[0]);
        yes(!saved.toString().contains("Severance") && saved.optString("kind").equals("public_search"));
      });
      test("automatically learned searches replay with query only and verify fresh results", () -> {
        JSONObject result = tools.execute("workflow", Json.obj("action", "run", "name", learnedSearchName[0],
            "parameters", Json.obj("query", "Better Call Saul", "result", "Pending").toString()));
        yes(result.optBoolean("cache_hit") && result.getJSONArray("results").getJSONObject(2).getJSONObject("observation").optString("text").contains("Title: Better Call Saul"));
        yes(BrowserActivity.current.get() == foreground);
        yes(tools.execute("workflow", Json.obj("action", "run", "name", learnedSearchName[0])).has("error"));
      });
      test("public search drift and missing parameters stop before later actions", () -> {
        JSONObject missing = tools.execute("workflow", Json.obj("action", "run", "name", "public-search-fixture", "parameters", Json.obj("title", "wrong").toString()));
        yes(missing.has("error"));
        host.ui(() -> { isolated.web.evaluateJavascript("document.querySelector('button').innerText='Changed';document.querySelector('input').value='unchanged'", null); return true; });
        JSONObject changed = tools.execute("workflow", Json.obj("action", "run", "name", "public-search-fixture", "parameters", Json.obj("title", "wrong", "heading", "Results: wrong").toString()));
        yes(changed.optBoolean("stopped_early") && changed.optInt("completed_steps") == 0 && !changed.optBoolean("cache_hit"));
        JSONObject observed = tools.execute("web_page", Json.obj("action", "read"));
        yes(Tools.uniqueTarget(observed, "Query", false, true, true).optString("value").equals("unchanged"));
        JSONObject learnedDrift = tools.execute("workflow", Json.obj("action", "run", "name", learnedSearchName[0],
            "parameters", Json.obj("query", "New title").toString()));
        yes(learnedDrift.optBoolean("stopped_early") && learnedDrift.optInt("completed_steps") == 0 && !learnedDrift.optBoolean("cache_hit"));
        tools.execute("workflow", Json.obj("action", "remove", "name", learnedSearchName[0]));
      });
      test("public label actions reject changed origin and ambiguity", () -> {
        yes(tools.execute("ui_target", Json.obj("scope", "public", "context", "https://other.com", "label", "Query", "action", "type", "text", "wrong")).has("error"));
        host.ui(() -> { isolated.web.evaluateJavascript("document.body.insertAdjacentHTML('beforeend','<input aria-label=Query>')", null); return true; });
        yes(tools.execute("ui_target", Json.obj("scope", "public", "context", "https://example.com", "label", "Query", "action", "type", "text", "wrong")).has("error"));
        host.ui(() -> { isolated.web.evaluateJavascript("document.querySelector('input:last-child').remove()", null); return true; });
      });
      test("public readiness stops promptly and cannot verify an input value", () -> {
        JSONObject fresh = tools.execute("web_page", Json.obj("action", "read"));
        JSONObject inputNode = Tools.uniqueTarget(fresh, "Query", false, true, true);
        long start = android.os.SystemClock.elapsedRealtime();
        JSONObject timeout = tools.execute("web_page", Json.obj("action", "type", "snapshot", fresh.getString("snapshot"), "id", inputNode.getInt("id"), "text", "waiting", "wait_text", "Results: waiting", "timeout_ms", 150));
        yes(timeout.has("error") && !timeout.optBoolean("settled") && android.os.SystemClock.elapsedRealtime() - start < 600);
        yes(tools.execute("ui_target", Json.obj("scope", "public", "context", "https://example.com", "label", "missing result", "match", "text", "action", "wait", "timeout_ms", 0)).has("error"));
        JSONObject done = tools.execute("web_page", Json.obj("action", "read", "wait_text", "Results: waiting", "timeout_ms", 2000));
        yes(!done.has("error") && done.optBoolean("settled") && done.optString("text").contains("Title: waiting"));
      });
      test("raw public page steps batch observed snapshots without extra model calls", () -> {
        JSONArray direct = Json.arr(Json.obj("tool", "web_page", "args", Json.obj("action", "read")),
            Json.obj("tool", "web_page", "args", Json.obj("action", "read", "wait_text", "Results: waiting", "timeout_ms", 1000)));
        JSONObject result = tools.execute("action_plan", Json.obj("steps", direct.toString()));
        yes(!result.has("error") && result.optInt("completed_steps") == 2);
      });
      test("public readiness cancellation interrupts local polling", () -> {
        final java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        Tools cancellable = new Tools(c, new Tools.Host() {
          public <T> T ui(Callable<T> task) throws Exception { return host.ui(task); }
          public boolean approve(String text) { return false; }
          public void trace(String text) {}
          public void speak(String text) {}
          public boolean cancelled() { return cancelled.get(); }
        });
        cancellable.startTask(4);
        fieldPublic.set(cancellable, isolated);
        Thread stop = new Thread(() -> {
          try { Thread.sleep(150); } catch (InterruptedException ignored) {}
          cancelled.set(true);
        });
        long start = android.os.SystemClock.elapsedRealtime();
        stop.start();
        JSONObject result = cancellable.execute("web_page", Json.obj("action", "read", "wait_text", "Never exists", "timeout_ms", 4000));
        stop.join(1000);
        // This helper borrowed the fixture view; do not destroy the owning Tools' view.
        fieldPublic.set(cancellable, null); cancellable.close();
        yes(result.optString("error").contains("Task stopped") && android.os.SystemClock.elapsedRealtime() - start < 1000);
      });
      tools.execute("workflow", Json.obj("action", "remove", "name", "public-search-fixture"));
      host.ui(() -> {
        BrowserActivity b = BrowserActivity.current.get();
        if (b != null) b.finish();
        return true;
      });
    } catch (Exception e) {
      failed++;
      report.append("FAIL browser test setup: ").append(e.getMessage()).append('\n');
    }
    test(
        "tool budget enforced",
        () -> {
          tools.startTask(1);
          yes(!tools.execute("clock", Json.obj()).has("error"));
          yes(tools.execute("clock", Json.obj()).has("error"));
        });
    tools.close();
    Bundle result = new Bundle();
    result.putString("stream", report + "\n" + passed + " passed, " + failed + " failed\n");
    finish(failed == 0 ? -1 : 0, result);
  }
}
