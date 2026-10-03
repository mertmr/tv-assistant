package dev.mert.tvassistant;

import java.util.Locale;
import org.json.*;

/** Readiness tracks observable page content, excluding typed field values and snapshot IDs. */
final class PageReadiness {
  private final String before;
  private final boolean requireChange;
  private String last = "";
  private long changedAt;
  private boolean changed;
  PageReadiness(JSONObject baseline, boolean requireChange) throws Exception {
    before = baseline == null ? "" : fingerprint(baseline);
    this.requireChange = requireChange;
  }
  boolean ready(JSONObject page, long now) throws Exception {
    String current = fingerprint(page);
    changed |= !current.equals(before);
    if (!current.equals(last)) { last = current; changedAt = now; }
    return (!requireChange || changed) && now - changedAt >= 150;
  }
  static boolean contains(JSONObject page, String text) {
    if (text.trim().isEmpty()) throw new IllegalArgumentException("Wait text is empty");
    return normalize(page.optString("text")).contains(normalize(text));
  }
  private static String normalize(String text) {
    return text.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
  }
  private static String fingerprint(JSONObject page) throws Exception {
    JSONArray nodes = page.optJSONArray("nodes"), controls = new JSONArray();
    if (nodes != null) for (int i = 0; i < nodes.length(); i++) {
      JSONObject n = nodes.getJSONObject(i);
      controls.put(Json.arr(n.optString("tag"), n.optString("label"), n.optString("href"), n.optBoolean("disabled")));
    }
    return Json.arr(page.optString("url"), page.optString("title"), page.optString("text"),
        page.optJSONObject("viewport"), controls).toString();
  }
}
