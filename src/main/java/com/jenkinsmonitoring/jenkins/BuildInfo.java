package com.jenkinsmonitoring.jenkins;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The parts of a Jenkins build the monitor needs, read from a {@code getBuild} response.
 *
 * @param status      normalized result, null while the build is running
 * @param nextBuild   number of the next build of the job, null when this is the latest
 * @param parameters  all build parameters in order; values may be long or secret, so filter before storing
 */
public record BuildInfo(int number,
                        String url,
                        String status,
                        boolean building,
                        LocalDateTime startedAt,
                        Long durationMs,
                        String description,
                        Integer nextBuild,
                        List<Cause> causes,
                        Map<String, String> parameters,
                        List<String> scmBranches) {

    public static final String PLAYBOOK_PARAMETER = "Playbook";

    public record Cause(String shortDescription, String userId, String userName) {
    }

    /** True once the build has a result and is no longer running. */
    public boolean finished() {
        return !building && status != null;
    }

    /** The Jenkins user id ({@code name.surname}) of whoever started the build, if a person did. */
    public Optional<String> userId() {
        return causes.stream()
                .map(Cause::userId)
                .filter(id -> id != null && !id.isBlank())
                .findFirst();
    }

    /** The user id for user-started builds, otherwise the text of the first cause, for example a merge request. */
    public String triggeredBy() {
        return userId().orElseGet(() -> causes.stream()
                .map(Cause::shortDescription)
                .filter(text -> text != null && !text.isBlank())
                .findFirst()
                .orElse(null));
    }

    /** Value of the Playbook parameter; only some jobs have it and it is often empty. */
    public Optional<String> playbook() {
        return parameter(PLAYBOOK_PARAMETER);
    }

    /** Case-insensitive lookup of a non-empty parameter value. */
    public Optional<String> parameter(String name) {
        return parameters.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .map(Map.Entry::getValue)
                .filter(value -> value != null && !value.isBlank())
                .findFirst();
    }
}
