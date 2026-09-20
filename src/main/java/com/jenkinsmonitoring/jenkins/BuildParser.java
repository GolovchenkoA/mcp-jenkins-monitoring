package com.jenkinsmonitoring.jenkins;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Reads the {@code result} of a {@code getBuild} call made with the configured tree. */
public final class BuildParser {

    private BuildParser() {
    }

    public static BuildInfo parse(JsonNode build) {
        JsonNode number = build.path("number");
        if (!number.isNumber()) {
            throw new IllegalArgumentException("The build response has no build number");
        }
        List<BuildInfo.Cause> causes = new ArrayList<>();
        Map<String, String> parameters = new LinkedHashMap<>();
        Set<String> branches = new LinkedHashSet<>();
        for (JsonNode action : build.path("actions")) {
            for (JsonNode cause : action.path("causes")) {
                causes.add(new BuildInfo.Cause(text(cause, "shortDescription"), text(cause, "userId"), text(cause, "userName")));
            }
            for (JsonNode parameter : action.path("parameters")) {
                String name = text(parameter, "name");
                if (name != null) {
                    parameters.putIfAbsent(name, parameter.path("value").asText(""));
                }
            }
            for (JsonNode branch : action.path("lastBuiltRevision").path("branch")) {
                String name = text(branch, "name");
                if (name != null) {
                    branches.add(name);
                }
            }
        }
        boolean building = build.path("building").asBoolean(false) || build.path("inProgress").asBoolean(false);
        JsonNode next = build.path("nextBuild").path("number");
        return new BuildInfo(
                number.asInt(),
                text(build, "url"),
                StatusNormalizer.normalize(build.get("result")),
                building,
                startedAt(build.path("timestamp")),
                build.path("duration").isNumber() ? build.path("duration").asLong() : null,
                text(build, "description"),
                next.isNumber() ? next.asInt() : null,
                causes,
                parameters,
                new ArrayList<>(branches));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isString() && !value.asText().isBlank() ? value.asText() : null;
    }

    private static LocalDateTime startedAt(JsonNode timestamp) {
        return timestamp.isNumber()
                ? LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp.asLong()), ZoneId.systemDefault())
                : null;
    }
}
