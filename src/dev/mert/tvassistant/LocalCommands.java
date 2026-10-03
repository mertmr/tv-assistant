package dev.mert.tvassistant;

import java.util.*;
import java.util.regex.*;
import org.json.*;

final class LocalCommands {
  static final class Call {
    final String tool;
    final JSONObject args;

    Call(String t, JSONObject a) {
      tool = t;
      args = a;
    }
  }

  static Call parse(String command, JSONArray apps) {
    String raw = command.trim().replaceAll("[.!?]+$", "");
    String s = raw.toLowerCase(Locale.ROOT);
    if (s.startsWith("browse ")) {
      String url =
          raw.substring(7).trim().replaceAll("(?i) dot ", ".").replaceAll("(?i) slash ", "/");
      if (url.matches("(?i)(?:https://)?[a-z0-9][a-z0-9.-]*\\.[a-z]{2,}(?:[/?:#].*)?"))
        return new Call(
            "open_url",
            Json.obj(
                "url", url.startsWith("https://") ? url : "https://" + url, "browser", "internal"));
    }
    if (s.matches("(what('s| is) )?(the )?(time|date)( now)?"))
      return new Call("clock", Json.obj());
    if (s.matches("(list|show|what are)( me)?( the)?( installed)? apps"))
      return new Call("list_apps", Json.obj());
    if (s.matches("(show|list)( my)?( media)? sessions"))
      return new Call("list_sessions", Json.obj());
    if (s.matches("(show|list)( my)? notes")) return new Call("notes", Json.obj("action", "list"));
    if (s.matches("(show|list)( my)? routines"))
      return new Call("routines", Json.obj("action", "list"));
    if (s.startsWith("run routine "))
      return new Call("routines", Json.obj("action", "run", "name", raw.substring(12)));
    if (s.matches("(what('s| is) on( the)? screen|read( the)? screen)"))
      return new Call("screen_read", Json.obj());
    if (s.matches("(show|read) (my )?(device|tv) (info|information)"))
      return new Call("device_info", Json.obj());
    if (s.matches("(turn (the )?)?volume (up|down)"))
      return new Call("volume", Json.obj("action", s.endsWith("up") ? "up" : "down"));
    if (s.equals("mute")
        || s.equals("unmute")
        || s.equals("mute the tv")
        || s.equals("unmute the tv"))
      return new Call("volume", Json.obj("action", s.startsWith("unmute") ? "unmute" : "mute"));
    Matcher m =
        Pattern.compile("(?:set |change |turn )?(?:the )?volume(?: to)? (\\d{1,3})(?: percent|%)?")
            .matcher(s);
    if (m.matches())
      return new Call("volume", Json.obj("action", "set", "percent", Integer.parseInt(m.group(1))));
    if (s.matches("(play|resume|pause|stop)( playback| the video| the movie)?")) {
      String op = s.startsWith("pause") ? "pause" : s.startsWith("stop") ? "stop" : "play";
      return new Call("playback", Json.obj("action", op));
    }
    if (s.equals("next track")
        || s.equals("previous track")
        || s.equals("rewind")
        || s.equals("fast forward"))
      return new Call(
          "playback",
          Json.obj(
              "action",
              s.equals("next track")
                  ? "next"
                  : s.equals("previous track")
                      ? "previous"
                      : s.equals("rewind") ? "rewind" : "fast_forward"));
    if (s.equals("go home")
        || s.equals("go back")
        || s.matches("(?:go |move )?(?:up|down|left|right)")
        || s.equals("select"))
      return new Call("navigate", Json.obj("direction", s.replaceFirst("^(go |move )", "")));
    if (s.equals("scroll down") || s.equals("scroll up"))
      return new Call(
          "screen_scroll", Json.obj("direction", s.endsWith("down") ? "forward" : "backward"));
    if (s.startsWith("calculate "))
      return new Call(
          "calculate",
          Json.obj(
              "expression",
              raw.substring(10)
                  .replace("times", "*")
                  .replace("plus", "+")
                  .replace("minus", "-")
                  .replace("divided by", "/")));
    m =
        Pattern.compile(
                "(?:what(?:'s| is) the )?weather(?: forecast)? (?:in|for) (.+)",
                Pattern.CASE_INSENSITIVE)
            .matcher(raw);
    if (m.matches()) return new Call("weather", Json.obj("city", m.group(1)));
    m =
        Pattern.compile(
                "(?:set |start )?(?:a )?timer(?: for)? (\\d+) (second|minute|hour)s?(?: called"
                    + " (.+))?")
            .matcher(s);
    if (m.matches())
      return new Call(
          "timer",
          Json.obj(
              "action",
              "start",
              "name",
              m.group(3) == null ? "Timer" : m.group(3),
              "seconds",
              Long.parseLong(m.group(1))
                  * (m.group(2).equals("hour") ? 3600 : m.group(2).equals("minute") ? 60 : 1)));
    m =
        Pattern.compile(
                "(?:open )?(wifi|wi-fi|bluetooth|display|sound|accessibility|playback|general)"
                    + " settings")
            .matcher(s);
    if (m.matches())
      return new Call("open_settings", Json.obj("panel", m.group(1).replace("wi-fi", "wifi")));
    if (s.equals("open settings")) return new Call("open_settings", Json.obj("panel", "general"));
    m =
        Pattern.compile(
                "(?:search|find|look for) (.+) (?:on|in|using) (.+)", Pattern.CASE_INSENSITIVE)
            .matcher(raw);
    if (m.matches())
      return new Call("search_app", Json.obj("app", m.group(2), "query", m.group(1)));
    m =
        Pattern.compile("(?:search (?:the )?web for|google|look up) (.+)", Pattern.CASE_INSENSITIVE)
            .matcher(raw);
    if (m.matches())
      return new Call(
          "web_search", Json.obj("query", m.group(1), "engine", "google", "browser", "silk"));
    m =
        Pattern.compile("(?:open(?: up)?|launch|start) (.+)", Pattern.CASE_INSENSITIVE)
            .matcher(raw);
    if (m.matches()) {
      String target = m.group(1);
      String url = target.replaceAll("(?i) dot ", ".").replaceAll("(?i) slash ", "/");
      if (url.matches("(?i)(?:https://)?[a-z0-9][a-z0-9.-]*\\.[a-z]{2,}(?:[/?:#].*)?"))
        return new Call(
            "open_url",
            Json.obj(
                "url", url.startsWith("https://") ? url : "https://" + url, "browser", "silk"));
      Matcher search =
          Pattern.compile(
                  "(.+?) (?:and(?: (?:find|search for|play|watch|the tv show))?|to"
                      + " (?:find|watch|play)) (.+)",
                  Pattern.CASE_INSENSITIVE)
              .matcher(target);
      if (search.matches())
        return new Call("search_app", Json.obj("app", search.group(1), "query", search.group(2)));
      String q = Tools.normalize(target);
      for (int i = 0; i < apps.length(); i++) {
        JSONObject app = apps.optJSONObject(i);
        if (q.equals(Tools.normalize(app.optString("label")))
            || target.equals(app.optString("package")))
          return new Call("open_app", Json.obj("app", target));
      }
      if (q.equals("stremio") || q.equals("silk") || q.equals("youtube") || q.equals("netflix"))
        return new Call("open_app", Json.obj("app", target));
    }
    return null;
  }
}
