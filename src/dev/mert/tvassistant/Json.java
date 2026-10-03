package dev.mert.tvassistant;

import org.json.*;

final class Json {
  static JSONObject obj(Object... pairs) {
    JSONObject o = new JSONObject();
    try {
      for (int i = 0; i < pairs.length; i += 2) o.put((String) pairs[i], pairs[i + 1]);
    } catch (JSONException e) {
      throw new IllegalArgumentException(e);
    }
    return o;
  }

  static JSONArray arr(Object... values) {
    JSONArray a = new JSONArray();
    for (Object v : values) a.put(v);
    return a;
  }

  static String clip(String s, int max) {
    return s == null ? "" : s.length() <= max ? s : s.substring(0, max) + "…";
  }
}
