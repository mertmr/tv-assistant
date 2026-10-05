package dev.mert.tvassistant;

import java.util.regex.Pattern;
import org.json.*;

/** Resolves real Cinemeta episode IDs; never synthesizes missing episodes or stream URLs. */
final class MediaEpisode {
  static void validate(String type, String id, JSONObject args) throws Exception {
    if (!id.matches("tt[0-9]{5,12}"))
      throw new IllegalArgumentException("Expected a catalog IMDb identifier");
    if (!type.equals("series") && !type.equals("movie"))
      throw new IllegalArgumentException("Expected movie or series");
    if (args.has("season") != args.has("episode"))
      throw new IllegalArgumentException("Supply both season and episode");
    if (args.has("season")) {
      if (!type.equals("series")) throw new IllegalArgumentException("Episodes require a series");
      integer(args, "season", 0);
      integer(args, "episode", 1);
    }
  }

  private static int integer(JSONObject object, String key, int minimum) throws Exception {
    Object raw = object.get(key);
    if (!(raw instanceof Number)) throw new IllegalArgumentException(key + " must be an integer");
    double value = ((Number) raw).doubleValue();
    if (Double.isNaN(value) || Double.isInfinite(value) || value < minimum || value > 10000
        || value != Math.floor(value))
      throw new IllegalArgumentException("Invalid " + key);
    return (int) value;
  }

  static JSONObject resolve(JSONObject meta, String id, JSONObject args) throws Exception {
    validate("series", id, args);
    if (!args.has("season")) throw new IllegalArgumentException("Episode coordinates are required");
    if (!id.equals(meta.optString("id")) || !"series".equals(meta.optString("type")))
      throw new IllegalArgumentException("Catalog returned a different series");
    int season = integer(args, "season", 0), episode = integer(args, "episode", 1);
    JSONArray videos = meta.optJSONArray("videos");
    JSONObject found = null;
    if (videos != null) for (int i = 0; i < videos.length(); i++) {
      JSONObject video = videos.optJSONObject(i);
      if (video == null || !(video.opt("season") instanceof Number)
          || !(video.opt("episode") instanceof Number)) continue;
      if (((Number) video.get("season")).doubleValue() != season
          || ((Number) video.get("episode")).doubleValue() != episode) continue;
      if (found != null) throw new IllegalArgumentException("Catalog episode is ambiguous");
      found = video;
    }
    if (found == null) throw new IllegalArgumentException("Requested episode was not found in the catalog");
    String videoId = found.optString("id"), title = meta.optString("name").trim();
    if (!videoId.matches("[A-Za-z0-9_:.-]{1,200}") || title.isEmpty())
      throw new IllegalArgumentException("Catalog episode is missing a usable ID or series title");
    return Json.obj("id", id, "type", "series", "title", title,
        "season", season, "episode", episode, "video_id", videoId,
        "episode_title", found.optString("title", found.optString("name")),
        "detail_url", "stremio:///detail/series/" + id + "/" + Net.enc(videoId)
            + "?autoPlay=false");
  }

  /** Require the correct app, series heading and selected episode header, not a list item alone. */
  static boolean verified(JSONObject screen, JSONObject episode) throws Exception {
    if (!"com.stremio.one".equals(screen.optString("package")) || screen.has("error")) return false;
    JSONArray nodes = screen.optJSONArray("nodes");
    if (nodes == null) return false;
    String title = episode.getString("title");
    Pattern code = Pattern.compile("(?i)\\bS0*" + episode.getInt("season")
        + "E0*" + episode.getInt("episode") + "\\b");
    boolean series = false, selected = false;
    for (int i = 0; i < nodes.length(); i++) {
      JSONObject node = nodes.getJSONObject(i);
      String text = node.optString("text").trim();
      series |= "com.stremio.one:id/meta_details_label".equals(node.optString("view_id"))
          && (text.equalsIgnoreCase(title)
              || text.toLowerCase(java.util.Locale.ROOT).startsWith(title.toLowerCase(java.util.Locale.ROOT) + " ("));
      String name = episode.optString("episode_title").trim();
      selected |= "com.stremio.one:id/meta_details_video_label".equals(node.optString("view_id"))
          && code.matcher(text).lookingAt() && (name.isEmpty() || text.endsWith(name));
    }
    return series && selected;
  }
}
