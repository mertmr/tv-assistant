package dev.mert.tvassistant;

import java.util.*;
import org.json.*;

/** Bounded client-side plans, not arbitrary code. A failed step stops all dependent actions. */
final class ActionPlan {
  private static final Set<String> ALLOWED = new HashSet<>(Arrays.asList(
      "list_apps", "open_app", "search_app", "open_url", "find_media", "media_details",
      "youtube_search", "youtube_latest", "youtube_play", "list_sessions", "device_info",
      "clock", "calculate", "weather", "screen_read", "screen_see", "screen_click",
      "screen_type", "screen_scroll", "screen_tap", "screen_swipe", "keyboard_keys",
      "navigate", "browser_read", "browser_action", "browser_navigation", "wait", "ui_target", "web_page"));
  interface Runner {
    void validate(String tool, JSONObject args) throws Exception;
    JSONObject run(String tool, JSONObject args);
    boolean cancelled();
  }

  static void validate(JSONArray steps) throws Exception {
    if (steps.length() < 1 || steps.length() > 8)
      throw new IllegalArgumentException("A plan needs 1 to 8 steps");
    for (int i = 0; i < steps.length(); i++) {
      JSONObject step = steps.getJSONObject(i);
      if (step.length() != 2 || !ALLOWED.contains(step.getString("tool")))
        throw new IllegalArgumentException("Unsupported plan step");
      step.getJSONObject("args");
      checkReferences(step.getJSONObject("args"), i);
    }
  }

  private static void checkReferences(Object value, int before) throws Exception {
    if (value instanceof JSONObject) {
      JSONObject object = (JSONObject) value;
      if (object.has("$ref")) {
        if (object.length() != 1) throw new IllegalArgumentException("Reference must stand alone");
        String path = object.getString("$ref");
        int index = Integer.parseInt(path.split("\\.")[0]);
        if (index < 0 || index >= before) throw new IllegalArgumentException("Reference needs an earlier step");
      } else if (object.has("$param")) {
        if (object.length() != 1 || !object.getString("$param").matches("[A-Za-z][A-Za-z0-9_]{0,39}"))
          throw new IllegalArgumentException("Invalid plan parameter");
      } else {
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) checkReferences(object.get(keys.next()), before);
      }
    } else if (value instanceof JSONArray) {
      JSONArray array = (JSONArray) value;
      for (int i = 0; i < array.length(); i++) checkReferences(array.get(i), before);
    }
  }

  static Object resolve(Object value, JSONArray results, JSONObject parameters) throws Exception {
    if (value instanceof String && ((String) value).matches("\\$param\\.[A-Za-z][A-Za-z0-9_]{0,39}"))
      return resolve(Json.obj("$param", ((String) value).substring(7)), results, parameters);
    if (value instanceof JSONObject) {
      JSONObject object = (JSONObject) value;
      if (object.has("$param")) {
        Object parameter = parameters.get(object.getString("$param"));
        if (!(parameter instanceof String) && !(parameter instanceof Number) && !(parameter instanceof Boolean))
          throw new IllegalArgumentException("Parameters must be scalar values");
        return parameter;
      }
      if (object.has("$ref")) {
        String[] path = object.getString("$ref").split("\\.");
        Object found = results.get(Integer.parseInt(path[0]));
        for (int i = 1; i < path.length; i++)
          found = found instanceof JSONArray ? ((JSONArray) found).get(Integer.parseInt(path[i]))
              : ((JSONObject) found).get(path[i]);
        if (found == JSONObject.NULL) throw new IllegalArgumentException("Referenced result is empty");
        return found;
      }
      JSONObject copy = new JSONObject();
      Iterator<String> keys = object.keys();
      while (keys.hasNext()) { String key = keys.next(); copy.put(key, resolve(object.get(key), results, parameters)); }
      return copy;
    }
    if (value instanceof JSONArray) {
      JSONArray copy = new JSONArray(), array = (JSONArray) value;
      for (int i = 0; i < array.length(); i++) copy.put(resolve(array.get(i), results, parameters));
      return copy;
    }
    return value;
  }

  static boolean failed(JSONObject result) {
    return result.has("error") || result.has("observation_error")
        || (result.has("text_verified") && !result.optBoolean("text_verified"))
        || (result.has("performed") && Boolean.FALSE.equals(result.opt("performed")))
        || (result.has("keys_requested") && result.optInt("keys_requested") != result.optInt("keys_delivered"));
  }

  static JSONObject run(JSONArray steps, JSONObject parameters, Runner runner) throws Exception {
    validate(steps);
    JSONArray results = new JSONArray();
    String image = "";
    int completed = 0;
    for (int i = 0; i < steps.length(); i++) {
      JSONObject result;
      try {
        if (runner.cancelled()) throw new InterruptedException("Task stopped");
        JSONObject step = steps.getJSONObject(i);
        JSONObject args = (JSONObject) resolve(step.getJSONObject("args"), results, parameters);
        runner.validate(step.getString("tool"), args);
        result = runner.run(step.getString("tool"), args);
      } catch (Exception e) { result = Json.obj("error", ChatAuth.safe(e)); }
      image = "";
      if (result.has("image_url")) { image = result.getString("image_url"); result.remove("image_url"); }
      results.put(result);
      if (failed(result)) break;
      completed++;
    }
    JSONObject out = Json.obj("completed_steps", completed, "requested_steps", steps.length(),
        "results", results, "stopped_early", completed != steps.length(),
        "note", "Step delivery is not final success; inspect observations and verification fields.");
    if (!image.isEmpty()) out.put("image_url", image);
    return out;
  }

  // Cached plans re-resolve labels in a fresh scope. Public typing stores only a parameter
  // placeholder, never its value. The final step must observe the requested destination.
  static void validateReusable(JSONArray steps) throws Exception {
    validate(steps);
    boolean publicTyping = false;
    for (int i = 0; i < steps.length(); i++) {
      JSONObject step = steps.getJSONObject(i), args = step.getJSONObject("args");
      Object label = args.opt("label");
      boolean validLabel = parameter(label) || (label instanceof String && !((String) label).trim().isEmpty());
      String action = args.optString("action"), scope = args.optString("scope");
      boolean typing = action.equals("type") && scope.equals("public") && parameter(args.opt("text"));
      publicTyping |= typing;
      if (!step.getString("tool").equals("ui_target")
          || !(action.matches("click|wait") || typing)
          || !scope.matches("native|browser|public")
          || !(args.opt("context") instanceof String) || args.optString("context").isEmpty()
          || !validLabel || (args.has("text") && !typing)
          || args.toString().contains("\"$ref\""))
        throw new IllegalArgumentException("Reuse needs fresh scoped labels; public typing needs a parameter");
    }
    if (!steps.getJSONObject(steps.length() - 1).getJSONObject("args").optString("action").equals("wait"))
      throw new IllegalArgumentException("Reusable plans must end with a label verification");
    if (publicTyping && !parameter(steps.getJSONObject(steps.length() - 1).getJSONObject("args").opt("label")))
      throw new IllegalArgumentException("Public search verification must also use a parameter");
  }

  private static boolean parameter(Object value) {
    if (value instanceof String)
      return ((String) value).matches("\\$param\\.[A-Za-z][A-Za-z0-9_]{0,39}");
    return value instanceof JSONObject && ((JSONObject) value).length() == 1
        && ((JSONObject) value).opt("$param") instanceof String
        && ((JSONObject) value).optString("$param").matches("[A-Za-z][A-Za-z0-9_]{0,39}");
  }
}
