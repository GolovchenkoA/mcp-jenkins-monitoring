package com.jenkinsmonitoring.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.nio.file.Path;

/** Small property groups that are only read, kept together so each is one obvious place to look. */
public final class AppProperties {

    private AppProperties() {
    }

    /** Binds {@code files.root.folder} and {@code files.storage.path}. */
    @ConfigurationProperties("files")
    public record Files(Root root, Storage storage) {

        public record Root(Path folder) {
        }

        public record Storage(Path path) {
        }

        public Path rootFolder() {
            return root.folder();
        }

        public Path storagePath() {
            return storage.path();
        }
    }

    @ConfigurationProperties("retention.policy")
    public record Retention(@DefaultValue("30") int days) {
    }

    @ConfigurationProperties("collector")
    public record Collector(@DefaultValue("20") int maxBuildsPerJobPerCycle,
                            @DefaultValue("200") int maxParameterValueLength) {
    }

    @ConfigurationProperties("tools")
    public record Tools(@DefaultValue RecentJobs recentJobs, @DefaultValue Notifications notifications) {

        public record RecentJobs(@DefaultValue("20") int defaultLimit) {
        }

        public record Notifications(@DefaultValue("200") int maxResults) {
        }
    }

    @ConfigurationProperties("scheduling")
    public record Scheduling(@DefaultValue JobCheck jenkinsJobCheck) {

        public record JobCheck(@DefaultValue("0 */5 * * * *") String cron) {
        }
    }

    @ConfigurationProperties("logging")
    public record Masking(@DefaultValue("(?i).*(password|token|secret|key).*") String maskParameterPattern) {
    }
}
