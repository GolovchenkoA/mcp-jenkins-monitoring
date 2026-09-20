package com.jenkinsmonitoring.storage;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The optional conditions of a rule. Each key has a list of accepted values (any of). In the JSON file a
 * single value is written as a plain string and several values as a list, which is easier to edit by hand.
 */
public final class Conditions {

    public static final Conditions NONE = new Conditions(Map.of());

    private final Map<String, List<String>> values;

    public Conditions(Map<String, List<String>> values) {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        values.forEach((key, list) -> copy.put(key, List.copyOf(list)));
        this.values = java.util.Collections.unmodifiableMap(copy);
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    static Conditions fromJson(Map<String, Object> raw) {
        Map<String, List<String>> parsed = new LinkedHashMap<>();
        raw.forEach((key, value) -> parsed.put(key, toStrings(value)));
        return new Conditions(parsed);
    }

    @JsonValue
    Map<String, Object> toJson() {
        Map<String, Object> json = new LinkedHashMap<>();
        values.forEach((key, list) -> json.put(key, list.size() == 1 ? list.get(0) : list));
        return json;
    }

    public Map<String, List<String>> asMap() {
        return values;
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    /** A value or a list of values as trimmed strings; numbers and booleans work too, nulls are dropped. */
    public static List<String> toStrings(Object value) {
        List<String> result = new ArrayList<>();
        if (value instanceof Iterable<?> iterable) {
            iterable.forEach(item -> addString(result, item));
        } else {
            addString(result, value);
        }
        return result;
    }

    private static void addString(List<String> target, Object item) {
        if (item != null) {
            target.add(String.valueOf(item).trim());
        }
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Conditions that && values.equals(that.values);
    }

    @Override
    public int hashCode() {
        return Objects.hash(values);
    }

    @Override
    public String toString() {
        return toJson().toString();
    }
}
