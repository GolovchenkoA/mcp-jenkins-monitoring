package com.jenkinsmonitoring.jenkins;

import com.jenkinsmonitoring.common.Threads;
import com.jenkinsmonitoring.server.JenkinsServer;
import com.jenkinsmonitoring.server.ServerCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Connects to every server after startup and learns its tools. It runs in the background: with the VPN off
 * each connection has to time out, and that must not delay the startup of this server.
 */
@Component
class ToolDiscoveryRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ToolDiscoveryRunner.class);

    private final ServerCatalog servers;
    private final JenkinsGateway gateway;

    ToolDiscoveryRunner(ServerCatalog servers, JenkinsGateway gateway) {
        this.servers = servers;
        this.gateway = gateway;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (JenkinsServer server : servers.servers()) {
            Threads.startDaemon("jenkins-connect", () -> {
                try {
                    gateway.connect(server);
                    log.info("Connected to Jenkins MCP server {}", server.canonical());
                } catch (JenkinsException e) {
                    log.warn("{}", e.getMessage());
                }
            });
        }
    }
}
