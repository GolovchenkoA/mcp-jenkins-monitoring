package com.jenkinsmonitoring.server;

import com.jenkinsmonitoring.config.JenkinsProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.source.ConfigurationProperty;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.origin.Origin;
import org.springframework.boot.origin.PropertySourceOrigin;
import org.springframework.boot.origin.SystemEnvironmentOrigin;
import org.springframework.core.env.Environment;
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
import java.util.function.IntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The configured Jenkins servers, validated once at startup. A server with a configuration problem is not
 * usable; it is listed in {@link #problems()} and the application still starts.
 *
 * <p>The {@code auth} setting of a server is one of two things:
 * <ul>
 *   <li>the credentials themselves, {@code Basic <base64 of user:apiToken>}, but only when the value comes
 *       from an environment variable such as {@code JENKINS_SERVER_0_AUTH}; in a configuration file it would
 *       be a secret at rest, so it is refused there;</li>
 *   <li>the name of another environment variable that holds those credentials; this works from either place.</li>
 * </ul>
 */
@Component
public class ServerCatalog {

    private static final Pattern ENV_VAR_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern BASIC_HEADER = Pattern.compile("^Basic\\s+(\\S+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern STARTS_LIKE_BASIC = Pattern.compile("^Basic\\s.*", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private final List<JenkinsServer> usable = new ArrayList<>();
    private final List<ServerProblem> problems = new ArrayList<>();
    private final List<ServerEntry> entries = new ArrayList<>();
    private final Map<String, String> configuredByKey = new HashMap<>();

    /** Credentials come from the real environment variables, never from configuration files. */
    @Autowired
    public ServerCatalog(JenkinsProperties properties, Environment environment) {
        this(properties, System::getenv, index -> environmentVariableOf(environment, "jenkins.server[" + index + "].auth"));
    }

    /**
     * For tests that only use the name form of {@code auth}: nothing counts as coming from an environment
     * variable, so credentials written directly in {@code auth} are refused.
     */
    public ServerCatalog(JenkinsProperties properties, Function<String, String> environment) {
        this(properties, environment, index -> Optional.empty());
    }

    /**
     * @param environment          looks up an environment variable by name
     * @param authEnvironmentName  for the server with that number: the name of the environment variable that the
     *                             {@code auth} value was read from, or empty if it came from anywhere else
     */
    public ServerCatalog(JenkinsProperties properties, Function<String, String> environment,
                         IntFunction<Optional<String>> authEnvironmentName) {
        Set<String> seen = new HashSet<>();
        // In the order of the numbers in jenkins.server[n]; the numbers only have to be unique
        new TreeMap<>(properties.servers()).forEach((index, config) -> {
            Optional<ServerProblem> problem = validate(index, config, environment, authEnvironmentName, seen);
            problem.ifPresent(problems::add);
            // The name shown is the variable's name, taken from the server that was just added: never the value
            String credentialsVariable = problem.isEmpty() ? usable.get(usable.size() - 1).authEnvVar() : null;
            entries.add(new ServerEntry(index, config.url(), config.protocol(), credentialsVariable,
                    problem.map(ServerProblem::message).orElse(null)));
        });
    }

    /** The environment variable an {@code auth} value was read from, if it was read from one. */
    private static Optional<String> environmentVariableOf(Environment environment, String property) {
        ConfigurationPropertyName name = ConfigurationPropertyName.of(property);
        for (ConfigurationPropertySource source : ConfigurationPropertySources.get(environment)) {
            ConfigurationProperty found = source.getConfigurationProperty(name);
            if (found != null) {
                // The first source that has it is the one whose value is used. Spring wraps the origin that a
                // property source reports in one that names the source, so look at what is inside.
                Origin origin = found.getOrigin();
                if (origin instanceof PropertySourceOrigin wrapper) {
                    origin = wrapper.getOrigin();
                }
                if (origin instanceof SystemEnvironmentOrigin variable) {
                    return Optional.of(variable.getProperty());
                }
                return Optional.empty();
            }
        }
        return Optional.empty();
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
                                             Function<String, String> environment,
                                             IntFunction<Optional<String>> authEnvironmentName, Set<String> seen) {
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
        String auth = config.auth() == null ? "" : config.auth().trim();
        if (auth.isEmpty()) {
            return problem(server, "auth is not set. Set JENKINS_SERVER_" + index + "_AUTH to 'Basic <base64 of user:apiToken>'");
        }

        String variable;
        String header;
        if (STARTS_LIKE_BASIC.matcher(auth).matches()) {
            // The credentials themselves. Never echo them, in any message below.
            Optional<String> fromVariable = authEnvironmentName.apply(index);
            if (fromVariable.isEmpty()) {
                return problem(server, "the credentials must not be written in a configuration file. Put them in the "
                        + "environment variable JENKINS_SERVER_" + index + "_AUTH, or put the NAME of such a variable in auth");
            }
            variable = fromVariable.get();
            header = auth;
        } else if (ENV_VAR_NAME.matcher(auth).matches()) {
            variable = auth;
            header = environment.apply(auth);
            if (header == null || header.isBlank()) {
                // A value read from an environment variable may be a credential pasted by mistake: do not echo it
                String named = authEnvironmentName.apply(index).isPresent() ? "the environment variable named in JENKINS_SERVER_" + index + "_AUTH" : "environment variable " + auth;
                return problem(server, named + " is not set");
            }
        } else {
            return problem(server, "auth must be 'Basic <base64 of user:apiToken>' given in an environment variable, "
                    + "or the NAME of an environment variable that holds it");
        }
        if (!isBasicHeader(header.trim())) {
            return problem(server, "the credentials in environment variable " + variable
                    + " must have the form 'Basic <base64 of user:apiToken>'");
        }
        String endpoint = ServerUrls.endpointOf(config.url()).orElse("/mcp");
        usable.add(new JenkinsServer(server, endpoint, variable, new Secret(header.trim())));
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
