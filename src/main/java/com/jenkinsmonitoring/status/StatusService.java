package com.jenkinsmonitoring.status;

import com.jenkinsmonitoring.collector.Collector;
import com.jenkinsmonitoring.common.Threads;
import com.jenkinsmonitoring.config.AppProperties;
import com.jenkinsmonitoring.config.JenkinsProperties;
import com.jenkinsmonitoring.jenkins.JenkinsException;
import com.jenkinsmonitoring.jenkins.JenkinsGateway;
import com.jenkinsmonitoring.jenkins.ToolCatalog;
import com.jenkinsmonitoring.rules.RuleService;
import com.jenkinsmonitoring.server.JenkinsServer;
import com.jenkinsmonitoring.server.ServerCatalog;
import com.jenkinsmonitoring.storage.CursorRepository;
import com.jenkinsmonitoring.storage.JobRepository;
import com.jenkinsmonitoring.storage.NotificationRepository;
import com.jenkinsmonitoring.storage.RuleRepository;
import com.jenkinsmonitoring.storage.StorageException;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Answers "is the server healthy?" with one overall level and the list of checks behind it.
 * <ul>
 *   <li>CRITICAL: the collector cannot work (no usable server, a Jenkins host that does not resolve, storage
 *       that cannot be read, or no Jenkins server reachable at all);</li>
 *   <li>PROBLEMS: it works only partly (some servers down, stalled scheduler, skipped rules);</li>
 *   <li>OK otherwise.</li>
 * </ul>
 */
@Service
public class StatusService {

    public enum Level { OK, PROBLEMS, CRITICAL }

    public record Check(String name, Level status, String message) {
    }

    public record Report(Level status, List<Check> checks) {
    }

    private static final Duration DNS_TIMEOUT = Duration.ofSeconds(5);

    private final ServerCatalog servers;
    private final JenkinsGateway gateway;
    private final ToolCatalog toolCatalog;
    private final JenkinsProperties jenkins;
    private final RuleService rules;
    private final Collector collector;
    private final RuleRepository ruleRepository;
    private final JobRepository jobRepository;
    private final NotificationRepository notificationRepository;
    private final CursorRepository cursorRepository;
    private final AppProperties.Files files;
    private final AppProperties.Scheduling scheduling;
    private final Clock clock;

    public StatusService(ServerCatalog servers, JenkinsGateway gateway, ToolCatalog toolCatalog,
                         JenkinsProperties jenkins, RuleService rules, Collector collector,
                         RuleRepository ruleRepository, JobRepository jobRepository,
                         NotificationRepository notificationRepository, CursorRepository cursorRepository,
                         AppProperties.Files files, AppProperties.Scheduling scheduling, Clock clock) {
        this.servers = servers;
        this.gateway = gateway;
        this.toolCatalog = toolCatalog;
        this.jenkins = jenkins;
        this.rules = rules;
        this.collector = collector;
        this.ruleRepository = ruleRepository;
        this.jobRepository = jobRepository;
        this.notificationRepository = notificationRepository;
        this.cursorRepository = cursorRepository;
        this.files = files;
        this.scheduling = scheduling;
        this.clock = clock;
    }

    public Report report() {
        List<Check> checks = new ArrayList<>();
        configurationChecks(checks);
        serverChecks(checks);
        storageChecks(checks);
        ruleChecks(checks);
        schedulerCheck(checks);
        Level overall = checks.stream().map(Check::status).max(Comparator.naturalOrder()).orElse(Level.OK);
        return new Report(overall, checks);
    }

    private void configurationChecks(List<Check> checks) {
        if (servers.configuredCount() == 0) {
            checks.add(new Check("servers configured", Level.CRITICAL,
                    "No Jenkins server is configured. Add jenkins.server[n].url, protocol and auth to application.properties."));
        }
        servers.problems().forEach(problem ->
                checks.add(new Check("server " + problem.server(), Level.CRITICAL, problem.message())));
    }

    /** DNS, reachability and Jenkins' own status of every usable server, all servers at the same time. */
    private void serverChecks(List<Check> checks) {
        List<JenkinsServer> usable = servers.servers();
        if (usable.isEmpty()) {
            return;
        }
        List<ServerResult> results;
        // One thread per server, so a server that is slow to answer does not delay the others
        ExecutorService executor = Executors.newFixedThreadPool(usable.size(), Threads.daemonFactory("status-check"));
        try {
            List<CompletableFuture<ServerResult>> futures = usable.stream()
                    .map(server -> CompletableFuture.supplyAsync(() -> checkServer(server), executor))
                    .toList();
            results = futures.stream().map(CompletableFuture::join).toList();
        } finally {
            executor.shutdown();
        }
        results.forEach(result -> checks.addAll(result.checks()));
        long unavailable = results.stream().filter(result -> !result.available()).count();
        if (unavailable == 0) {
            checks.add(new Check("jenkins servers available", Level.OK, "All " + usable.size() + " server(s) answer."));
        } else if (unavailable < usable.size()) {
            checks.add(new Check("jenkins servers available", Level.PROBLEMS,
                    "Servers available partially: " + (usable.size() - unavailable) + " of " + usable.size() + "."));
        } else {
            checks.add(new Check("jenkins servers available", Level.CRITICAL, "No Jenkins server answers."));
        }
    }

    private record ServerResult(boolean available, List<Check> checks) {
    }

