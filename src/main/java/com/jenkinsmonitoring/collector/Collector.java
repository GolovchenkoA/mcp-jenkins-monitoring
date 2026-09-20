package com.jenkinsmonitoring.collector;

import com.jenkinsmonitoring.config.AppProperties;
import com.jenkinsmonitoring.jenkins.BuildInfo;
import com.jenkinsmonitoring.jenkins.BuildParser;
import com.jenkinsmonitoring.jenkins.JenkinsException;
import com.jenkinsmonitoring.jenkins.JenkinsGateway;
import com.jenkinsmonitoring.rules.RuleMatcher;
import com.jenkinsmonitoring.rules.RuleService;
import com.jenkinsmonitoring.server.JenkinsServer;
import com.jenkinsmonitoring.server.ServerCatalog;
import com.jenkinsmonitoring.storage.Cursor;
import com.jenkinsmonitoring.storage.CursorRepository;
import com.jenkinsmonitoring.storage.JobRepository;
import com.jenkinsmonitoring.storage.NotificationRecord;
import com.jenkinsmonitoring.storage.NotificationRepository;
import com.jenkinsmonitoring.storage.NotificationStatus;
import com.jenkinsmonitoring.storage.Rule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

/**
 * Finds new builds of the watched jobs. For each rule it keeps a cursor, the highest build number seen, and
 * follows Jenkins' {@code nextBuild} links from there. Finished builds are matched against the rule; matches
 * go to {@code jobs.json} and get a PENDING notification.
 *
 * <p>A crash is harmless: a build is saved first and the cursor moves afterwards, and saving is idempotent
 * by build URL, so at worst one build is processed twice without any duplicate.
 *
 * <p>Everything the collector writes for a rule goes through {@link RuleService#runIfWatched}, so a rule
 * removed while a pass is running cannot get its cursor or its records back.
 */
@Component
public class Collector {

    private static final Logger log = LoggerFactory.getLogger(Collector.class);

    private final RuleService rules;
    private final ServerCatalog servers;
    private final JenkinsGateway gateway;
    private final CursorRepository cursors;
    private final JobRepository jobs;
    private final NotificationRepository notifications;
    private final JobRecordFactory recordFactory;
    private final AppProperties.Collector properties;
    private final Clock clock;

    private final Instant startedAt;
    private volatile Instant lastCompleted;
    private final ConcurrentMap<Integer, String> problems = new ConcurrentHashMap<>();

    public Collector(RuleService rules, ServerCatalog servers, JenkinsGateway gateway, CursorRepository cursors,
                     JobRepository jobs, NotificationRepository notifications, JobRecordFactory recordFactory,
                     AppProperties.Collector properties, Clock clock) {
        this.rules = rules;
        this.servers = servers;
        this.gateway = gateway;
        this.cursors = cursors;
        this.jobs = jobs;
        this.notifications = notifications;
        this.recordFactory = recordFactory;
        this.properties = properties;
        this.clock = clock;
        this.startedAt = clock.instant();
    }

    @Scheduled(cron = "${scheduling.jenkins-job-check.cron}")
    public void scheduledRun() {
        runOnce();
    }

    /** One pass over all rules. A problem with one rule or one server never stops the others. */
    public void runOnce() {
        List<Rule> current = rules.list();
        Set<Integer> ids = current.stream().map(Rule::id).collect(Collectors.toSet());
        problems.keySet().retainAll(ids); // a removed rule takes its problem with it
        Set<String> unavailable = new HashSet<>();
        for (Rule rule : current) {
            if (unavailable.contains(rule.server())) {
                continue;
            }
            Optional<JenkinsServer> server = servers.byCanonical(rule.server());
            if (server.isEmpty()) {
                problems.put(rule.id(), "Server " + rule.server() + " is not usable; see the server problems.");
                continue;
            }
            problems.remove(rule.id());
            try {
                process(rule, server.get());
            } catch (JenkinsException.Unavailable e) {
                // Skip the other rules of this server too: each would wait for the same timeout
                unavailable.add(rule.server());
                problems.put(rule.id(), e.getMessage());
                log.warn("{}", e.getMessage());
            } catch (JenkinsException.NotFound e) {
                problems.put(rule.id(), "Job '" + rule.job() + "' was not found on " + rule.server() + ".");
                log.warn("Rule {}: job '{}' was not found on {}", rule.id(), rule.job(), rule.server());
            } catch (RuntimeException e) {
                problems.put(rule.id(), e.getClass().getSimpleName() + ": " + e.getMessage());
                log.error("Rule {} could not be processed", rule.id(), e);
            }
        }
        lastCompleted = clock.instant();
    }

