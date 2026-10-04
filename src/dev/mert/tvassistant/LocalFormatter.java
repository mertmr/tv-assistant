package dev.mert.tvassistant;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

final class LocalFormatter {
  static String format(String tool, JSONObject result) {
    if (result.has("error")) return result.optString("error");
    if (tool.equals("screen_see"))
      return "Captured current screen for AI · "
          + result.optInt("width")
          + " × "
          + result.optInt("height");
    if (tool.equals("youtube_play"))
      return (result.optBoolean("playback_verified") ? "Playing: " : "Requested video: ")
          + result.optString("title")
          + (result.optBoolean("playback_verified")
              ? ""
              : "\nPlayback could not be verified; check the YouTube screen.");
    if (tool.equals("youtube_search"))
      return items(result.optJSONArray("channels"), "name", "Matching YouTube channels", null)
          + "\n"
          + items(result.optJSONArray("videos"), "title", "Matching videos", "published_label");
    if (tool.equals("youtube_latest")) {
      JSONObject latest = result.optJSONObject("latest");
      return latest == null
          ? "No public uploads found."
          : result.optString("channel_name")
              + "\nLatest upload: "
              + latest.optString("title")
              + "\nPublished: "
              + latest.optString("published");
    }
    if (tool.equals("media_details") && result.has("episode")) {
      JSONObject episode = result.optJSONObject("episode");
      if (episode != null) {
        String label = episode.optString("title") + " · S" + episode.optInt("season")
            + "E" + episode.optInt("episode") + " · " + episode.optString("episode_title");
        return result.optBoolean("episode_verified") ? "Episode selected: " + label
            : "Requested episode: " + label + "\nSelection was not verified. "
                + result.optString("observation_error", result.optString("verification_note", "Check the TV screen."));
      }
    }
    if (result.has("launched")) return "Opened the destination. Check the TV screen.";
    if (result.has("performed"))
      return result.optBoolean("performed", false) || result.opt("performed") instanceof String
          ? "Action performed."
          : "The app did not accept that action.";
    if (result.has("requested"))
      return "Requested " + result.optString("requested").replace('_', ' ') + ".";
    switch (tool) {
      case "web_page":
        return result.optBoolean("loading") ? "Public page is loading."
            : result.optString("title") + "\n" + result.optString("url") + "\n"
                + items(result.optJSONArray("nodes"), "label", "Page controls and links", null);
      case "keyboard_keys":
        return "Delivered " + result.optInt("keys_delivered") + "/"
            + result.optInt("keys_requested") + " keys; inspect the search results.";
      case "action_plan":
        return (result.optBoolean("stopped_early") ? "Plan stopped: " : "Plan completed: ")
            + result.optInt("completed_steps") + "/" + result.optInt("requested_steps") + " steps."
            + (result.has("cached_workflow") ? "\nLearned workflow: " + result.optString("cached_workflow") : "")
            + (result.has("cache_note") ? "\nWorkflow not saved: " + result.optString("cache_note") : "");
      case "clock":
        return result.optString("time");
      case "calculate":
        return "Result: " + result.opt("result");
      case "volume":
        return "Media volume: "
            + result.optInt("percent")
            + "%"
            + (result.optBoolean("muted") ? " · muted" : "")
            + (result.optBoolean("fixed")
                ? "\nThis device has fixed software volume. Use the TV/receiver remote."
                : "");
      case "device_info":
        return result.optString("manufacturer")
            + " "
            + result.optString("model")
            + "\nAndroid "
            + result.optString("android")
            + "\nNetwork: "
            + (result.optBoolean("online") ? "connected" : "offline")
            + "\nScreen navigation: "
            + (result.optBoolean("navigation_enabled") ? "enabled" : "not connected");
      case "list_apps":
        return items(result.optJSONArray("apps"), "label", "Installed apps", null);
      case "find_media":
        return items(result.optJSONArray("results"), "name", "Matching titles", "year");
      case "list_sessions":
        {
          JSONArray sessions = result.optJSONArray("sessions");
          if (sessions == null || sessions.length() == 0)
            return "No active media session is available.";
          StringBuilder text = new StringBuilder("Active players\n");
          for (int i = 0; i < sessions.length(); i++) {
            JSONObject s = sessions.optJSONObject(i);
            int state = s.optInt("state");
            text.append(s.optString("title", "Untitled"))
                .append(" · ")
                .append(state == 3 ? "playing" : state == 2 ? "paused" : "ready")
                .append('\n');
          }
          return text.toString().trim();
        }
      case "screen_read":
        return items(result.optJSONArray("nodes"), "text", "Visible screen items", "description");
      case "browser_read":
        return result.optString("title") + "\n" + Json.clip(result.optString("text"), 3000);
      case "weather":
        {
          JSONObject forecast = result.optJSONObject("forecast");
          if (forecast == null) return "Weather is unavailable.";
          JSONObject current = forecast.optJSONObject("current"),
              daily = forecast.optJSONObject("daily");
          StringBuilder text = new StringBuilder(result.optString("location"));
          if (current != null)
            text.append("\n")
                .append(current.optDouble("temperature_2m"))
                .append("°C · ")
                .append(condition(current.optInt("weather_code")))
                .append("\nFeels like ")
                .append(current.optDouble("apparent_temperature"))
                .append("°C");
          if (daily != null) {
            JSONArray times = daily.optJSONArray("time"),
                min = daily.optJSONArray("temperature_2m_min"),
                max = daily.optJSONArray("temperature_2m_max");
            if (times != null && min != null && max != null)
              for (int i = 0; i < times.length(); i++)
                text.append("\n")
                    .append(times.optString(i))
                    .append(": ")
                    .append(min.optDouble(i))
                    .append("–")
                    .append(max.optDouble(i))
                    .append("°C");
          }
          return text + "\nSource: Open-Meteo";
        }
      case "notes":
        return result.has("notes")
            ? mapping(result.optJSONObject("notes"), "Notes", true)
            : "Note updated.";
      case "preferences":
        return result.has("preferences")
            ? mapping(result.optJSONObject("preferences"), "Saved preferences", true)
            : "Preference saved.";
      case "routines":
        if (result.has("routines"))
          return mapping(result.optJSONObject("routines"), "Saved routines", false);
        if (result.has("results")) return "Routine finished. Check the action log for each step.";
        return "Routine updated.";
      case "timer":
        if (result.has("timers"))
          return mapping(result.optJSONObject("timers"), "Running timers", false);
        if (result.has("cancelled")) return "Timer cancelled.";
        return "Timer set for "
            + new SimpleDateFormat("HH:mm:ss", Locale.US)
                .format(new Date(result.optLong("ends_at_ms")))
            + ". Keep the assistant running.";
      case "wait":
        return "Waited for the screen to load.";
      default:
        return "Done.";
    }
  }

