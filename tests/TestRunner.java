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
  private final StringBuilder report = new StringBuilder();

  @Override
  public void onCreate(Bundle args) {
    super.onCreate(args);
    youtubeOnly = args != null && "youtube".equals(args.getString("group"));
    benchmarkOnly = args != null && "benchmark".equals(args.getString("group"));
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

  @Override
  public void onStart() {
    if (benchmarkOnly) { benchmark(); return; }
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
      host.ui(
          () -> {
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
