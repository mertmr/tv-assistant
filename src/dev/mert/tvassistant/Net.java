package dev.mert.tvassistant;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

final class Net {
  private static volatile HttpURLConnection inference;

  static void cancelStream() {
    HttpURLConnection c = inference;
    if (c != null) c.disconnect();
  }

  interface Events {
    void event(JSONObject event) throws Exception;

    boolean cancelled();
  }

  static String enc(String s) {
    try {
      return URLEncoder.encode(s, "UTF-8");
    } catch (Exception e) {
      throw new IllegalArgumentException(e);
    }
  }

  static String form(Map<String, String> p) {
    StringBuilder b = new StringBuilder();
    for (Map.Entry<String, String> e : p.entrySet()) {
      if (b.length() > 0) b.append('&');
      b.append(enc(e.getKey())).append('=').append(enc(e.getValue()));
    }
    return b.toString();
  }

  static HttpURLConnection open(String url, String bearer) throws Exception {
    URL u = new URL(url);
    if (!"https".equals(u.getProtocol())) throw new IOException("HTTPS is required");
    HttpURLConnection c = (HttpURLConnection) u.openConnection();
    c.setConnectTimeout(15000);
    c.setReadTimeout(60000);
    c.setInstanceFollowRedirects(false);
    c.setRequestProperty("User-Agent", "TVAssistant/0.1");
    if (bearer != null) c.setRequestProperty("Authorization", "Bearer " + bearer);
    return c;
  }

  static String read(InputStream in, int limit) throws Exception {
    if (in == null) return "";
    try (InputStream stream = in;
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      byte[] b = new byte[8192];
      int n;
      while ((n = stream.read(b)) != -1) {
        if (out.size() + n > limit) throw new IOException("Response exceeds size limit");
        out.write(b, 0, n);
      }
      return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
  }

  static String request(String url, String bearer, String body, String type) throws Exception {
    HttpURLConnection c = open(url, bearer);
    try {
      if (body != null) {
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", type);
        try (OutputStream out = c.getOutputStream()) {
          out.write(body.getBytes(StandardCharsets.UTF_8));
        }
      }
      int status = c.getResponseCode();
      String text = read(status < 400 ? c.getInputStream() : c.getErrorStream(), 2_000_000);
      if (status < 200 || status >= 300)
        throw new IOException(
            "HTTP "
                + status
                + " · "
                + Json.clip(text, 700)
                + " · request "
                + c.getHeaderField("x-request-id"));
      return text;
    } finally {
      c.disconnect();
    }
  }

  static JSONObject get(String url, String token) throws Exception {
    return new JSONObject(request(url, token, null, null));
  }

  static String publicText(String url, int limit) throws Exception {
    HttpURLConnection c = open(url, null);
    try {
      c.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
      if (c.getResponseCode() != 200)
        throw new IOException("Public metadata request failed: HTTP " + c.getResponseCode());
      return read(c.getInputStream(), limit);
    } finally {
      c.disconnect();
    }
  }

  static JSONObject postForm(String url, Map<String, String> p) throws Exception {
    return new JSONObject(request(url, null, form(p), "application/x-www-form-urlencoded"));
  }

  static JSONObject stream(String token, JSONObject body, Events events) throws Exception {
    if (events.cancelled()) throw new InterruptedIOException("Task stopped");
    HttpURLConnection c = open("https://api.openai.com/v1/responses", token);
    inference = c;
    try {
      c.setRequestMethod("POST");
      c.setDoOutput(true);
      c.setRequestProperty("Content-Type", "application/json");
      c.setRequestProperty("Accept", "text/event-stream");
      try (OutputStream out = c.getOutputStream()) {
        out.write(body.toString().getBytes(StandardCharsets.UTF_8));
      }
      int status = c.getResponseCode();
      if (status != 200)
        throw new IOException(
            "HTTP " + status + " · " + Json.clip(read(c.getErrorStream(), 100000), 700));
      try (BufferedReader r =
          new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
        return parseStream(r, events);
      }
    } finally {
      inference = null;
      c.disconnect();
    }
  }

  static JSONObject parseStream(BufferedReader r, Events events) throws Exception {
    String line;
    StringBuilder data = new StringBuilder();
    Accumulator accumulator = new Accumulator(events);
    int size = 0;
    while ((line = r.readLine()) != null) {
      if (events.cancelled()) throw new InterruptedIOException("Task stopped");
      size += line.length();
      if (size > 4_000_000) throw new IOException("Stream exceeds size limit");
      if (line.isEmpty()) {
        if (data.length() > 0) {
          String raw = data.toString();
          data.setLength(0);
          if (!"[DONE]".equals(raw)) {
            JSONObject completed = accumulator.accept(new JSONObject(raw));
            if (completed != null) return completed;
          }
        }
      } else if (line.startsWith("data:")) {
        if (data.length() > 0) data.append('\n');
        data.append(line.substring(5).trim());
      }
    }
    throw new IOException("AI stream ended without completion");
  }

  // Both transports retain completed items when the plan-sharing terminal envelope is empty.
  static final class Accumulator {
    private final Events events;
    private final SortedMap<Integer, JSONObject> items = new TreeMap<>();
    Accumulator(Events events) { this.events = events; }
    JSONObject accept(JSONObject event) throws Exception {
      if (events.cancelled()) throw new InterruptedIOException("Task stopped");
      String kind = event.optString("type");
      events.event(event);
      if (kind.equals("response.output_item.done"))
        items.put(event.getInt("output_index"), event.getJSONObject("item"));
      if (kind.equals("response.failed") || kind.equals("response.incomplete") || kind.equals("error"))
        throw new IOException("AI request did not complete: " + Json.clip(event.toString(), 700));
      if (!kind.equals("response.completed")) return null;
      JSONObject completed = event.getJSONObject("response");
      JSONArray output = completed.optJSONArray("output");
      if ((output == null || output.length() == 0) && !items.isEmpty()) {
        JSONArray restored = new JSONArray();
        for (JSONObject item : items.values()) restored.put(item);
        completed.put("output", restored);
      }
      return completed;
    }
  }
}