  private static String items(JSONArray list, String key, String title, String extra) {
    if (list == null || list.length() == 0) return "No items found.";
    StringBuilder text = new StringBuilder(title).append('\n');
    for (int i = 0; i < Math.min(40, list.length()); i++) {
      JSONObject item = list.optJSONObject(i);
      String label = item.optString(key);
      String suffix = extra == null ? "" : item.optString(extra);
      if (label.isEmpty()) label = suffix;
      else if (!suffix.isEmpty() && !suffix.equals(label)) label += " · " + suffix;
      if (!label.isEmpty()) text.append("• ").append(label).append('\n');
    }
    return text.toString().trim();
  }

  private static String mapping(JSONObject map, String title, boolean values) {
    if (map == null || map.length() == 0) return "Nothing saved yet.";
    StringBuilder text = new StringBuilder(title).append('\n');
    Iterator<String> keys = map.keys();
    while (keys.hasNext()) {
      String key = keys.next();
      text.append("• ").append(key);
      if (values) text.append(": ").append(map.optString(key));
      text.append('\n');
    }
    return text.toString().trim();
  }

  private static String condition(int code) {
    if (code == 0) return "clear sky";
    if (code <= 3) return "cloudy";
    if (code <= 48) return "fog";
    if (code <= 67) return "rain";
    if (code <= 77) return "snow";
    if (code <= 82) return "rain showers";
    if (code <= 86) return "snow showers";
    return "thunderstorms";
  }
}
