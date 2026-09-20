package com.jenkinsmonitoring.rules;

import com.jenkinsmonitoring.storage.Conditions;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Checks and normalizes the conditions given for a rule. {@code user}, {@code status} and {@code playbook}
 * are known keys; any other key is taken to be the name of a build parameter and cannot be verified.
 */
public final class ConditionsValidator {

    static final String USER = "user";
    static final String STATUS = "status";
    static final String PLAYBOOK = "playbook";
    static final Set<String> STATUSES = Set.of("SUCCESS", "FAILURE", "UNSTABLE", "ABORTED");

    private static final Set<String> RESERVED_KEYS = Set.of(USER, STATUS, PLAYBOOK);
    private static final Pattern USER_ID = Pattern.compile("^[\\p{L}\\p{N}_-]+\\.[\\p{L}\\p{N}_-]+$");

    private ConditionsValidator() {
    }

    public static Conditions validate(Map<String, ?> raw) {
        if (raw == null || raw.isEmpty()) {
            return Conditions.NONE;
        }
        Map<String, List<String>> normalized = new LinkedHashMap<>();
        Set<String> seenKeys = new java.util.HashSet<>();
        raw.forEach((rawKey, rawValue) -> {
            String key = rawKey == null ? "" : rawKey.trim();
            if (key.isEmpty()) {
                throw new RuleException("A condition needs a name, for example user, status, playbook or a build parameter name.");
            }
            String canonicalKey = RESERVED_KEYS.contains(key.toLowerCase(Locale.ROOT)) ? key.toLowerCase(Locale.ROOT) : key;
            if (!seenKeys.add(canonicalKey.toLowerCase(Locale.ROOT))) {
                throw new RuleException("The condition '" + key + "' is given more than once.");
            }
            normalized.put(canonicalKey, values(canonicalKey, rawValue));
        });
        return new Conditions(normalized);
    }

    private static List<String> values(String key, Object rawValue) {
        List<String> values = Conditions.toStrings(rawValue);
        if (values.isEmpty() || values.stream().anyMatch(String::isEmpty)) {
            throw new RuleException("The condition '" + key + "' needs at least one non-empty value.");
        }
        return switch (key) {
            case USER -> values.stream().map(ConditionsValidator::userId).toList();
            case STATUS -> values.stream().map(ConditionsValidator::status).toList();
            default -> values;
        };
    }

    private static String userId(String value) {
        if (!USER_ID.matcher(value).matches()) {
            throw new RuleException("Invalid user '" + value + "'. Use the form name.surname, for example artem.holovchenko.");
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private static String status(String value) {
        String upper = value.toUpperCase(Locale.ROOT);
        if (!STATUSES.contains(upper)) {
            throw new RuleException("Invalid status '" + value + "'. Use one of SUCCESS, FAILURE, UNSTABLE, ABORTED.");
        }
        return upper;
    }
}
