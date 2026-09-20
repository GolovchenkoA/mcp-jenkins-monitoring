package com.jenkinsmonitoring.jenkins;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Turns the {@code result} of a build into one of SUCCESS, FAILURE, UNSTABLE, ABORTED or UNKNOWN. It is
 * normally a plain string; for anything else (the shape of failed builds is not known) we look for a status
 * inside it, and otherwise say UNKNOWN and log which keys we saw, never their values.
 */
final class StatusNormalizer {

    static final String UNKNOWN = "UNKNOWN";

    private static final Logger log = LoggerFactory.getLogger(StatusNormalizer.class);
    private static final Set<String> KNOWN = Set.of("SUCCESS", "FAILURE", "UNSTABLE", "ABORTED");
    private static final List<String> STATUS_KEYS = List.of("result", "status", "name", "value");

    private StatusNormalizer() {
    }

    /** The normalized status, or null while the build has no result yet (still running). */
    static String normalize(JsonNode result) {
        if (result == null || result.isMissingNode() || result.isNull()) {
            return null;
        }
        if (result.isString()) {
            String upper = result.asText().trim().toUpperCase(Locale.ROOT);
            return KNOWN.contains(upper) ? upper : UNKNOWN;
        }
        if (result.isObject()) {
            for (String key : STATUS_KEYS) {
                JsonNode candidate = result.get(key);
                if (candidate != null && (candidate.isString() || candidate.isObject())) {
                    String status = normalize(candidate);
                    if (status != null) {
                        return status;
                    }
                }
            }
            log.warn("Unrecognised build result, keys: {}", result.propertyNames());
            return UNKNOWN;
        }
        log.warn("Unrecognised build result of type {}", result.getNodeType());
        return UNKNOWN;
    }
}
