package com.jenkinsmonitoring.storage;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Where the collector stopped for one (server, job): the highest build number it has seen, whether or not
 * that build matched a rule. Builds seen but still running are kept in {@code openBuilds} and re-checked
 * until they finish, so a slow build never blocks the ones after it.
 *
 * @param url URL of the build {@code buildId}, as returned by Jenkins; absent when that build does not exist
 */
public record Cursor(@JsonFormat(pattern = Formats.DATE_TIME) LocalDateTime createdAt,
                     @JsonFormat(pattern = Formats.DATE_TIME) LocalDateTime updatedAt,
                     String server,
                     String job,
                     int buildId,
                     String url,
                     List<Integer> openBuilds) {

    public Cursor {
        openBuilds = openBuilds == null ? List.of() : List.copyOf(openBuilds);
    }

    public boolean is(String otherServer, String otherJob) {
        return server.equals(otherServer) && job.equals(otherJob);
    }

    public Cursor advancedTo(int build, String buildUrl, List<Integer> open, LocalDateTime now) {
        return new Cursor(createdAt, now, server, job, build, buildUrl, open);
    }

    public Cursor withOpenBuilds(List<Integer> open, LocalDateTime now) {
        return new Cursor(createdAt, now, server, job, buildId, url, open);
    }
}