    /** When the last pass finished, or when the application started if none has finished yet. */
    public Instant lastActivity() {
        return lastCompleted != null ? lastCompleted : startedAt;
    }

    /** Problems per rule id from the latest pass. */
    public Map<Integer, String> problems() {
        return Map.copyOf(problems);
    }

    private void process(Rule rule, JenkinsServer server) {
        Optional<Cursor> cursor = cursors.find(server.canonical(), rule.job());
        if (cursor.isEmpty()) {
            bootstrap(rule, server);
            return;
        }
        Map<Integer, BuildInfo> seen = new HashMap<>();
        Cursor current = cursor.get();
        Optional<BuildInfo> cursorBuild = fetch(server, rule, current.buildId(), seen);
        if (cursorBuild.isPresent()) {
            current = followNextBuilds(rule, server, current, cursorBuild.get(), seen);
        } else {
            current = scanMissing(rule, server, current, seen);
        }
        recheckOpenBuilds(rule, server, current, seen);
    }

    /**
     * First time a job is seen: remember its latest build without notifying about it or older ones. A build
     * that is still running is tracked and reported when it finishes.
     */
    private void bootstrap(Rule rule, JenkinsServer server) {
        LocalDateTime now = LocalDateTime.now(clock);
        BuildInfo latest;
        try {
            latest = BuildParser.parse(gateway.getBuild(server, rule.job(), null));
        } catch (JenkinsException.NotFound noBuilds) {
            // The job exists but has never run: the next build that appears is a new one
            int next = gateway.getJob(server, rule.job(), "nextBuildNumber").path("nextBuildNumber").asInt(1);
            saveCursor(rule, new Cursor(now, now, server.canonical(), rule.job(), Math.max(next - 1, 0), null, List.of()));
            return;
        }
        List<Integer> open = latest.finished() ? List.of() : List.of(latest.number());
        saveCursor(rule, new Cursor(now, now, server.canonical(), rule.job(), latest.number(), latest.url(), open));
    }

    private Cursor followNextBuilds(Rule rule, JenkinsServer server, Cursor cursor, BuildInfo cursorBuild,
                                    Map<Integer, BuildInfo> seen) {
        Cursor current = cursor;
        BuildInfo last = cursorBuild;
        int budget = properties.maxBuildsPerJobPerCycle();
        while (budget-- > 0 && last.nextBuild() != null) {
            Optional<BuildInfo> next = fetch(server, rule, last.nextBuild(), seen);
            if (next.isEmpty()) {
                // Discarded meanwhile, or not visible to our account; the next pass looks again
                problems.put(rule.id(), "Build #" + last.nextBuild() + " of job '" + rule.job() + "' is linked but cannot be read.");
                break;
            }
            current = handleNewBuild(rule, server, current, next.get());
            last = next.get();
        }
        return current;
    }

