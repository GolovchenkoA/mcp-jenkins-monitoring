package com.jenkinsmonitoring.collector;

import com.jenkinsmonitoring.config.AppProperties;
import com.jenkinsmonitoring.jenkins.JenkinsGateway;
import com.jenkinsmonitoring.logging.ParameterMasker;
import com.jenkinsmonitoring.rules.RuleService;
import com.jenkinsmonitoring.server.ServerCatalog;
import com.jenkinsmonitoring.storage.CursorRepository;
import com.jenkinsmonitoring.storage.JobRepository;
import com.jenkinsmonitoring.storage.NotificationRepository;

import java.time.Clock;

/** Builds a collector the way the application does, for tests outside this package. */
public final class CollectorFixture {

    private CollectorFixture() {
    }

    public static Collector collector(RuleService rules, ServerCatalog servers, JenkinsGateway gateway,
                                      CursorRepository cursors, JobRepository jobs, NotificationRepository notifications,
                                      Clock clock) {
        var masker = new ParameterMasker(new AppProperties.Masking("(?i).*(password|token|secret|key).*"));
        var properties = new AppProperties.Collector(20, 200);
        var factory = new JobRecordFactory(masker, properties, clock);
        return new Collector(rules, servers, gateway, cursors, jobs, notifications, factory, properties, clock);
    }
}
