package com.jenkinsmonitoring.storage;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * A notification for one build matched by one rule. {@code server}, {@code job}, {@code buildNumber} and
 * {@code buildStatus} are copies that make the file readable without opening {@code jobs.json}.
 */
public record NotificationRecord(@JsonFormat(pattern = Formats.DATE_TIME) LocalDateTime createdAt,
                                 int ruleId,
                                 String url,
                                 NotificationStatus status,
                                 String server,
                                 String job,
                                 int buildNumber,
                                 String buildStatus) {
}
