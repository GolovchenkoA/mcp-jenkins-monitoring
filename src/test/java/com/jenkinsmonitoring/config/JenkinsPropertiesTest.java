package com.jenkinsmonitoring.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** How the Jenkins settings bind, in particular which values have defaults. */
class JenkinsPropertiesTest {

    private static JenkinsProperties bind(Map<String, Object> variables) {
        StandardEnvironment environment = new StandardEnvironment();
        // named like Spring Boot's real one, so environment variable names are mapped to property names
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource("test-systemEnvironment", variables));
        return Binder.get(environment).bind("jenkins", JenkinsProperties.class).get();
    }

    @Test
    void theProtocolDefaultsToStreamableWhenItIsNotSet() {
        JenkinsProperties properties = bind(Map.of(
                "JENKINS_SERVER_0_URL", "https://jenkins-server1.com/mcp-server/mcp",
                "JENKINS_SERVER_0_AUTH", "SERVER1_AUTH"));

        assertThat(properties.servers().get(0).protocol()).isEqualTo("STREAMABLE");
    }

    @Test
    void aProtocolThatIsSetIsKept() {
        JenkinsProperties properties = bind(Map.of(
                "JENKINS_SERVER_0_URL", "https://jenkins-server1.com/mcp-server/mcp",
                "JENKINS_SERVER_0_PROTOCOL", "sse"));

        assertThat(properties.servers().get(0).protocol()).isEqualTo("sse");
    }

    @Test
    void theOldPluralVariableNameIsNotBound() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource("test-systemEnvironment", Map.of(
                "JENKINS_SERVERS_0_URL", "https://jenkins-server1.com/mcp-server/mcp",
                "JENKINS_SERVERS_0_PROTOCOL", "STREAMABLE")));

        // nothing at all is bound: the old name is ignored, so no server ends up configured
        assertThat(Binder.get(environment).bind("jenkins", JenkinsProperties.class).isBound()).isFalse();
    }

    @Test
    void anEmptyProtocolIsNotReplacedByTheDefault() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("file", Map.of(
                "jenkins.server[0].url", "https://jenkins-server1.com/mcp-server/mcp",
                "jenkins.server[0].protocol", "")));

        JenkinsProperties properties = Binder.get(environment).bind("jenkins", JenkinsProperties.class).get();

        // documents today's behaviour: an empty value counts as a value, so the server is later reported as not usable
        assertThat(properties.servers().get(0).protocol()).isEmpty();
    }
}
