package com.jenkinsmonitoring.rules;

import com.jenkinsmonitoring.jenkins.JenkinsException;
import com.jenkinsmonitoring.jenkins.JenkinsGateway;
import com.jenkinsmonitoring.server.JenkinsServer;
import com.jenkinsmonitoring.server.ServerCatalog;
import com.jenkinsmonitoring.server.ServerUrls;
import com.jenkinsmonitoring.storage.Conditions;
import com.jenkinsmonitoring.storage.CursorRepository;
import com.jenkinsmonitoring.storage.Rule;
import com.jenkinsmonitoring.storage.RuleRepository;
import com.jenkinsmonitoring.storage.StorageException;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The rules, held in memory and saved to {@code rules.json}. The file is read at startup; a hand edit made
 * while the application runs is picked up before the next change, so it is not overwritten.
 */
@Service
public class RuleService {

    private static final Logger log = LoggerFactory.getLogger(RuleService.class);

    static final String MISSING_FIELDS = "addRule requires both `server` and `job`. Rules by user alone are not "
            + "supported. Example: addRule(server=\"https://my-jenkins-server.com\", job=\"iot-platform-deploy-dev\", "
            + "conditions={\"user\":\"artem.holovchenko\"})";

    private final RuleRepository repository;
    private final ServerCatalog servers;
    private final JenkinsGateway gateway;
    private final CursorRepository cursors;
    private final Clock clock;

    private final Object lock = new Object();
    private volatile List<Rule> rules = List.of();
    private volatile List<String> problems = List.of();
    // Entries of rules.json that are not in use; they are written back unchanged whenever the file is saved
    private List<Rule> unusable = List.of();
    private List<JsonNode> unreadable = List.of();
    // Set when rules.json cannot be read at all; changing rules would then overwrite the user's file
    private boolean fileUnreadable;
    private long loadedModified;

    public RuleService(RuleRepository repository, ServerCatalog servers, JenkinsGateway gateway,
                       CursorRepository cursors, Clock clock) {
        this.repository = repository;
        this.servers = servers;
        this.gateway = gateway;
        this.cursors = cursors;
        this.clock = clock;
    }

    @PostConstruct
    public void load() {
        synchronized (lock) {
            reload();
        }
    }

    public List<Rule> list() {
        return rules;
    }

    /** Why some rules of the file are not in use; shown by the status check. */
    public List<String> problems() {
        return problems;
    }

    /**
     * Runs the action only if the rule is still in place, and holds the rule lock meanwhile. The collector
     * writes through this, so a rule that was removed during a pass cannot get its cursor or records back.
     */
    public void runIfWatched(Rule rule, Runnable action) {
        synchronized (lock) {
            if (find(rule.server(), rule.job()).filter(current -> current.id() == rule.id()).isPresent()) {
                action.run();
            }
        }
    }

    public Rule add(String serverInput, String jobInput, Map<String, Object> rawConditions) {
        if (isBlank(serverInput) || isBlank(jobInput)) {
            throw new RuleException(MISSING_FIELDS);
        }
        JenkinsServer server = servers.resolve(serverInput).orElseThrow(() -> unknownServer(serverInput));
        String job = jobInput.trim();
        verifyOnJenkins(server, job);
        Conditions conditions = ConditionsValidator.validate(rawConditions);

        synchronized (lock) {
            reloadIfChanged();
            requireReadableFile();
            find(server.canonical(), job).ifPresent(existing -> {
                throw new RuleException("A rule already exists for server " + server.canonical() + " and job '" + job
                        + "' (rule " + existing.id() + ", conditions: " + existing.conditionsOrNone()
                        + "). Remove it with removeRule first if you want to change it.");
            });
            List<Rule> knownIds = new ArrayList<>(rules);
            knownIds.addAll(unusable); // an id that an unused entry holds must not be handed out again
            Rule rule = new Rule(repository.nextId(knownIds), LocalDateTime.now(clock), server.canonical(), job,
                    conditions.isEmpty() ? null : conditions);
            List<Rule> updated = new ArrayList<>(rules);
            updated.add(rule);
            save(updated);
            return rule;
        }
    }

