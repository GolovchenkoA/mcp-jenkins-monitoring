package com.jenkinsmonitoring.rules;

import com.jenkinsmonitoring.jenkins.BuildInfo;
import com.jenkinsmonitoring.storage.Rule;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Decides whether a build matches the conditions of its rule. All conditions must match. Names and values
 * are compared ignoring case, a list of values means any of them, and a condition on something the build
 * does not have (an unset parameter, no user) does not match.
 */
public final class RuleMatcher {

    private RuleMatcher() {
    }

    public static boolean matches(Rule rule, BuildInfo build) {
        for (Map.Entry<String, List<String>> condition : rule.conditionsOrNone().asMap().entrySet()) {
            if (!matches(condition.getKey(), condition.getValue(), build)) {
                return false;
            }
        }
        return true;
    }

    private static boolean matches(String key, List<String> accepted, BuildInfo build) {
        Optional<String> actual = switch (key.toLowerCase(Locale.ROOT)) {
            case ConditionsValidator.USER -> build.userId();
            case ConditionsValidator.STATUS -> Optional.ofNullable(build.status());
            case ConditionsValidator.PLAYBOOK -> build.playbook();
            default -> build.parameter(key);
        };
        return actual.isPresent() && accepted.stream().anyMatch(value -> value.equalsIgnoreCase(actual.get()));
    }
}
