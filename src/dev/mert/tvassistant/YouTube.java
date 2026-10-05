package dev.mert.tvassistant;

import android.util.Xml;
import java.io.StringReader;
import java.util.*;
import java.util.regex.*;
import org.json.*;
import org.xmlpull.v1.XmlPullParser;

/** Read-only metadata from public YouTube pages and the public channel upload feed. */
final class YouTube {
  static JSONObject search(String query) throws Exception {
    String url = "https://www.youtube.com/results?search_query=" + Net.enc(query);
    JSONObject result = parseSearch(Net.publicText(url, 5_000_000));
    return result.put("source", url);
  }

  static JSONObject parseSearch(String html) throws Exception {
    Matcher match = Pattern.compile("(?:var\\s+)?ytInitialData\\s*=\\s*").matcher(html);
    if (!match.find())
      throw new IllegalStateException(
          "YouTube returned no public search metadata; use the internal browser or refine the"
              + " query.");
    Object data = new JSONTokener(html.substring(match.end())).nextValue();
    JSONArray channels = new JSONArray(), videos = new JSONArray();
    walk(data, channels, videos, new HashSet<>(), 0);
    return Json.obj(
        "channels",
        channels,
        "videos",
        videos,
        "note",
        "Search ranking does not establish the latest upload. Resolve the channel, then use"
            + " youtube_latest.");
  }

  private static String text(JSONObject object) {
    if (object == null) return "";
    if (object.has("simpleText")) return object.optString("simpleText");
    StringBuilder text = new StringBuilder();
    JSONArray runs = object.optJSONArray("runs");
    if (runs != null)
      for (int i = 0; i < runs.length(); i++) text.append(runs.optJSONObject(i).optString("text"));
    return text.toString();
  }

  private static void walk(
      Object value, JSONArray channels, JSONArray videos, Set<String> seen, int depth)
      throws Exception {
    if (depth > 60 || (channels.length() >= 8 && videos.length() >= 12)) return;
    if (value instanceof JSONArray) {
      JSONArray array = (JSONArray) value;
      for (int i = 0; i < array.length(); i++)
        walk(array.get(i), channels, videos, seen, depth + 1);
    } else if (value instanceof JSONObject) {
      JSONObject object = (JSONObject) value;
      JSONObject channel = object.optJSONObject("channelRenderer"),
          video = object.optJSONObject("videoRenderer");
      if (channel != null && channels.length() < 8) {
        String id = channel.optString("channelId");
        if (validChannel(id) && seen.add(id))
          channels.put(
              Json.obj(
                  "channel_id",
                  id,
                  "name",
                  text(channel.optJSONObject("title")),
                  "url",
                  "https://www.youtube.com/channel/" + id));
      }
      if (video != null && videos.length() < 12) {
        String id = video.optString("videoId");
        if (validVideo(id) && seen.add(id))
          videos.put(
              Json.obj(
                  "video_id",
                  id,
                  "title",
                  text(video.optJSONObject("title")),
                  "channel",
                  text(video.optJSONObject("longBylineText")),
                  "published_label",
                  text(video.optJSONObject("publishedTimeText")),
                  "url",
                  "https://www.youtube.com/watch?v=" + id));
      }
      Iterator<String> keys = object.keys();
      while (keys.hasNext()) {
        String key = keys.next();
        if (!key.equals("channelRenderer") && !key.equals("videoRenderer"))
          walk(object.get(key), channels, videos, seen, depth + 1);
      }
    }
  }

  static boolean validChannel(String id) {
    return id.matches("UC[A-Za-z0-9_-]{22}");
  }

  static boolean validVideo(String id) {
    return id.matches("[A-Za-z0-9_-]{11}");
  }

  static JSONObject latest(String channel) throws Exception {
    if (!validChannel(channel))
      throw new IllegalArgumentException(
          "Resolve a valid YouTube channel ID with youtube_search first");
    String url = "https://www.youtube.com/feeds/videos.xml?channel_id=" + channel;
    String xml;
    try {
      xml = Net.publicText(url, 1_000_000);
    } catch (Net.HttpStatusException e) {
      // YouTube retired the public channel upload feed: this endpoint now returns 404 for
      // every channel, including deliberately invalid IDs. Do not present it as a lookup
      // failure or retry it, and do not fall back to search ranking for "latest".
      if (e.status == 404)
        throw new IllegalStateException(
            "YouTube's public channel upload feed has been retired and is unavailable for every"
                + " channel. Use youtube_search to find a video, or open the channel's Videos"
                + " page in the browser and read the newest entry from the page itself. Report"
                + " this limitation instead of guessing which upload is latest.");
      throw e;
    }
    JSONObject result = parseFeed(xml);
    if (!channel.equals(result.optString("channel_id")))
      throw new IllegalStateException("The upload feed did not match the requested channel");
    return result
        .put("source", url)
        .put(
            "note",
            "Newest published item in the public upload feed; may include Shorts or livestream"
                + " uploads. Do not infer latest from search ranking.");
  }

  static JSONObject parseFeed(String xml) throws Exception {
    if (xml.contains("<!DOCTYPE") || xml.contains("<!ENTITY"))
      throw new IllegalArgumentException("Unsupported XML declaration");
    XmlPullParser parser = Xml.newPullParser();
    parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
    parser.setInput(new StringReader(xml));
    List<JSONObject> entries = new ArrayList<>();
    JSONObject entry = null;
    String channel = "", name = "";
    for (int event = parser.getEventType();
        event != XmlPullParser.END_DOCUMENT;
        event = parser.next()) {
      if (event == XmlPullParser.START_TAG) {
        String tag = parser.getName();
        if (tag.equals("entry")) entry = new JSONObject();
        else if (tag.equals("videoId")
            || tag.equals("channelId")
            || tag.equals("title")
            || tag.equals("published")) {
          String text = parser.nextText();
          if (entry != null)
            entry.put(
                tag.equals("videoId") ? "video_id" : tag.equals("channelId") ? "channel_id" : tag,
                text);
          else if (tag.equals("channelId"))
            channel = text.matches("[A-Za-z0-9_-]{22}") ? "UC" + text : text;
          else if (tag.equals("title")) name = text;
        }
      } else if (event == XmlPullParser.END_TAG
          && parser.getName().equals("entry")
          && entry != null) {
        if (validVideo(entry.optString("video_id"))
            && entry.optString("published").matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T.*")) {
          entry.put("url", "https://www.youtube.com/watch?v=" + entry.getString("video_id"));
          entries.add(entry);
        }
        entry = null;
      }
    }
    entries.sort((a, b) -> b.optString("published").compareTo(a.optString("published")));
    JSONArray videos = new JSONArray();
    for (int i = 0; i < Math.min(8, entries.size()); i++) videos.put(entries.get(i));
    if (!validChannel(channel) || videos.length() == 0)
      throw new IllegalStateException("No usable public uploads were returned for this channel");
    return Json.obj(
        "channel_id",
        channel,
        "channel_name",
        name,
        "videos",
        videos,
        "latest",
        videos.getJSONObject(0));
  }
}
