package com.jenkinsmonitoring.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.source.ConfigurationProperty;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.IterableConfigurationPropertySource;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * Writes to the log, once at startup, which Jenkins servers were found in the configuration and where each
 * came from: a line of an application.properties, or an environment variable such as
 * {@code JENKINS_SERVER_0_URL}. Spring Boot records the origin of every property, so nothing is guessed.
 * Only names and URLs are logged, never credentials. It runs before anything connects to a server.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class ServerConfigLogger implements ApplicationRunner {

    static final String NONE_CONFIGURED = "No Jenkins server is configured. Add jenkins.server[0].url, "
            + "jenkins.server[0].protocol and jenkins.server[0].auth to an application.properties next to the jar, "
            + "or set the environment variables JENKINS_SERVER_0_URL, JENKINS_SERVER_0_PROTOCOL and "
            + "JENKINS_SERVER_0_AUTH. The value of ..._AUTH is the name of another environment variable that "
            + "holds 'Basic <base64 of user:apiToken>'.";

    private static final Logger log = LoggerFactory.getLogger(ServerConfigLogger.class);

    private final ServerCatalog servers;
    private final Environment environment;

    ServerConfigLogger(ServerCatalog servers, Environment environment) {
        this.servers = servers;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        logServers();
    }

    void logServers() {
        List<ServerEntry> entries = servers.entries();
        if (entries.isEmpty()) {
            log.warn(NONE_CONFIGURED);
        } else {
            report(entries);
        }
        warnAboutTheOldName();
    }

    private void report(List<ServerEntry> entries) {
        long usable = entries.stream().filter(ServerEntry::usable).count();
        log.info("Jenkins servers in the configuration: {} ({} in use, {} not usable)",
                entries.size(), usable, entries.size() - usable);
        for (ServerEntry entry : entries) {
            String name = "jenkins.server[" + entry.index() + "]";
            String urlSource = originOf(name + ".url");
            // A URL that cannot be understood may contain a pasted credential, so it is never printed; the origin
            // below says exactly where to look
            String canonical = ServerUrls.canonicalOf(entry.url()).orElse("<invalid url>");
            if (entry.usable()) {
                log.info("  {} = {} (MCP endpoint {}, protocol {}), url from {}, credentials from environment variable {}",
                        name, canonical, ServerUrls.endpointOf(entry.url()).orElse("/mcp"),
                        String.valueOf(entry.protocol()).toUpperCase(Locale.ROOT), urlSource, entry.authEnvVar());
            } else {
                log.error("  {} = {} is NOT USED: {} (url from {})", name, canonical, entry.problem(), urlSource);
            }
        }
    }

    /**
     * The setting used to be called {@code jenkins.servers[n]} (plural). Spring ignores unknown names without a
     * word, so someone with the old name would only see "no server configured". Say what to change instead.
     */
    private void warnAboutTheOldName() {
        ConfigurationPropertyName oldName = ConfigurationPropertyName.of("jenkins.servers");
        try {
            for (ConfigurationPropertySource source : ConfigurationPropertySources.get(environment)) {
                if (source instanceof IterableConfigurationPropertySource iterable) {
                    for (ConfigurationPropertyName name : iterable) {
                        if (oldName.isAncestorOf(name)) {
                            log.warn("Found {} (from {}), but the setting was renamed: use jenkins.server[n] (singular), "
                                    + "or the environment variables JENKINS_SERVER_0_URL and so on. The old name is ignored.",
                                    name, originOf(name.toString()));
                            return;
                        }
                    }
                }
            }
        } catch (RuntimeException e) {
            log.debug("Could not check for the old jenkins.servers name: {}", e.getMessage());
        }
    }

    /** Where a property's value was read from: a file with line and column, or an environment variable. */
    private String originOf(String property) {
        try {
            ConfigurationPropertyName name = ConfigurationPropertyName.of(property);
            for (ConfigurationPropertySource source : ConfigurationPropertySources.get(environment)) {
                ConfigurationProperty found = source.getConfigurationProperty(name);
                if (found != null) {
                    return found.getOrigin() != null ? found.getOrigin().toString() : "the configuration";
                }
            }
        } catch (RuntimeException e) {
            // A report must never stop the application from starting
            log.debug("Could not find where {} comes from: {}", property, e.getMessage());
        }
        return "an unknown place";
    }
}
