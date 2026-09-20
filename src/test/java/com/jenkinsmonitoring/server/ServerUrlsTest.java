package com.jenkinsmonitoring.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class ServerUrlsTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "https://jenkins-server1.com",
            "jenkins-server1.com",
            "https://jenkins-server1.com/",
            "  JENKINS-Server1.com  ",
            "https://jenkins-server1.com:443",
            "https://jenkins-server1.com/mcp-server/mcp"})
    void everyFormOfTheSameServerHasTheSameCanonicalUrl(String input) {
        assertThat(ServerUrls.canonicalOf(input)).contains("https://jenkins-server1.com");
    }

    @Test
    void keepsSchemeAndNonDefaultPort() {
        assertThat(ServerUrls.canonicalOf("http://jenkins.local:8080/mcp")).contains("http://jenkins.local:8080");
        assertThat(ServerUrls.canonicalOf("http://jenkins.local:80")).contains("http://jenkins.local");
    }

    @Test
    void matchingIgnoresSchemeAndPath() {
        assertThat(ServerUrls.hostKey("http://jenkins-server1.com/x")).isEqualTo(ServerUrls.hostKey("jenkins-server1.com"));
        assertThat(ServerUrls.hostKey("jenkins.local:8080")).contains("jenkins.local:8080");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "ftp://host", "not a url"})
    void rejectsInputThatIsNotAServer(String input) {
        assertThat(ServerUrls.canonicalOf(input)).isEmpty();
    }

    @Test
    void endpointIsThePathOfTheConfiguredUrl() {
        assertThat(ServerUrls.endpointOf("https://jenkins-server1.com/mcp-server/mcp")).contains("/mcp-server/mcp");
        assertThat(ServerUrls.endpointOf("https://jenkins-server1.com")).contains("/mcp");
    }
}
