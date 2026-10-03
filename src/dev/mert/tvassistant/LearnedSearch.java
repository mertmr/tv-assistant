package dev.mert.tvassistant;

import java.net.URI;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;

/** Learns a small verified search routine, never a query or a result destination. */
final class LearnedSearch {
  static JSONObject learn(JSONArray source, JSONObject parameters, JSONObject execution) throws Exception {
    if (execution.optBoolean("stopped_early", true) || source.length() < 2 || source.length() > 3)
      return null;
    JSONArray results = execution.optJSONArray("results");
    if (results == null || results.length() != source.length()) return null;
    JSONArray steps = new JSONArray(source.toString());
    String origin = "", query = "", prefix = "", suffix = "";
    for (int i = 0; i < steps.length(); i++) {
      JSONObject step = steps.getJSONObject(i), args = step.getJSONObject("args");
      String expected = i == steps.length() - 1 ? "wait" : i == steps.length() - 2 ? "type" : "click";
      if (!step.optString("tool").equals("ui_target") || !args.optString("scope").equals("public")
          || !args.optString("action").equals(expected) || ActionPlan.failed(results.getJSONObject(i))) return null;
      Set<String> allowed = new HashSet<>(Arrays.asList("scope", "context", "label", "action", "match", "timeout_ms", "scrolls"));
      if (expected.equals("type")) allowed.add("text");
      Iterator<String> keys = args.keys();
      while (keys.hasNext()) if (!allowed.contains(keys.next())) return null;
      if (!(args.opt("context") instanceof String) || args.optInt("scrolls") != 0) return null;
      String context = args.getString("context");
      URI uri = new URI(context);
      if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
          || uri.getRawQuery() != null || uri.getRawFragment() != null || !uri.getRawPath().isEmpty()) return null;
      if (i == 0) origin = context;
      if (!origin.equals(context)) return null;
      if (!expected.equals("wait")) {
        if (!(args.opt("label") instanceof String) || args.getString("label").trim().isEmpty()
            || args.getString("label").startsWith("$param.") || args.toString().contains("\"$ref\"")) return null;
      }
      if (expected.equals("type")) {
        Object value = ActionPlan.resolve(args.opt("text"), new JSONArray(), parameters);
        if (!(value instanceof String) || !results.getJSONObject(i).optBoolean("text_verified")) return null;
        query = (String) value;
        if (query.trim().isEmpty() || query.length() > 200 || query.startsWith("$param.")) return null;
        args.put("text", Json.obj("$param", "query"));
      } else if (expected.equals("wait")) {
        if (!args.optString("match").equals("text") || !results.getJSONObject(i).optBoolean("verified")) return null;
        Object value = ActionPlan.resolve(args.opt("label"), new JSONArray(), parameters);
        if (!(value instanceof String)) return null;
        String label = (String) value, lower = label.toLowerCase(Locale.ROOT), term = query.toLowerCase(Locale.ROOT);
        int at = lower.indexOf(term);
        // Require a single unchanged query in the verified heading; never infer unrelated destinations.
        if (at < 0 || lower.indexOf(term, at + term.length()) >= 0 || label.length() > 300
            || !label.regionMatches(true, at, query, 0, query.length())) return null;
        prefix = label.substring(0, at); suffix = label.substring(at + query.length());
        args.put("label", Json.obj("$param", "result"));
      }
    }
    // Do not accidentally retain the query in another argument, including a control label/origin.
    if (steps.toString().toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))) return null;
    ActionPlan.validateReusable(steps);
    JSONObject workflow = Json.obj("steps", steps, "kind", "public_search", "verification_prefix", prefix,
        "verification_suffix", suffix, "parameters", Json.obj("query", "New search terms; result verification is derived automatically"));
    // Identity depends on the site and controls, not timing, query, or verification wording.
    String identity = origin;
    for (int i = 0; i < steps.length() - 1; i++) identity += "\n" + steps.getJSONObject(i).getJSONObject("args").getString("label");
    byte[] hash = MessageDigest.getInstance("SHA-256").digest(identity.getBytes("UTF-8"));
    StringBuilder id = new StringBuilder();
    for (int i = 0; i < 6; i++) id.append(String.format(Locale.ROOT, "%02x", hash[i] & 255));
    return Json.obj("name", new URI(origin).getHost() + " search " + id, "workflow", workflow);
  }

  static JSONObject parameters(JSONObject workflow, JSONObject supplied) throws Exception {
    JSONObject values = new JSONObject(supplied.toString());
    if (workflow.optString("kind").equals("public_search")) {
      Object query = values.opt("query");
      if (!(query instanceof String) || ((String) query).trim().isEmpty() || ((String) query).length() > 200)
        throw new IllegalArgumentException("Learned search needs a nonempty query of at most 200 characters");
      // A caller cannot weaken the saved success condition by supplying a different result label.
      values.put("result", workflow.getString("verification_prefix") + query + workflow.getString("verification_suffix"));
    }
    return values;
  }
}
