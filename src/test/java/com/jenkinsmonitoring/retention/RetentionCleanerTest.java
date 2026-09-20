package com.jenkinsmonitoring.retention;

import com.jenkinsmonitoring.config.AppProperties;
import com.jenkinsmonitoring.storage.Cursor;
import com.jenkinsmonitoring.storage.CursorRepository;
import com.jenkinsmonitoring.storage.JobRecord;
import com.jenkinsmonitoring.storage.JobRepository;
import com.jenkinsmonitoring.storage.NotificationRecord;
import com.jenkinsmonitoring.storage.NotificationRepository;
import com.jenkinsmonitoring.storage.NotificationStatus;
import com.jenkinsmonitoring.storage.Rule;
import com.jenkinsmonitoring.storage.RuleRepository;
import com.jenkinsmonitoring.support.TestServers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RetentionCleanerTest {

    @TempDir
    Path folder;

    @Test
    void deletesRecordsOlderThanTheConfiguredDaysAndKeepsTheRest() {
        var files = TestServers.files(folder);
        var jobs = new JobRepository(files);
        var notifications = new NotificationRepository(files);
        Clock clock = Clock.fixed(Instant.parse("2026-09-20T12:00:00Z"), ZoneId.of("UTC"));
        LocalDateTime now = LocalDateTime.now(clock);
        jobs.insertIfAbsent(job("old", now.minusDays(31)));
        jobs.insertIfAbsent(job("recent", now.minusDays(29)));
        notifications.insertIfAbsent(notification("old", now.minusDays(31)));
        notifications.insertIfAbsent(notification("recent", now.minusDays(1)));

        new RetentionCleaner(jobs, notifications, new AppProperties.Retention(30), clock).runOnce();

        assertThat(jobs.findAll()).extracting(JobRecord::url).containsExactly("recent");
        assertThat(notifications.find(null)).extracting(NotificationRecord::url).containsExactly("recent");
    }

    private static JobRecord job(String url, LocalDateTime createdAt) {
        return new JobRecord(createdAt, url, "s", "j", 1, "SUCCESS", null, null, null, null, null, Map.of(), List.of());
    }

    private static NotificationRecord notification(String url, LocalDateTime createdAt) {
        return new NotificationRecord(createdAt, 1, url, NotificationStatus.PENDING, "s", "j", 1, "SUCCESS");
    }

    @Test
    void rulesCursorsAndTheIdCounterAreNeverPurged() {
        var files = TestServers.files(folder);
        var rules = new RuleRepository(files);
        var cursors = new CursorRepository(files);
        Clock clock = Clock.fixed(Instant.parse("2026-09-20T12:00:00Z"), ZoneId.of("UTC"));
        LocalDateTime ancient = LocalDateTime.now(clock).minusDays(400);
        rules.saveAll(List.of(new Rule(1, ancient, "s", "j", null)), List.of(), List.of());
        rules.nextId(List.of());
        cursors.save(new Cursor(ancient, ancient, "s", "j", 5, null, null));

        new RetentionCleaner(new JobRepository(files), new NotificationRepository(files), new AppProperties.Retention(30), clock).runOnce();

        assertThat(rules.load().rules()).hasSize(1);
        assertThat(cursors.find("s", "j")).isPresent();
        assertThat(rules.nextId(List.of())).isEqualTo(2);
    }
}
