package dev.tally.obs;

import dev.tally.json.Json;
import dev.tally.json.JsonValue;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Formatter;
import java.util.logging.LogRecord;
import java.util.regex.Pattern;

/**
 * One JSON object per record, for a log collector. Selected with TALLY_LOG_FORMAT=json.
 *
 * The messages are already key=value text, so each pair becomes its own field and the rest is msg.
 */
public final class JsonFormatter extends Formatter {
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
    private static final Pattern PAIR = Pattern.compile("[A-Za-z][A-Za-z0-9_]*=\\S*");
    // Up to 18 digits always fits a long, so a numeric-looking value never fails to parse.
    private static final Pattern NUMBER = Pattern.compile("-?\\d{1,18}");
    private static final Set<String> RESERVED = Set.of("ts", "level", "requestId", "logger", "msg", "error", "stack");

    @Override
    public String format(LogRecord record) {
        Map<String, JsonValue> fields = new LinkedHashMap<>();
        fields.put("ts", new JsonValue.JsonString(TIMESTAMP.format(record.getInstant())));
        fields.put("level", new JsonValue.JsonString(record.getLevel().getName()));
        // Same ScopedValue read as LineFormatter, and safe for the same reason: publish is synchronous.
        if (RequestContext.CURRENT.isBound()) {
            fields.put("requestId", new JsonValue.JsonString(RequestContext.CURRENT.get().requestId()));
        }
        fields.put("logger", new JsonValue.JsonString(record.getLoggerName()));

        List<String> words = new ArrayList<>();
        Map<String, JsonValue> pairs = new LinkedHashMap<>();
        for (String token : formatMessage(record).split("\\s+")) {
            if (token.isEmpty()) {
                continue;
            }
            int eq = token.indexOf('=');
            String key = eq < 0 ? null : token.substring(0, eq);
            // A reserved or repeated key stays in the text. Letting a message set level or requestId would
            // let anything that reaches a log line forge them, and the JSON reader rejects duplicate keys.
            if (key != null && PAIR.matcher(token).matches() && !RESERVED.contains(key) && !pairs.containsKey(key)) {
                pairs.put(key, value(token.substring(eq + 1)));
            } else {
                words.add(token);
            }
        }
        if (!words.isEmpty()) {
            fields.put("msg", new JsonValue.JsonString(String.join(" ", words)));
        }
        fields.putAll(pairs);

        Throwable thrown = record.getThrown();
        if (thrown != null) {
            fields.put("error", new JsonValue.JsonString(thrown.getClass().getName() + ": " + thrown.getMessage()));
            List<JsonValue> frames = new ArrayList<>();
            for (StackTraceElement frame : thrown.getStackTrace()) {
                frames.add(new JsonValue.JsonString(frame.toString()));
            }
            fields.put("stack", new JsonValue.JsonArray(frames));
        }
        return Json.write(new JsonValue.JsonObject(fields)) + System.lineSeparator();
    }

    private static JsonValue value(String raw) {
        return NUMBER.matcher(raw).matches() ? new JsonValue.JsonNumber(Long.parseLong(raw)) : new JsonValue.JsonString(raw);
    }
}