    /**
     * The cursor build no longer exists (Jenkins discards old builds) or the job never ran. Walk the build
     * numbers after the cursor up to the job's next build number, skipping the ones that are gone. If the
     * numbers went backwards the job was recreated, and we start over without notifications.
     */
    private Cursor scanMissing(Rule rule, JenkinsServer server, Cursor cursor, Map<Integer, BuildInfo> seen) {
        int nextNumber = gateway.getJob(server, rule.job(), "nextBuildNumber").path("nextBuildNumber").asInt(0);
        if (nextNumber > 0 && nextNumber <= cursor.buildId()) {
            problems.put(rule.id(), "The build numbers of job '" + rule.job() + "' were reset; resumed from its latest build.");
            rules.runIfWatched(rule, () -> cursors.delete(server.canonical(), rule.job()));
            bootstrap(rule, server);
            return cursors.find(server.canonical(), rule.job()).orElse(cursor);
        }
        if (nextNumber <= 0) {
            problems.put(rule.id(), "Jenkins did not say what the next build number of job '" + rule.job() + "' is.");
            return cursor;
        }
        Cursor current = cursor;
        int last = Math.min(nextNumber - 1, cursor.buildId() + properties.maxBuildsPerJobPerCycle());
        for (int number = cursor.buildId() + 1; number <= last; number++) {
            Optional<BuildInfo> build = fetch(server, rule, number, seen);
            if (build.isPresent()) {
                current = handleNewBuild(rule, server, current, build.get());
            }
        }
        if (last > current.buildId()) {
            // Only missing numbers were left: one save moves the cursor past all of them
            current = current.advancedTo(last, null, current.openBuilds(), LocalDateTime.now(clock));
            saveCursor(rule, current);
        }
        return current;
    }

    /** A build the cursor has not passed yet. Finished builds are stored; running ones are tracked. */
    private Cursor handleNewBuild(Rule rule, JenkinsServer server, Cursor cursor, BuildInfo build) {
        List<Integer> open = new ArrayList<>(cursor.openBuilds());
        if (build.finished()) {
            store(rule, server, build);
        } else if (!open.contains(build.number())) {
            open.add(build.number());
        }
        // Cursor last: if we crash before this line the build is simply handled again
        Cursor moved = cursor.advancedTo(build.number(), build.url(), open, LocalDateTime.now(clock));
        saveCursor(rule, moved);
        return moved;
    }

    private void recheckOpenBuilds(Rule rule, JenkinsServer server, Cursor cursor, Map<Integer, BuildInfo> seen) {
        List<Integer> stillOpen = new ArrayList<>();
        for (int number : cursor.openBuilds()) {
            Optional<BuildInfo> build = fetch(server, rule, number, seen);
            if (build.isEmpty()) {
                continue; // discarded by Jenkins, nothing left to wait for
            }
            if (build.get().finished()) {
                store(rule, server, build.get());
            } else {
                stillOpen.add(number);
            }
        }
        if (!stillOpen.equals(cursor.openBuilds())) {
            saveCursor(rule, cursor.withOpenBuilds(stillOpen, LocalDateTime.now(clock)));
        }
    }

    private void store(Rule rule, JenkinsServer server, BuildInfo build) {
        if (!RuleMatcher.matches(rule, build)) {
            return;
        }
        rules.runIfWatched(rule, () -> {
            jobs.insertIfAbsent(recordFactory.create(server.canonical(), rule.job(), build));
            notifications.insertIfAbsent(new NotificationRecord(LocalDateTime.now(clock), rule.id(), build.url(),
                    NotificationStatus.PENDING, server.canonical(), rule.job(), build.number(), build.status()));
        });
    }

    private void saveCursor(Rule rule, Cursor cursor) {
        rules.runIfWatched(rule, () -> cursors.save(cursor));
    }

    /** One call per build and pass: a build that is both the cursor and open is not fetched twice. */
    private Optional<BuildInfo> fetch(JenkinsServer server, Rule rule, int number, Map<Integer, BuildInfo> seen) {
        BuildInfo cached = seen.get(number);
        if (cached != null) {
            return Optional.of(cached);
        }
        try {
            JsonNode node = gateway.getBuild(server, rule.job(), number);
            BuildInfo build = BuildParser.parse(node);
            seen.put(number, build);
            return Optional.of(build);
        } catch (JenkinsException.NotFound gone) {
            return Optional.empty();
        }
    }
}
