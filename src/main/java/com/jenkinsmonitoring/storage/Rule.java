package com.jenkinsmonitoring.storage;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * A rule watches one job on one server. {@code conditions} narrows which builds of that job match; when it
 * is absent every build matches.
 *
 * @param server canonical server URL
 * @param job    exact {@code jobFullName}
 */
public record Rule(int id,
                   @JsonFormat(pattern = Formats.DATE_TIME) LocalDateTime createdAt,
                   String server,
                   String job,
                   Conditions conditions) {

    public Conditions conditionsOrNone() {
        return conditions == null ? Conditions.NONE : conditions;
    }

    public boolean watches(String otherServer, String otherJob) {
        return server.equals(otherServer) && job.equals(otherJob);
    }
}
