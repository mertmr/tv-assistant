package dev.mert.tvassistant;

import java.io.*;
import org.json.*;

/** Offline JVM checks. No Android activity, TV, account, or inference calls. */
public final class HostChecks {
  interface Check { void run() throws Exception; }
  static int passed;
  static void check(String name, Check check) throws Exception {
    check.run(); passed++; System.out.println("PASS " + name);
  }
  static void yes(boolean value) { if (!value) throw new AssertionError(); }
  static void rejects(Check check) throws Exception {
    try { check.run(); } catch (Exception expected) { return; }
    throw new AssertionError("Expected rejection");
  }
  static Net.Events events(boolean cancelled) {
    return new Net.Events() {
      public void event(JSONObject event) {}
      public boolean cancelled() { return cancelled; }
    };
  }
  static ActionPlan.Runner runner(int[] calls, JSONObject response, boolean cancelled) {
    return new ActionPlan.Runner() {
      public void validate(String tool, JSONObject args) {}
      public JSONObject run(String tool, JSONObject args) { calls[0]++; return response; }
      public boolean cancelled() { return cancelled; }
    };
  }
  static JSONArray plan() {
    return Json.arr(Json.obj("tool", "clock", "args", Json.obj()),
        Json.obj("tool", "calculate", "args", Json.obj("expression", "7*8")));
  }
  public static void main(String[] args) throws Exception {
    check("WebSocket delta payload excludes stream and includes previous response", () -> {
      JSONArray input = Json.arr(Json.obj("role", "user", "content", "old"), Json.obj("type", "function_call_output", "call_id", "call1", "output", "{}"));
      JSONObject payload = ResponseSocket.payload(Json.obj("stream", true, "store", false), input, 1, "resp1");
      yes(!payload.has("stream") && payload.getString("type").equals("response.create") && !payload.getBoolean("store")
          && payload.getJSONArray("input").length() == 1 && payload.getString("previous_response_id").equals("resp1"));
    });
    check("WebSocket new chain sends full context", () -> {
      JSONObject payload = ResponseSocket.payload(Json.obj("stream", true), plan(), 0, "");
      yes(!payload.has("previous_response_id") && payload.getJSONArray("input").length() == 2);
    });
    check("shared event accumulator restores terminal output in order", () -> {
      Net.Accumulator parser = new Net.Accumulator(events(false));
      parser.accept(Json.obj("type", "response.output_item.done", "output_index", 1, "item", Json.obj("type", "message", "id", "second")));
      parser.accept(Json.obj("type", "response.output_item.done", "output_index", 0, "item", Json.obj("type", "function_call", "id", "first")));
      JSONObject response = parser.accept(Json.obj("type", "response.completed", "response", Json.obj("id", "resp1", "output", Json.arr())));
      yes(response.getJSONArray("output").length() == 2 && response.getJSONArray("output").getJSONObject(0).getString("id").equals("first"));
    });
    check("shared accumulator preserves supplied terminal output without duplication", () -> {
      Net.Accumulator parser = new Net.Accumulator(events(false));
      parser.accept(Json.obj("type", "response.output_item.done", "output_index", 0, "item", Json.obj("id", "item")));
      JSONObject done = parser.accept(Json.obj("type", "response.completed", "response", Json.obj("output", Json.arr(Json.obj("id", "item")))));
      yes(done.getJSONArray("output").length() == 1);
    });
    check("shared accumulator rejects failed, incomplete, error, cancelled", () -> {
      for (String type : new String[] {"response.failed", "response.incomplete", "error"})
        rejects(() -> new Net.Accumulator(events(false)).accept(Json.obj("type", type)));
      rejects(() -> new Net.Accumulator(events(true)).accept(Json.obj("type", "response.completed")));
    });
    check("SSE completion and missing completion", () -> {
      JSONObject result = Net.parseStream(new BufferedReader(new StringReader("data: {\"type\":\"response.completed\",\"response\":{\"output\":[]}}\n\n")), events(false));
      yes(result.getJSONArray("output").length() == 0);
      rejects(() -> Net.parseStream(new BufferedReader(new StringReader("data: {\"type\":\"response.created\"}\n\n")), events(false)));
    });
    check("plan scalar parameters and nested references", () -> {
      JSONObject resolved = (JSONObject) ActionPlan.resolve(Json.obj("id", Json.obj("$ref", "0.results.0.id"), "title", Json.obj("$param", "title")),
          Json.arr(Json.obj("results", Json.arr(Json.obj("id", "tt1")))), Json.obj("title", "The Wire"));
      yes(resolved.getString("id").equals("tt1") && resolved.getString("title").equals("The Wire"));
      rejects(() -> ActionPlan.resolve(Json.obj("$param", "missing"), Json.arr(), Json.obj()));
      rejects(() -> ActionPlan.resolve(Json.obj("$param", "nested"), Json.arr(), Json.obj("nested", Json.obj())));
    });
    check("plan rejects recursion, forward refs, unauthorized tools and oversize", () -> {
      for (String tool : new String[] {"action_plan", "routines", "workflow", "notes", "preferences"})
        rejects(() -> ActionPlan.validate(Json.arr(Json.obj("tool", tool, "args", Json.obj()))));
      rejects(() -> ActionPlan.validate(Json.arr(Json.obj("tool", "clock", "args", Json.obj("key", Json.obj("$ref", "0.value"))))));
      JSONArray tooMany = new JSONArray(); for (int i = 0; i < 9; i++) tooMany.put(plan().getJSONObject(0));
      rejects(() -> ActionPlan.validate(tooMany));
    });
    check("plan stops on failed action and cancellation", () -> {
      int[] calls = {0};
      JSONObject result = ActionPlan.run(plan(), Json.obj(), runner(calls, Json.obj("performed", false), false));
      yes(calls[0] == 1 && result.getBoolean("stopped_early") && result.getInt("completed_steps") == 0);
      calls[0] = 0;
      result = ActionPlan.run(plan(), Json.obj(), runner(calls, Json.obj(), true));
      yes(calls[0] == 0 && result.getBoolean("stopped_early"));
    });
    check("plan stops on partial keyboard delivery", () -> {
      int[] calls = {0};
      JSONObject result = ActionPlan.run(plan(), Json.obj(), runner(calls, Json.obj("keys_requested", 4, "keys_delivered", 2), false));
      yes(calls[0] == 1 && result.getBoolean("stopped_early"));
    });
    check("successful plan keeps step count and parameters", () -> {
      int[] calls = {0};
      JSONObject result = ActionPlan.run(plan(), Json.obj(), runner(calls, Json.obj("value", 56), false));
      yes(calls[0] == 2 && !result.getBoolean("stopped_early") && result.getInt("completed_steps") == 2);
    });
    check("fresh labels reject ambiguity, disabled and password fields", () -> {
      JSONObject target = Json.obj("id", 1, "tag", "BUTTON", "label", "Find");
      yes(Tools.uniqueTarget(Json.obj("nodes", Json.arr(target)), "find", false, true, false).getInt("id") == 1);
      rejects(() -> Tools.uniqueTarget(Json.obj("nodes", Json.arr(target, target)), "find", false, true, false));
      yes(Tools.uniqueTarget(Json.obj("nodes", Json.arr(Json.obj("label", "Search", "password", true))), "Search", false, true, false) == null);
      yes(Tools.uniqueTarget(Json.obj("nodes", Json.arr(Json.obj("label", "Find", "disabled", true))), "Find", false, true, false) == null);
    });
    check("native targets exclude zero-size bounds and noneditable typing", () -> {
      yes(Tools.uniqueTarget(Json.obj("nodes", Json.arr(Json.obj("text", "Find", "bounds", Json.arr(0,0,0,0)))), "Find", false, false, false) == null);
      yes(Tools.uniqueTarget(Json.obj("nodes", Json.arr(Json.obj("text", "Find", "bounds", Json.arr(1,1,100,100)))), "Find", false, false, true) == null);
    });
    check("scope guards reject changed app/origin and accept normalized HTTPS ports", () -> {
      Tools.checkScope(Json.obj("package", "test.app"), "native", "test.app");
      rejects(() -> Tools.checkScope(Json.obj("package", "other.app"), "native", "test.app"));
      Tools.checkScope(Json.obj("url", "https://example.com/path"), "browser", "https://example.com:443");
      rejects(() -> Tools.checkScope(Json.obj("url", "https://other.com/path"), "browser", "https://example.com"));
      rejects(() -> Tools.checkScope(Json.obj("url", "https://example.com/path"), "browser", "https://example.com/path"));
      rejects(() -> Tools.checkScope(Json.obj("url", "https://example.com/path"), "browser", "https://user@example.com"));
    });
    check("reusable plans require scoped clicks and terminal verification", () -> {
      JSONArray reusable = Json.arr(Json.obj("tool", "ui_target", "args", Json.obj("scope", "browser", "context", "https://example.com", "label", Json.obj("$param", "title"), "action", "wait")));
      ActionPlan.validateReusable(reusable);
      rejects(() -> ActionPlan.validateReusable(plan()));
      reusable.getJSONObject(0).getJSONObject("args").put("action", "click");
      rejects(() -> ActionPlan.validateReusable(reusable));
      reusable.getJSONObject(0).getJSONObject("args").put("action", "type");
      rejects(() -> ActionPlan.validateReusable(reusable));
    });
    check("microphone probe distinguishes no samples from zero PCM", () -> {
      MicrophoneLevels levels = new MicrophoneLevels();
      yes(levels.samples() == 0 && levels.rms() == 0 && levels.observation().contains("No PCM"));
      levels.accept(new short[160], 160);
      yes(levels.samples() == 160 && levels.nonzero() == 0 && levels.peak() == 0
          && levels.durationMs(16000) == 10 && levels.observation().contains("silent"));
    });
    check("microphone PCM handles both full-scale extremes without overflow", () -> {
      MicrophoneLevels levels = new MicrophoneLevels();
      levels.accept(new short[] {Short.MIN_VALUE, Short.MAX_VALUE}, 2);
      yes(levels.peak() == 32768 && levels.nonzero() == 2
          && levels.rms() > 32767 && levels.rms() < 32768);
    });
    check("microphone levels include quiet chunks, zeros and all samples", () -> {
      MicrophoneLevels levels = new MicrophoneLevels();
      levels.accept(new short[] {1000, -1000, 9999}, 2);
      levels.accept(new short[] {0, 0}, 2);
      yes(levels.samples() == 4 && levels.nonzero() == 2 && levels.peak() == 1000
          && Math.abs(levels.rms() - Math.sqrt(500000)) < 0.001
          && levels.observation().contains("does not establish"));
    });
    check("microphone level validation rejects invalid buffer bounds and rates", () -> {
      MicrophoneLevels levels = new MicrophoneLevels();
      rejects(() -> levels.accept(new short[1], 2));
      rejects(() -> levels.accept(new short[1], -1));
      rejects(() -> levels.durationMs(0));
      rejects(() -> levels.durationMs(-1));
      yes(levels.samples() == 0);
    });
    check("microphone source preferences are bounded and cycle safely", () -> {
      yes(MicrophoneProbe.sourceIndex(-1) == 0 && MicrophoneProbe.sourceIndex(999) == 0
          && MicrophoneProbe.sourceName(999).equals("VOICE_RECOGNITION")
          && MicrophoneProbe.nextSource(3) == 0 && MicrophoneProbe.nextSource(-1) == 1);
    });
    System.out.println(passed + " passed, 0 failed (offline JVM checks; no device)");
  }
}
