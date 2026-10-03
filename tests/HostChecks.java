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
  static JSONArray search(String query, String heading) {
    return Json.arr(
        Json.obj("tool", "ui_target", "args", Json.obj("scope", "public", "context", "https://example.com", "label", "Search", "action", "click")),
        Json.obj("tool", "ui_target", "args", Json.obj("scope", "public", "context", "https://example.com", "label", "Title", "action", "type", "text", query)),
        Json.obj("tool", "ui_target", "args", Json.obj("scope", "public", "context", "https://example.com", "label", heading, "match", "text", "action", "wait")));
  }
  static JSONObject searchResult() {
    return Json.obj("stopped_early", false, "completed_steps", 3,
        "results", Json.arr(Json.obj("performed", true), Json.obj("performed", true, "text_verified", true), Json.obj("verified", true)));
  }
  public static void main(String[] args) throws Exception {
    check("automatic learning replaces literals and derives verification for a new query", () -> {
      JSONObject learned = LearnedSearch.learn(search("The Wire", "Results: The Wire"), Json.obj(), searchResult());
      yes(learned != null && !learned.toString().contains("The Wire"));
      JSONObject workflow = learned.getJSONObject("workflow");
      JSONObject params = LearnedSearch.parameters(workflow, Json.obj("query", "Another show", "result", "Pending"));
      yes(params.getString("result").equals("Results: Another show"));
      JSONArray resolved = (JSONArray) ActionPlan.resolve(workflow.getJSONArray("steps"), new JSONArray(), params);
      yes(resolved.getJSONObject(1).getJSONObject("args").getString("text").equals("Another show"));
      yes(resolved.getJSONObject(2).getJSONObject("args").getString("label").equals("Results: Another show"));
      yes(learned.getString("name").equals(LearnedSearch.learn(search("Severance", "Results: Severance"), Json.obj(), searchResult()).getString("name")));
      rejects(() -> LearnedSearch.parameters(workflow, Json.obj()));
      rejects(() -> LearnedSearch.parameters(workflow, Json.obj("query", " ")));
      rejects(() -> LearnedSearch.parameters(workflow, Json.obj("query", 7)));
    });
    check("automatic learning accepts parameterized searches without retaining their values", () -> {
      JSONArray source = search("$param.title", "Results: placeholder");
      source.getJSONObject(2).getJSONObject("args").put("label", Json.obj("$param", "heading"));
      JSONObject learned = LearnedSearch.learn(source, Json.obj("title", "Severance", "heading", "Results: Severance"), searchResult());
      yes(learned != null && !learned.toString().contains("Severance"));
      yes(source.getJSONObject(1).getJSONObject("args").getString("text").equals("$param.title"));
    });
    check("automatic learning rejects failed, unverified and unrelated result headings", () -> {
      JSONObject failed = searchResult().put("stopped_early", true);
      yes(LearnedSearch.learn(search("Severance", "Results: Severance"), Json.obj(), failed) == null);
      failed = searchResult(); failed.getJSONArray("results").getJSONObject(1).put("text_verified", false);
      yes(LearnedSearch.learn(search("Severance", "Results: Severance"), Json.obj(), failed) == null);
      failed = searchResult(); failed.getJSONArray("results").getJSONObject(2).put("verified", false);
      yes(LearnedSearch.learn(search("Severance", "Results: Severance"), Json.obj(), failed) == null);
      yes(LearnedSearch.learn(search("Severance", "Pending"), Json.obj(), searchResult()) == null);
      yes(LearnedSearch.learn(search("Severance", "Severance Severance"), Json.obj(), searchResult()) == null);
    });
    check("automatic learning rejects mixed origins, field-value verification and query-bearing controls", () -> {
      JSONArray source = search("Severance", "Results: Severance");
      source.getJSONObject(2).getJSONObject("args").put("context", "https://other.com");
      yes(LearnedSearch.learn(source, Json.obj(), searchResult()) == null);
      source = search("Severance", "Results: Severance"); source.getJSONObject(2).getJSONObject("args").put("match", "exact");
      yes(LearnedSearch.learn(source, Json.obj(), searchResult()) == null);
      source = search("Severance", "Results: Severance"); source.getJSONObject(0).getJSONObject("args").put("label", "Severance");
      yes(LearnedSearch.learn(source, Json.obj(), searchResult()) == null);
    });
    check("scroll counts reject fractions and out-of-range values before any actions", () -> {
      JSONObject schema = Json.obj("type", "object", "properties", Json.obj("count",
          Json.obj("type", "integer", "minimum", 1, "maximum", 4)), "required", Json.arr());
      ToolArguments.validate(schema, Json.obj("count", 4));
      for (double value : new double[] {0, 5, 1.5, Double.NaN})
        rejects(() -> ToolArguments.validate(schema, Json.obj("count", value)));
    });
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
    check("plan preflight rejects invalid later literals and missing parameters", () -> {
      JSONObject schema = Json.obj("type", "object", "properties", Json.obj(
          "action", Json.obj("type", "string", "enum", Json.arr("click", "wait")),
          "timeout_ms", Json.obj("type", "number", "minimum", 0, "maximum", 4000)),
          "required", Json.arr("action"));
      rejects(() -> ToolArguments.preflight(schema, Json.obj("action", 123), Json.obj()));
      rejects(() -> ToolArguments.preflight(schema, Json.obj("action", "delete"), Json.obj()));
      rejects(() -> ToolArguments.preflight(schema, Json.obj("action", "click", "timeout_ms", 5000), Json.obj()));
      rejects(() -> ToolArguments.preflight(schema, Json.obj("action", Json.obj("$param", "missing")), Json.obj()));
      rejects(() -> ToolArguments.preflight(schema, Json.obj("action", Json.obj("$param", "action")), Json.obj("action", "delete")));
      ToolArguments.preflight(schema, Json.obj("action", Json.obj("$param", "action")), Json.obj("action", "click"));
    });
    check("nested keyboard arguments are checked before delivery", () -> {
      JSONObject item = Json.obj("type", "object", "properties", Json.obj("x", Json.obj("type", "number"),
          "y", Json.obj("type", "number")), "required", Json.arr("x", "y"));
      JSONObject schema = Json.obj("type", "object", "properties", Json.obj("keys", Json.obj("type", "array", "minItems", 1, "maxItems", 32, "items", item)), "required", Json.arr("keys"));
      ToolArguments.validate(schema, Json.obj("keys", Json.arr(Json.obj("x", 10, "y", 20))));
      rejects(() -> ToolArguments.validate(schema, Json.obj("keys", "not an array")));
      rejects(() -> ToolArguments.validate(schema, Json.obj("keys", Json.arr())));
      rejects(() -> ToolArguments.validate(schema, Json.obj("keys", Json.arr(Json.obj("x", "wrong", "y", 20)))));
      rejects(() -> ToolArguments.validate(schema, Json.obj("keys", Json.arr(Json.obj("x", 10)))));
      rejects(() -> ToolArguments.validate(schema, Json.obj("keys", Json.arr(Json.obj("x", 10, "y", 20, "extra", true)))));
    });
    check("deferred references are validated after resolution", () -> {
      JSONObject schema = Json.obj("type", "object", "properties", Json.obj("id", Json.obj("type", "string")), "required", Json.arr("id"));
      JSONObject template = Json.obj("id", Json.obj("$ref", "0.id"));
      ToolArguments.preflight(schema, template, Json.obj());
      JSONObject resolved = (JSONObject) ActionPlan.resolve(template, Json.arr(Json.obj("id", 123)), Json.obj());
      rejects(() -> ToolArguments.validate(schema, resolved));
    });
    check("incorrect text verification stops dependent actions", () -> {
      int[] calls = {0};
      JSONObject result = ActionPlan.run(plan(), Json.obj(), runner(calls, Json.obj("performed", true, "text_verified", false), false));
      yes(calls[0] == 1 && result.getBoolean("stopped_early"));
    });
    check("WebSocket payload preserves source and rejects invalid continuation cursor", () -> {
      JSONArray input = Json.arr(Json.obj("role", "user", "content", "old"), Json.obj("role", "user", "content", "new"));
      JSONObject request = Json.obj("input", input, "stream", true, "background", false, "model", "test-model");
      String before = request.toString();
      JSONObject result = ResponseSocket.payload(request, input, 1, "resp1");
      yes(request.toString().equals(before) && result.getString("model").equals("test-model")
          && !result.has("background") && result.getJSONArray("input").length() == 1);
      rejects(() -> ResponseSocket.payload(request, input, 3, "resp1"));
      rejects(() -> ResponseSocket.payload(request, input, -1, "resp1"));
    });
    check("native typed field follows identity instead of the old value label", () -> {
      JSONObject original = Json.obj("view_id", "null", "bounds", Json.arr(0, 10, 200, 60), "text", "old");
      JSONObject updated = Json.obj("view_id", "null", "bounds", Json.arr(0, 10, 200, 60), "text", "new", "editable", true);
      yes(Tools.typedField(original, Json.obj("nodes", Json.arr(updated))).getString("text").equals("new"));
      rejects(() -> Tools.typedField(original, Json.obj("nodes", Json.arr(updated, updated))));
      updated.put("password", true);
      yes(Tools.typedField(original, Json.obj("nodes", Json.arr(updated))) == null);
    });
    check("public page actions can be batched with fresh result references", () -> {
      ActionPlan.validate(Json.arr(Json.obj("tool", "web_page", "args", Json.obj("action", "read")),
          Json.obj("tool", "web_page", "args", Json.obj("action", "type", "snapshot", Json.obj("$ref", "0.snapshot")))));
    });
    check("cached public searches require parameterized typing and final verification", () -> {
      JSONArray search = Json.arr(
          Json.obj("tool", "ui_target", "args", Json.obj("scope", "public", "context", "https://example.com", "label", "Query", "action", "type", "text", Json.obj("$param", "query"))),
          Json.obj("tool", "ui_target", "args", Json.obj("scope", "public", "context", "https://example.com", "label", Json.obj("$param", "result"), "match", "text", "action", "wait")));
      ActionPlan.validateReusable(search);
      search.getJSONObject(0).getJSONObject("args").put("text", "private literal");
      rejects(() -> ActionPlan.validateReusable(search));
      search.getJSONObject(0).getJSONObject("args").put("text", Json.obj("$ref", "0.text"));
      rejects(() -> ActionPlan.validateReusable(search));
    });
    check("readiness excludes field values and snapshot IDs but detects real results", () -> {
      JSONObject baseline = Json.obj("url", "https://example.com", "text", "Pending", "snapshot", "old",
          "nodes", Json.arr(Json.obj("tag", "INPUT", "label", "Query", "value", "old")));
      PageReadiness ready = new PageReadiness(baseline, true);
      JSONObject typed = new JSONObject(baseline.toString());
      typed.put("snapshot", "fresh").getJSONArray("nodes").getJSONObject(0).put("value", "query");
      yes(!ready.ready(typed, 0) && !ready.ready(typed, 1000));
      yes(!PageReadiness.contains(typed, "query"));
      typed.put("text", "Results: The   Wire");
      yes(!ready.ready(typed, 1100) && ready.ready(typed, 1250));
      yes(PageReadiness.contains(typed, "results: the wire"));
      rejects(() -> PageReadiness.contains(typed, " "));
    });
    check("public origins enforce the same fresh scope guard", () -> {
      Tools.checkScope(Json.obj("url", "https://example.com/search"), "public", "https://example.com");
      rejects(() -> Tools.checkScope(Json.obj("url", "https://other.com"), "public", "https://example.com"));
    });
    check("parameter shorthand resolves and preflights instead of typing a placeholder", () -> {
      yes(ActionPlan.resolve("$param.query", Json.arr(), Json.obj("query", "title")).equals("title"));
      JSONObject schema = Json.obj("type", "object", "properties", Json.obj("text", Json.obj("type", "string")), "required", Json.arr("text"));
      ToolArguments.preflight(schema, Json.obj("text", "$param.query"), Json.obj("query", "title"));
      rejects(() -> ToolArguments.preflight(schema, Json.obj("text", "$param.query"), Json.obj()));
      rejects(() -> ToolArguments.preflight(schema, Json.obj("text", "$param.query"), Json.obj("query", Json.obj())));
      JSONArray search = Json.arr(
          Json.obj("tool", "ui_target", "args", Json.obj("scope", "public", "context", "https://example.com", "label", "Query", "action", "type", "text", "$param.query")),
          Json.obj("tool", "ui_target", "args", Json.obj("scope", "public", "context", "https://example.com", "label", "$param.heading", "action", "wait")));
      ActionPlan.validateReusable(search);
    });
    check("large plans retain final observed links and every step status", () -> {
      JSONArray nodes = new JSONArray();
      for (int i = 0; i < 100; i++) nodes.put(Json.obj("id", i, "label", "Result" + i,
          "href", "https://example.com/" + i, "value", "", "password", false, "disabled", false));
      JSONObject observation = Json.obj("snapshot", "final", "url", "https://example.com", "nodes", nodes,
          "text", new String(new char[9000]).replace('\0', 'x'));
      JSONArray steps = Json.arr(Json.obj("performed", true, "observation", observation),
          Json.obj("verified", true, "observation", observation));
      JSONObject input = Json.obj("completed_steps", 2, "requested_steps", 2, "stopped_early", false, "results", steps);
      JSONObject output = new JSONObject(AssistantEngine.toolOutput(input));
      yes(output.toString().length() <= 16000 && output.getJSONArray("results").length() == 2);
      yes(output.getJSONArray("results").getJSONObject(1).getBoolean("verified"));
      yes(output.getJSONObject("observation").getString("snapshot").equals("final")
          && output.getJSONObject("observation").getJSONArray("nodes").getJSONObject(0).getString("href").equals("https://example.com/0"));
      yes(input.getJSONArray("results").getJSONObject(0).has("observation"));
      steps.put(1, Json.obj("error", "Stopped on drift"));
      output = new JSONObject(AssistantEngine.toolOutput(input));
      yes(output.getJSONArray("results").getJSONObject(1).optString("error").equals("Stopped on drift"));
    });
    check("cached public searches cannot freeze a previous query's verification", () -> {
      JSONArray search = Json.arr(
          Json.obj("tool", "ui_target", "args", Json.obj("scope", "public", "context", "https://example.com", "label", "Query", "action", "type", "text", "$param.query")),
          Json.obj("tool", "ui_target", "args", Json.obj("scope", "public", "context", "https://example.com", "label", "Results: previous query", "action", "wait")));
      rejects(() -> ActionPlan.validateReusable(search));
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
