package dev.tally.fraud;

import dev.tally.json.Json;
import dev.tally.json.JsonValue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Why a posting got its score: each rule's points, every feature value the rules could read, the earlier
 * postings the fired rules rest on, and how long building all of it took.
 */
public record Explanation(Map<String, Integer> points, Map<String, Long> features, List<Long> evidence, long micros) {
    public static final Explanation NONE = new Explanation(Map.of(), Map.of(), List.of(), 0);

    public Explanation {
        points = Collections.unmodifiableMap(new LinkedHashMap<>(points));
        features = Collections.unmodifiableMap(new LinkedHashMap<>(features));
        evidence = List.copyOf(evidence);
    }

    public JsonValue toJson() {
        Map<String, JsonValue> p = new LinkedHashMap<>();
        points.forEach((k, v) -> p.put(k, new JsonValue.JsonNumber(v)));
        Map<String, JsonValue> f = new LinkedHashMap<>();
        features.forEach((k, v) -> f.put(k, new JsonValue.JsonNumber(v)));
        List<JsonValue> e = new ArrayList<>();
        evidence.forEach(id -> e.add(new JsonValue.JsonNumber(id)));
        Map<String, JsonValue> m = new LinkedHashMap<>();
        m.put("points", new JsonValue.JsonObject(p));
        m.put("features", new JsonValue.JsonObject(f));
        m.put("evidence", new JsonValue.JsonArray(e));
        m.put("micros", new JsonValue.JsonNumber(micros));
        return new JsonValue.JsonObject(m);
    }

    // The store's own column, written by this class, so a malformed value is a bug and fails loudly.
    public static Explanation fromJson(String text) {
        if (text == null) {
            return NONE;
        }
        Map<String, JsonValue> m = ((JsonValue.JsonObject) Json.parse(text)).members();
        Map<String, Integer> p = new LinkedHashMap<>();
        ((JsonValue.JsonObject) m.get("points")).members().forEach((k, v) -> p.put(k, (int) ((JsonValue.JsonNumber) v).value()));
        Map<String, Long> f = new LinkedHashMap<>();
        ((JsonValue.JsonObject) m.get("features")).members().forEach((k, v) -> f.put(k, ((JsonValue.JsonNumber) v).value()));
        List<Long> e = new ArrayList<>();
        ((JsonValue.JsonArray) m.get("evidence")).items().forEach(v -> e.add(((JsonValue.JsonNumber) v).value()));
        return new Explanation(p, f, e, ((JsonValue.JsonNumber) m.get("micros")).value());
    }
}
