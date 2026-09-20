package com.jenkinsmonitoring.storage;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * A Jenkins build that matched a rule. Only the fields we need plus a few user-friendly extras are kept,
 * never the whole build response.
 *
 * @param createdAt   when the record was inserted
 * @param url         build URL exactly as Jenkins returned it; unique
 * @param server      canonical server URL
 * @param status      SUCCESS, FAILURE, UNSTABLE, ABORTED or UNKNOWN
 * @param triggeredBy the user id for user-started builds, otherwise the cause text
 * @param duration    human readable, for example {@code 3m 5s}
 */
public record JobRecord(@JsonFormat(pattern = Formats.DATE_TIME) LocalDateTime createdAt,
                        String url,
                        String server,
                        String job,
                        int buildNumber,
                        String status,
                        String triggeredBy,
                        String description,
                        @JsonFormat(pattern = Formats.DATE_TIME) LocalDateTime startedAt,
                        String duration,
                        String playbook,
                        Map<String, String> parameters,
                        List<String> scmBranches) {
}
