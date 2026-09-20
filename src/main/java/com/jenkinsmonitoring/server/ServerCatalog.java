package com.jenkinsmonitoring.server;

import com.jenkinsmonitoring.config.JenkinsProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The configured Jenkins servers, validated once at startup. A server with a configuration problem is not
 * usable; it is listed in {@link #problems()} and the application still starts.
 */
@Component
public class ServerCatalog {

    private static final Pattern ENV_VAR_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern BASIC_HEADER = Pattern.compile("^Basic\\s+(\\S+)$");

    private final List<JenkinsServer> usable = new ArrayList<>();
    private final List<ServerProblem> problems = new ArrayList<>();
    private final List<ServerEntry> entries = new ArrayList<>();
    private final Map<String, String> configuredByKey = new HashMap<>();

    /** Credentials are read from the real environment variables, never from configuration files. */
    @Autowired
    public ServerCatalog(JenkinsProperties properties) {
        this(properties, System::getenv);
    }

    /** {@code environment} looks up an environment variable by name; tests pass their own. */
    public ServerCatalog(JenkinsProperties properties, Function<String, String> environment) {
        Set<String> seen = new HashSet<>();
        // In the order of the numbers in jenkins.server[n]; the numbers only have to be unique
        new TreeMap<>(properties.servers()).forEach((index, config) -> {
            Optional<ServerProblem> problem = validate(index, config, environment, seen);
            problem.ifPresent(problems::add);
            entries.add(new ServerEntry(index, config.url(), config.protocol(),
                    problem.isEmpty() ? config.auth().trim() : null, problem.map(ServerProblem::message).orElse(null)));
        });
    }

    /** Every configured block, usable or not, in configuration order. The startup report is built from it. */
    public List<ServerEntry> entries() {
        return List.copyOf(entries);
    }

    public List<JenkinsServer> servers() {
        return List.copyOf(usable);
    }

    public List<ServerProblem> problems() {
        return List.copyOf(problems);
    }

    public int configuredCount() {
        return usable.size() + problems.size();
    }

    /** The usable server a user's input refers to, matched by host and port. */
    public Optional<JenkinsServer> resolve(String input) {
        Optional<String> key = ServerUrls.hostKey(input);
        if (key.isEmpty()) {
            return Optional.empty();
        }
        return usable.stream()
                .filter(server -> ServerUrls.hostKey(server.canonical()).equals(key))
                .findFirst();
    }

    public Optional<JenkinsServer> byCanonical(String canonical) {
        return usable.stream().filter(server -> server.canonical().equals(canonical)).findFirst();
    }

    /**
     * The canonical URL for an input that refers to a configured server, usable or not. Removing a rule must
     * not depend on the server working, so a server with a broken credential still resolves here.
     */
    public Optional<String> resolveConfigured(String input) {
        return ServerUrls.hostKey(input).map(configuredByKey::get);
    }

    /** Adds the server to the usable ones, or says what is wrong with it. */
    private Optional<ServerProblem> validate(int index, JenkinsProperties.Server config,
                                             Function<String, String> environment, Set<String> seen) {
        Optional<String> canonical = ServerUrls.canonicalOf(config.url());
        if (canonical.isEmpty() || config.url() == null || !config.url().contains("://")) {
            return problem("jenkins.server[" + index + "]", "url must be a full http(s) URL such as https://host/mcp-server/mcp");
        }
        String server = canonical.get();
        ServerUrls.hostKey(server).ifPresent(key -> configuredByKey.putIfAbsent(key, server));

        if (!"STREAMABLE".equalsIgnoreCase(config.protocol())) {
            return problem(server, "protocol must be STREAMABLE, found '" + config.protocol() + "'");
        }
        if (!seen.add(server)) {
            return problem(server, "another configured server has the same canonical URL");
        }
        String authEnvVar = config.auth() == null ? "" : config.auth().trim();
        if (authEnvVar.isEmpty()) {
            return problem(server, "auth is not set; it must name an environment variable");
        }
        if (!ENV_VAR_NAME.matcher(authEnvVar).matches()) {
            // Never echo the value: it may be a credential someone pasted into the file by mistake
            return problem(server, "auth must be the NAME of an environment variable, not the credentials themselves");
        }
        String header = environment.apply(authEnvVar);
        if (header == null || header.isBlank()) {
            return problem(server, "environment variable " + authEnvVar + " is not set");
        }
        if (!isBasicHeader(header.trim())) {
            return problem(server, "environment variable " + authEnvVar + " must have the form 'Basic <base64>'");
        }
        String endpoint = ServerUrls.endpointOf(config.url()).orElse("/mcp");
        usable.add(new JenkinsServer(server, endpoint, authEnvVar, new Secret(header.trim())));
        return Optional.empty();
    }

    private static Optional<ServerProblem> problem(String server, String message) {
        return Optional.of(new ServerProblem(server, message));
    }

    private static boolean isBasicHeader(String header) {
        Matcher matcher = BASIC_HEADER.matcher(header);
        if (!matcher.matches()) {
            return false;
        }
        try {
            Base64.getDecoder().decode(matcher.group(1));
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
