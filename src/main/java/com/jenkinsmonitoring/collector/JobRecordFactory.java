package com.jenkinsmonitoring.collector;

import com.jenkinsmonitoring.config.AppProperties;
import com.jenkinsmonitoring.jenkins.BuildInfo;
import com.jenkinsmonitoring.logging.ParameterMasker;
import com.jenkinsmonitoring.storage.JobRecord;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/** Builds the record we keep for a build: the useful fields only, never the whole build response. */
@Component
class JobRecordFactory {

    private final ParameterMasker masker;
    private final AppProperties.Collector properties;
    private final Clock clock;

    JobRecordFactory(ParameterMasker masker, AppProperties.Collector properties, Clock clock) {
        this.masker = masker;
        this.properties = properties;
        this.clock = clock;
    }

    JobRecord create(String server, String job, BuildInfo build) {
        return new JobRecord(LocalDateTime.now(clock), build.url(), server, job, build.number(), build.status(),
                build.triggeredBy(), build.description(), build.startedAt(), formatDuration(build.durationMs()),
                build.playbook().orElse(null), storedParameters(build), build.scmBranches());
    }

    /**
     * Long or multi-line values (typically JSON settings blobs) are left out, secrets are masked, and the
     * Playbook is not repeated because it has its own field.
     */
    Map<String, String> storedParameters(BuildInfo build) {
        Map<String, String> stored = new LinkedHashMap<>();
        build.parameters().forEach((name, value) -> {
            boolean useful = value != null && !value.isBlank()
                    && value.length() <= properties.maxParameterValueLength()
                    && value.indexOf('\n') < 0
                    && !name.equalsIgnoreCase(BuildInfo.PLAYBOOK_PARAMETER);
            if (useful) {
                stored.put(name, masker.maskValue(name, value));
            }
        });
        return stored;
    }

    static String formatDuration(Long millis) {
        if (millis == null) {
            return null;
        }
        Duration duration = Duration.ofMillis(millis);
        long hours = duration.toHours();
        int minutes = duration.toMinutesPart();
        int seconds = duration.toSecondsPart();
        if (hours > 0) {
            return hours + "h " + minutes + "m " + seconds + "s";
        }
        if (minutes > 0) {
            return minutes + "m " + seconds + "s";
        }
        return seconds > 0 ? seconds + "s" : "<1s";
    }
}
