package com.jenkinsmonitoring.server;

import com.jenkinsmonitoring.config.JenkinsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The configured Jenkins servers, validated once at startup. A server with a configuration problem is not
 * usable; it is listed in {@link #problems()} and the application still starts.
 */
@Component
public class ServerCatalog {

    private static final Logger log = LoggerFactory.getLogger(ServerCatalog.class);
    private static final Pattern ENV_VAR_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern BASIC_HEADER = Pattern.compile("^Basic\\s+(\\S+)$");

    private final List<JenkinsServer> usable = new ArrayList<>();
    private final List<ServerProblem> problems = new ArrayList<>();
    private final Map<String, String> configuredByKey = new HashMap<>();

    /** Credentials are read from the real environment variables, never from configuration files. */
    @Autowired
    public ServerCatalog(JenkinsProperties properties) {
        this(properties, System::getenv);
    }

    /** {@code environment} looks up an environment variable by name; tests pass their own. */
    public ServerCatalog(JenkinsProperties properties, Function<String, String> environment) {
        Set<String> seen = new HashSet<>();
        List<JenkinsProperties.Server> servers = properties.servers();
        for (int i = 0; i < servers.size(); i++) {
            validate(i, servers.get(i), environment, seen);
        }
        problems.forEach(p -> log.error("Jenkins server {} is not usable: {}", p.server(), p.message()));
        log.info("Configured Jenkins servers: {} usable, {} with problems", usable.size(), problems.size());
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

    private void validate(int index, JenkinsProperties.Server config, Function<String, String> environment, Set<String> seen) {
        String label = "jenkins.servers[" + index + "]";
        Optional<String> canonical = ServerUrls.canonicalOf(config.url());
        if (canonical.isEmpty() || config.url() == null || !config.url().contains("://")) {
            problems.add(new ServerProblem(label, "url must be a full http(s) URL such as https://host/mcp-server/mcp"));
            return;
        }
        String server = canonical.get();
        ServerUrls.hostKey(server).ifPresent(key -> configuredByKey.putIfAbsent(key, server));

        if (!"STREAMABLE".equalsIgnoreCase(config.protocol())) {
            problems.add(new ServerProblem(server, "protocol must be STREAMABLE, found '" + config.protocol() + "'"));
            return;
        }
        if (!seen.add(server)) {
            problems.add(new ServerProblem(server, "another configured server has the same canonical URL"));
            return;
        }
        String authEnvVar = config.auth() == null ? "" : config.auth().trim();
        if (authEnvVar.isEmpty()) {
            problems.add(new ServerProblem(server, "auth is not set; it must name an environment variable"));
            return;
        }
        if (!ENV_VAR_NAME.matcher(authEnvVar).matches()) {
            // Never echo the value: it may be a credential someone pasted into the file by mistake
            problems.add(new ServerProblem(server,
                    "auth must be the NAME of an environment variable, not the credentials themselves"));
            return;
        }
        String header = environment.apply(authEnvVar);
        if (header == null || header.isBlank()) {
            problems.add(new ServerProblem(server, "environment variable " + authEnvVar + " is not set"));
            return;
        }
        if (!isBasicHeader(header.trim())) {
            problems.add(new ServerProblem(server,
                    "environment variable " + authEnvVar + " must have the form 'Basic <base64>'"));
            return;
        }
        String endpoint = ServerUrls.endpointOf(config.url()).orElse("/mcp");
        usable.add(new JenkinsServer(server, endpoint, authEnvVar, new Secret(header.trim())));
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
