package com.jenkinsmonitoring.logging;

import com.jenkinsmonitoring.config.AppProperties;
import com.jenkinsmonitoring.storage.Json;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.regex.Pattern;

/**
 * Hides values of parameters whose names look like secrets. It is applied before a value is stored or
 * logged: the stored files and the debug log are plain text next to the jar.
 */
@Component
public class ParameterMasker {

    static final String MASK = "***";

    private final Pattern sensitiveName;

    public ParameterMasker(AppProperties.Masking properties) {
        this.sensitiveName = Pattern.compile(properties.maskParameterPattern());
    }

    public boolean isSensitive(String name) {
        return name != null && sensitiveName.matcher(name).matches();
    }

    public String maskValue(String name, String value) {
        return isSensitive(name) ? MASK : value;
    }

    /**
     * Compact JSON with sensitive values replaced. Text that is not JSON cannot be masked and is returned as
     * it is; Jenkins answers with JSON, and the credentials are never part of a body.
     */
    public String maskJson(String json) {
        if (json == null) {
            return "null";
        }
        try {
            JsonNode copy = Json.MAPPER.readTree(json).deepCopy();
            maskInPlace(copy);
            return Json.COMPACT.writeValueAsString(copy);
        } catch (RuntimeException e) {
            return json;
        }
    }

    public String maskObject(Object value) {
        try {
            return maskJson(Json.COMPACT.writeValueAsString(value));
        } catch (RuntimeException e) {
            return "<unprintable " + value.getClass().getSimpleName() + ">";
        }
    }

    private void maskInPlace(JsonNode node) {
        if (node instanceof ObjectNode object) {
            // Jenkins parameters look like {"name": "...", "value": "..."}
            JsonNode name = object.get("name");
            if (name != null && name.isString() && isSensitive(name.asText()) && object.has("value")) {
                object.put("value", MASK);
            }
            for (String key : new ArrayList<>(object.propertyNames())) {
                if (isSensitive(key)) {
                    object.put(key, MASK);
                } else {
                    maskInPlace(object.get(key));
                }
            }
        } else if (node instanceof ArrayNode array) {
            array.forEach(this::maskInPlace);
        }
    }
}
