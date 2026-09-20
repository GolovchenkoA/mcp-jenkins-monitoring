package com.jenkinsmonitoring.retention;

import com.jenkinsmonitoring.config.AppProperties;
import com.jenkinsmonitoring.storage.JobRepository;
import com.jenkinsmonitoring.storage.NotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * Deletes build records and notifications older than {@code retention.policy.days}. Rules and cursors are
 * never purged. The cursor is what prevents old builds from being read again, so purging cannot make an old
 * build look new.
 */
@Component
public class RetentionCleaner {

    private static final Logger log = LoggerFactory.getLogger(RetentionCleaner.class);

    private final JobRepository jobs;
    private final NotificationRepository notifications;
    private final AppProperties.Retention properties;
    private final Clock clock;

    public RetentionCleaner(JobRepository jobs, NotificationRepository notifications,
                            AppProperties.Retention properties, Clock clock) {
        this.jobs = jobs;
        this.notifications = notifications;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(cron = "${scheduling.retention-cleanup.cron}")
    public void scheduledRun() {
        runOnce();
    }

    public void runOnce() {
        LocalDateTime cutoff = LocalDateTime.now(clock).minusDays(properties.days());
        int deletedJobs = jobs.deleteOlderThan(cutoff);
        int deletedNotifications = notifications.deleteOlderThan(cutoff);
        log.info("Retention ({} days): deleted {} job records and {} notifications",
                properties.days(), deletedJobs, deletedNotifications);
    }
}
