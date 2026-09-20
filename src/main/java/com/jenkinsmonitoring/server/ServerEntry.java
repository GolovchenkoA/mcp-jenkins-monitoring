package com.jenkinsmonitoring.server;

/**
 * One {@code jenkins.server[n]} block as it was configured, and whether it could be used.
 *
 * @param url        as configured, so it can be shown next to the reason a server is not used
 * @param authEnvVar the name of the environment variable that holds the credentials (for example
 *                   JENKINS_SERVER_0_AUTH); never the credentials. Only set for a usable server, because
 *                   for a rejected one the value may be a credential pasted by mistake
 * @param problem    why the server is not used, or null when it is
 */
public record ServerEntry(int index, String url, String protocol, String authEnvVar, String problem) {

    public boolean usable() {
        return problem == null;
    }
}