    private ServerResult checkServer(JenkinsServer server) {
        List<Check> checks = new ArrayList<>();
        String name = server.canonical();
        if (!resolves(server.host())) {
            checks.add(new Check("dns " + name, Level.CRITICAL,
                    "The host name " + server.host() + " cannot be resolved. Is the VPN on?"));
            return new ServerResult(false, checks);
        }
        checks.add(new Check("dns " + name, Level.OK, "The host name resolves."));
        try {
            gateway.whoAmI(server);
            checks.add(new Check("jenkins " + name, Level.OK, "Reachable, credentials accepted."));
        } catch (JenkinsException.Unavailable e) {
            String message = e.isAuthFailure() ? "The server rejected the configured credentials." : e.getMessage();
            checks.add(new Check("jenkins " + name, Level.PROBLEMS, message));
            return new ServerResult(false, checks);
        } catch (JenkinsException e) {
            checks.add(new Check("jenkins " + name, Level.PROBLEMS, e.getMessage()));
            return new ServerResult(false, checks);
        }
        checks.add(toolsCheck(server));
        checks.add(jenkinsHealth(server));
        return new ServerResult(true, checks);
    }

    /** The tools the server offered when we connected must include every tool we are allowed to use. */
    private Check toolsCheck(JenkinsServer server) {
        String name = "jenkins tools " + server.canonical();
        List<ToolCatalog.ToolInfo> offered = toolCatalog.toolsOf(server.canonical());
        if (offered.isEmpty()) {
            return new Check(name, Level.PROBLEMS, "The tools of this server could not be listed.");
        }
        List<String> missing = jenkins.allowedTools().stream()
                .filter(tool -> offered.stream().noneMatch(info -> info.name().equals(tool)))
                .toList();
        return missing.isEmpty()
                ? new Check(name, Level.OK, "The server offers all tools we use.")
                : new Check(name, Level.PROBLEMS, "The server does not offer: " + String.join(", ", missing) + ".");
    }

    private Check jenkinsHealth(JenkinsServer server) {
        String name = "jenkins health " + server.canonical();
        try {
            JsonNode status = gateway.getStatus(server);
            List<String> issues = new ArrayList<>();
            if (status.path("Quiet Mode").asBoolean(false)) {
                issues.add("Jenkins is in quiet mode (preparing to shut down)");
            }
            if (status.path("Active administrative monitors").size() > 0) {
                issues.add(status.path("Active administrative monitors").size() + " active administrative monitor(s)");
            }
            String rootUrl = status.path("Root URL Status").asText("OK");
            if (!"OK".equalsIgnoreCase(rootUrl)) {
                issues.add("Root URL status is " + rootUrl);
            }
            return issues.isEmpty()
                    ? new Check(name, Level.OK, "Jenkins reports no problems.")
                    : new Check(name, Level.PROBLEMS, String.join("; ", issues) + ".");
        } catch (JenkinsException e) {
            return new Check(name, Level.PROBLEMS, "Could not read the Jenkins status: " + e.getMessage());
        }
    }

    /**
     * The lookup runs on a thread of its own that nobody waits for: a resolver that hangs must not delay
     * the answer beyond the timeout, and must not keep the executor of the checks from closing.
     */
    private static boolean resolves(String host) {
        CompletableFuture<InetAddress> lookup = new CompletableFuture<>();
        Threads.startDaemon("dns-lookup", () -> {
            try {
                lookup.complete(InetAddress.getByName(host));
            } catch (java.net.UnknownHostException | RuntimeException e) {
                lookup.completeExceptionally(e);
            }
        });
        try {
            lookup.get(DNS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            return false;
        }
    }

    private void storageChecks(List<Check> checks) {
        try {
            try {
                // The folder is created at startup; if it was removed meanwhile, creating it again is the repair
                Files.createDirectories(files.storagePath());
            } catch (IOException e) {
                throw new StorageException("The storage folder " + files.storagePath() + " cannot be created: " + e.getMessage(), e);
            }
            if (!Files.isWritable(files.storagePath())) {
                throw new StorageException("The storage folder " + files.storagePath() + " is not writable", null);
            }
            ruleRepository.verify();
            jobRepository.verify();
            notificationRepository.verify();
            cursorRepository.verify();
            checks.add(new Check("storage", Level.OK, "All files are readable and valid."));
        } catch (StorageException e) {
            checks.add(new Check("storage", Level.CRITICAL, e.getMessage()));
        }
    }

    private void ruleChecks(List<Check> checks) {
        List<String> issues = new ArrayList<>(rules.problems());
        collector.problems().forEach((ruleId, problem) -> issues.add("Rule " + ruleId + ": " + problem));
        if (issues.isEmpty()) {
            checks.add(new Check("rules", Level.OK, rules.list().size() + " rule(s) active."));
        } else {
            issues.forEach(issue -> checks.add(new Check("rules", Level.PROBLEMS, issue)));
        }
    }

    /** The collector should finish a pass at least every interval; twice that is treated as stalled. */
    private void schedulerCheck(List<Check> checks) {
        CronExpression cron = CronExpression.parse(scheduling.jenkinsJobCheck().cron());
        ZonedDateTime now = ZonedDateTime.now(clock);
        ZonedDateTime first = cron.next(now);
        ZonedDateTime second = first == null ? null : cron.next(first);
        if (second == null) {
            checks.add(new Check("collector", Level.OK, "No schedule to check."));
            return;
        }
        Duration interval = Duration.between(first, second);
        Instant lastActivity = collector.lastActivity();
        Duration idle = Duration.between(lastActivity, clock.instant());
        if (idle.compareTo(interval.multipliedBy(2).plusSeconds(30)) > 0) {
            checks.add(new Check("collector", Level.PROBLEMS, "The collector has not finished a run since " + lastActivity + "."));
        } else {
            checks.add(new Check("collector", Level.OK, "The collector is running on schedule."));
        }
    }
}
