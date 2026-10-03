package dev.mert.tvassistant;

import android.os.Build;
import java.io.*;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.java_websocket.drafts.Draft_6455;
import org.json.*;

/** One authenticated socket per task. Never replay an ambiguously submitted generation. */
final class ResponseSocket implements AutoCloseable {
  private final BlockingQueue<Object> incoming = new ArrayBlockingQueue<>(256);
  private final WebSocketClient client;
  private boolean attempted, fallback;
  private String previous = "";
  private int cursor, images;
  private volatile boolean closed;
  int requests, resets;
  String transport = "websocket";

  ResponseSocket(String token) throws Exception {
    System.setProperty("slf4j.provider", "org.slf4j.nop.NOPServiceProvider");
    Map<String, String> headers = new HashMap<>();
    headers.put("Authorization", "Bearer " + token);
    client = new WebSocketClient(new URI("wss://api.openai.com/v1/responses"), new Draft_6455(), headers, 12000) {
      public void onOpen(ServerHandshake handshake) {}
      public void onMessage(String message) {
        if (message.length() > 4_000_000 || !incoming.offer(message)) {
          incoming.clear();
          incoming.offer(new IOException("AI WebSocket buffer exceeded its limit"));
          closeConnection(1009, "Response limit");
        }
      }
      public void onClose(int code, String reason, boolean remote) {
        incoming.offer(new IOException("AI connection closed before completion (" + code + ")"));
      }
      public void onError(Exception error) {
        // Transport errors can include request headers in third-party implementations.
        incoming.offer(new IOException("AI WebSocket transport failed"));
      }
    };
    client.setConnectionLostTimeout(30);
  }

  static JSONObject payload(JSONObject request, JSONArray full, int cursor, String previous)
      throws Exception {
    // Do not stringify/copy old screenshots and tool history just to discard them below.
    JSONObject body = new JSONObject();
    Iterator<String> keys = request.keys();
    while (keys.hasNext()) {
      String key = keys.next();
      if (!key.equals("input") && !key.equals("stream") && !key.equals("background")
          && !key.equals("previous_response_id")) body.put(key, request.get(key));
    }
    body.put("type", "response.create");
    if (!previous.isEmpty()) {
      if (cursor < 0 || cursor > full.length())
        throw new IllegalArgumentException("Invalid continuation position");
      JSONArray delta = new JSONArray();
      for (int i = cursor; i < full.length(); i++) delta.put(full.get(i));
      body.put("input", delta).put("previous_response_id", previous);
    } else {
      body.remove("previous_response_id");
      body.put("input", full);
    }
    return body;
  }

  JSONObject create(String token, JSONObject request, Net.Events events) throws Exception {
    if (closed || events.cancelled()) throw new InterruptedIOException("Task stopped");
    if (!attempted) {
      attempted = true;
      // Endpoint hostname verification needs API 24 in this library. Older clients use HTTPS.
      fallback = Build.VERSION.SDK_INT < 24;
      if (!fallback) {
        try {
          fallback = !client.connectBlocking(12, TimeUnit.SECONDS);
        } catch (Exception e) { fallback = true; }
      }
      if (fallback) {
        client.closeConnection(1000, "HTTPS fallback");
        transport = "https";
      }
    }
    if (closed || events.cancelled()) throw new InterruptedIOException("Task stopped");
    requests++;
    if (fallback) return Net.stream(token, request, events);
    if (!client.isOpen()) throw new IOException("AI connection was lost. Submit a follow-up to continue safely.");
    JSONArray full = request.getJSONArray("input");
    int addedImages = 0;
    for (int i = cursor; i < full.length(); i++) {
      JSONObject item = full.optJSONObject(i);
      JSONArray content = item == null ? null : item.optJSONArray("content");
      if (content != null) for (int j = 0; j < content.length(); j++)
        if (content.getJSONObject(j).optString("type").equals("input_image")) addedImages++;
    }
    // Continuations cannot remove older images. Start a fresh chain periodically, on the same
    // socket, from the local context that retains only the newest image.
    if (images + addedImages > 3) { previous = ""; cursor = 0; images = 0; resets++; }
    JSONObject body = payload(request, full, cursor, previous);
    incoming.clear();
    client.send(body.toString());
    int size = 0;
    Net.Accumulator accumulator = new Net.Accumulator(events);
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
    while (true) {
      if (closed || events.cancelled()) throw new InterruptedIOException("Task stopped");
      if (System.nanoTime() >= until) throw new IOException("AI response timed out; no action was replayed");
      Object raw = incoming.poll(100, TimeUnit.MILLISECONDS);
      if (raw == null) continue;
      if (raw instanceof Exception) throw (Exception) raw;
      size += ((String) raw).length();
      if (size > 4_000_000) throw new IOException("AI response exceeds size limit");
      JSONObject completed = accumulator.accept(new JSONObject((String) raw));
      if (completed != null) {
        previous = completed.optString("id");
        cursor = full.length() + completed.getJSONArray("output").length();
        images += addedImages;
        return completed;
      }
    }
  }

  public void close() {
    closed = true;
    client.closeConnection(1000, "Task ended");
  }
}
