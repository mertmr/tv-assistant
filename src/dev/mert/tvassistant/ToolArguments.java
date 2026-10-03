package dev.mert.tvassistant;

import java.util.Iterator;
import org.json.*;

/** Checks the supported tool-schema subset; plan references are checked again after resolution. */
final class ToolArguments {
  static void validate(JSONObject schema, JSONObject args) throws Exception {
    object(schema, args, new JSONObject(), false, 0);
  }

  static void preflight(JSONObject schema, JSONObject args, JSONObject parameters) throws Exception {
    object(schema, args, parameters, true, 0);
  }

  private static void object(JSONObject schema, JSONObject args, JSONObject parameters,
      boolean template, int depth) throws Exception {
    if (depth > 16) throw new IllegalArgumentException("Arguments are too deeply nested");
    JSONObject properties = schema.getJSONObject("properties");
    JSONArray required = schema.optJSONArray("required");
    if (required != null) for (int i = 0; i < required.length(); i++) {
      String key = required.getString(i);
      if (!args.has(key) || args.isNull(key)) throw new IllegalArgumentException("Missing " + key);
    }
    Iterator<String> keys = args.keys();
    while (keys.hasNext()) {
      String key = keys.next();
      if (!properties.has(key)) throw new IllegalArgumentException("Unknown argument: " + key);
      value(properties.getJSONObject(key), args.get(key), parameters, template, depth + 1, key);
    }
  }

  private static void value(JSONObject schema, Object value, JSONObject parameters,
      boolean template, int depth, String label) throws Exception {
    if (depth > 16) throw new IllegalArgumentException("Arguments are too deeply nested");
    if (template && value instanceof String && ((String) value).matches("\\$param\\.[A-Za-z][A-Za-z0-9_]{0,39}"))
      value = Json.obj("$param", ((String) value).substring(7));
    if (template && value instanceof JSONObject) {
      JSONObject marker = (JSONObject) value;
      if (marker.has("$ref") || marker.has("$param")) {
        if (marker.length() != 1) throw new IllegalArgumentException("Plan placeholder must stand alone");
        if (marker.has("$ref")) return; // Its type depends on a result not yet available.
        value = parameters.get(marker.getString("$param"));
        if (!(value instanceof String) && !(value instanceof Number) && !(value instanceof Boolean))
          throw new IllegalArgumentException("Parameters must be scalar values");
      }
    }
    switch (schema.getString("type")) {
      case "string":
        if (!(value instanceof String)) throw new IllegalArgumentException("Expected text: " + label);
        if (((String) value).length() > 12000) throw new IllegalArgumentException("Argument is too long");
        break;
      case "integer":
      case "number":
        if (!(value instanceof Number) || (Double.isNaN(((Number) value).doubleValue()) || Double.isInfinite(((Number) value).doubleValue())))
          throw new IllegalArgumentException("Expected finite number: " + label);
        double number = ((Number) value).doubleValue();
        if (schema.getString("type").equals("integer") && number != Math.rint(number))
          throw new IllegalArgumentException("Expected integer: " + label);
        if ((schema.has("minimum") && number < schema.getDouble("minimum"))
            || (schema.has("maximum") && number > schema.getDouble("maximum")))
          throw new IllegalArgumentException("Argument outside allowed range: " + label);
        break;
      case "boolean":
        if (!(value instanceof Boolean)) throw new IllegalArgumentException("Expected boolean: " + label);
        break;
      case "array":
        if (!(value instanceof JSONArray)) throw new IllegalArgumentException("Expected array: " + label);
        JSONArray array = (JSONArray) value;
        if (array.length() < schema.optInt("minItems", 0) || array.length() > schema.optInt("maxItems", Integer.MAX_VALUE))
          throw new IllegalArgumentException("Array size outside allowed range: " + label);
        JSONObject item = schema.optJSONObject("items");
        if (item != null) for (int i = 0; i < array.length(); i++)
          value(item, array.get(i), parameters, template, depth + 1, label + "[" + i + "]");
        break;
      case "object":
        if (!(value instanceof JSONObject)) throw new IllegalArgumentException("Expected object: " + label);
        object(schema, (JSONObject) value, parameters, template, depth + 1);
        break;
      default: throw new IllegalArgumentException("Unsupported argument schema");
    }
    JSONArray options = schema.optJSONArray("enum");
    if (options != null) {
      boolean match = false;
      for (int i = 0; i < options.length(); i++) match |= options.get(i).equals(value);
      if (!match) throw new IllegalArgumentException("Unsupported " + label);
    }
  }
}