    /** Removes the rule and the collector's position for that job. Needs no connection to Jenkins. */
    public Rule remove(String serverInput, String jobInput) {
        if (isBlank(serverInput) || isBlank(jobInput)) {
            throw new RuleException("removeRule requires both `server` and `job`.");
        }
        String server = servers.resolveConfigured(serverInput)
                .or(() -> ServerUrls.canonicalOf(serverInput))
                .orElseThrow(() -> new RuleException("'" + serverInput + "' is not a valid server URL or host name."));
        String job = jobInput.trim();
        synchronized (lock) {
            reloadIfChanged();
            requireReadableFile();
            Rule rule = find(server, job).orElseThrow(() ->
                    new RuleException("Rule not found for server " + server + " and job '" + job + "'."));
            List<Rule> updated = new ArrayList<>(rules);
            updated.remove(rule);
            save(updated);
            cursors.delete(server, job);
            return rule;
        }
    }

    private void verifyOnJenkins(JenkinsServer server, String job) {
        try {
            gateway.whoAmI(server);
            gateway.getJob(server, job, "fullName");
        } catch (JenkinsException.NotFound e) {
            throw new RuleException("Job '" + job + "' was not found on " + server.canonical()
                    + ", or it is not accessible with the configured credentials.");
        } catch (JenkinsException.Unavailable e) {
            throw new RuleException((e.isAuthFailure()
                    ? server.canonical() + " rejected the configured credentials."
                    : e.getMessage()) + " The rule was not saved.");
        } catch (JenkinsException e) {
            throw new RuleException("The job cannot be checked on " + server.canonical() + ": " + e.getMessage()
                    + " The rule was not saved.");
        }
    }

    private RuleException unknownServer(String input) {
        List<String> configured = servers.servers().stream().map(JenkinsServer::canonical).toList();
        return new RuleException(configured.isEmpty()
                ? "No Jenkins server is configured, so '" + input + "' cannot be used."
                : "'" + input + "' does not match any configured Jenkins server. Configured servers: " + configured);
    }

    private Optional<Rule> find(String server, String job) {
        return rules.stream().filter(rule -> rule.watches(server, job)).findFirst();
    }

    private void requireReadableFile() {
        if (fileUnreadable) {
            throw new RuleException("The rules file cannot be read (" + String.join(" ", problems)
                    + "), so no rule can be changed until it is fixed.");
        }
    }

    private void save(List<Rule> updated) {
        repository.saveAll(updated, unusable, unreadable);
        rules = List.copyOf(updated);
        loadedModified = repository.lastModified();
    }

    private void reloadIfChanged() {
        if (fileUnreadable || repository.lastModified() != loadedModified) {
            reload();
        }
    }

    /** Reads the file. Invalid entries are skipped with a warning and reported by the status check. */
    private void reload() {
        List<Rule> loaded = new ArrayList<>();
        List<Rule> notUsed = new ArrayList<>();
        List<String> found = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        try {
            RuleRepository.Loaded read = repository.load();
            found.addAll(read.problems());
            for (Rule rule : read.rules()) {
                try {
                    Rule valid = validated(rule);
                    if (seen.add(valid.server() + "|" + valid.job())) {
                        loaded.add(valid);
                    } else {
                        notUsed.add(rule);
                        found.add("Rule " + rule.id() + " skipped: another rule already watches job '" + rule.job() + "'.");
                    }
                } catch (RuleException e) {
                    notUsed.add(rule);
                    found.add("Rule " + rule.id() + " skipped: " + e.getMessage());
                }
            }
            loadedModified = repository.lastModified();
            rules = List.copyOf(loaded);
            unusable = List.copyOf(notUsed);
            unreadable = List.copyOf(read.unreadable());
            fileUnreadable = false;
        } catch (StorageException e) {
            fileUnreadable = true;
            found.add(e.getMessage());
        }
        found.forEach(problem -> log.warn("{}", problem));
        problems = List.copyOf(found);
    }

    /** A rule read from the file with its server in canonical form, or an exception saying what is wrong. */
    private Rule validated(Rule rule) {
        if (rule.id() <= 0) {
            throw new RuleException("it needs a positive id.");
        }
        if (isBlank(rule.server()) || isBlank(rule.job())) {
            throw new RuleException("it needs both server and job.");
        }
        String server = servers.resolveConfigured(rule.server()).orElseThrow(() ->
                new RuleException("server '" + rule.server() + "' matches no configured Jenkins server."));
        Conditions conditions = ConditionsValidator.validate(rule.conditionsOrNone().asMap());
        return new Rule(rule.id(), rule.createdAt(), server, rule.job().trim(),
                conditions.isEmpty() ? null : conditions);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
