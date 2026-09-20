package com.jenkinsmonitoring.server;

import com.jenkinsmonitoring.config.JenkinsProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.support.SystemEnvironmentPropertySourceEnvironmentPostProcessor;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code auth} setting: credentials given directly ({@code Basic <base64>}) are accepted only from an
 * environment variable such as JENKINS_SERVER_0_AUTH; the name of another variable works from anywhere.
 */
class ServerCatalogAuthTest {

    private static final String URL = "https://jenkins-server1.com/mcp-server/mcp";
    private static final String CREDENTIALS = "Basic dXNlcjp0b2tlbg==";

    private static JenkinsProperties.Server server(String auth) {
        return new JenkinsProperties.Server(URL, "STREAMABLE", auth);
    }

    private static JenkinsProperties one(String auth) {
        return new JenkinsProperties(Map.of(0, server(auth)), java.util.List.of(), "", java.time.Duration.ofSeconds(1));
    }

    /** A catalog in which the auth value of server 0 was read from the environment variable JENKINS_SERVER_0_AUTH. */
    private static ServerCatalog fromEnvironmentVariable(String value) {
        return new ServerCatalog(one(value), name -> null, index -> Optional.of("JENKINS_SERVER_" + index + "_AUTH"));
    }

    @Test
    void credentialsGivenDirectlyInAnEnvironmentVariableAreAccepted() {
        ServerCatalog catalog = fromEnvironmentVariable(CREDENTIALS);

        assertThat(catalog.problems()).isEmpty();
        assertThat(catalog.servers().get(0).authHeader().value()).isEqualTo(CREDENTIALS);
        assertThat(catalog.servers().get(0).authEnvVar()).isEqualTo("JENKINS_SERVER_0_AUTH");
    }

    @Test
    void extraSpacesAndACaseDifferenceInTheSchemeDoNotMatter() {
        assertThat(fromEnvironmentVariable("Basic   dXNlcjp0b2tlbg==  ").servers()).hasSize(1);
        assertThat(fromEnvironmentVariable("basic dXNlcjp0b2tlbg==").servers()).hasSize(1);
    }

    @Test
    void theEntryNamesTheVariableAndNeverTheCredentials() {
        ServerEntry entry = fromEnvironmentVariable(CREDENTIALS).entries().get(0);

        assertThat(entry.usable()).isTrue();
        assertThat(entry.authEnvVar()).isEqualTo("JENKINS_SERVER_0_AUTH");
        assertThat(entry.toString()).doesNotContain("dXNlcjp0b2tlbg==");
    }

    @Test
    void credentialsGivenDirectlyInAFileAreRefusedAndNeverEchoed() {
        ServerCatalog catalog = new ServerCatalog(one(CREDENTIALS), name -> null);

        assertThat(catalog.servers()).isEmpty();
        assertThat(catalog.problems().get(0).message())
                .contains("must not be written in a configuration file").contains("JENKINS_SERVER_0_AUTH")
                .doesNotContain("dXNlcjp0b2tlbg==");
        assertThat(catalog.entries().get(0).authEnvVar()).isNull();
    }

    @Test
    void theNameOfAnotherVariableStillWorks() {
        ServerCatalog catalog = new ServerCatalog(one("JENKINS0_CREDENTIALS"), Map.of("JENKINS0_CREDENTIALS", CREDENTIALS)::get);

        assertThat(catalog.problems()).isEmpty();
        assertThat(catalog.servers().get(0).authEnvVar()).isEqualTo("JENKINS0_CREDENTIALS");
    }

    @Test
    void aNameWhoseVariableIsMissingIsReported() {
        ServerCatalog catalog = new ServerCatalog(one("JENKINS0_CREDENTIALS"), name -> null);

        assertThat(catalog.problems().get(0).message()).contains("JENKINS0_CREDENTIALS is not set");
    }

    @Test
    void credentialsInTheWrongFormAreReportedWithTheVariableNameButNotTheirValue() {
        ServerCatalog catalog = fromEnvironmentVariable("Basic not!base64");

        assertThat(catalog.servers()).isEmpty();
        assertThat(catalog.problems().get(0).message()).contains("JENKINS_SERVER_0_AUTH")
                .contains("Basic <base64 of user:apiToken>").doesNotContain("not!base64");
    }

    @Test
    void somethingThatIsNeitherCredentialsNorAVariableNameExplainsBothForms() {
        ServerCatalog catalog = fromEnvironmentVariable("Bearer abc.def");

        assertThat(catalog.servers()).isEmpty();
        assertThat(catalog.problems().get(0).message()).contains("Basic <base64 of user:apiToken>").contains("NAME")
                .doesNotContain("abc.def");
    }

    @Test
    void aBareValueFromAnEnvironmentVariableIsNeverEchoedWhenItNamesNothing() {
        ServerCatalog catalog = fromEnvironmentVariable("pastedSecretToken");

        assertThat(catalog.problems().get(0).message()).contains("JENKINS_SERVER_0_AUTH").contains("is not set")
                .doesNotContain("pastedSecretToken");
    }

    @Test
    void theAuthValueDoesNotShowInToString() {
        assertThat(server(CREDENTIALS).toString()).doesNotContain("dXNlcjp0b2tlbg==");
    }

    @Test
    void anEmptyAuthTellsWhichVariableToSet() {
        ServerCatalog catalog = new ServerCatalog(one(""), name -> null);

        assertThat(catalog.problems().get(0).message()).contains("JENKINS_SERVER_0_AUTH");
    }

    // --- the real detection of where the value came from, through Spring's property origins ---

    private static JenkinsProperties bound(StandardEnvironment environment) {
        return Binder.get(environment).bind("jenkins", JenkinsProperties.class).get();
    }

    /**
     * An environment whose operating-system variables are the given ones. Spring Boot wraps that source at
     * startup so it can say which variable a value came from; the post processor does the same here.
     */
    private static StandardEnvironment withVariables(Map<String, Object> variables) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, variables));
        new SystemEnvironmentPropertySourceEnvironmentPostProcessor().postProcessEnvironment(environment, new SpringApplication());
        return environment;
    }

    @Test
    void aRealEnvironmentVariableIsRecognisedAsTheSource() {
        StandardEnvironment environment = withVariables(Map.of(
                "JENKINS_SERVER_0_URL", URL, "JENKINS_SERVER_0_AUTH", CREDENTIALS));

        ServerCatalog catalog = new ServerCatalog(bound(environment), environment);

        assertThat(catalog.problems()).isEmpty();
        assertThat(catalog.servers().get(0).authEnvVar()).isEqualTo("JENKINS_SERVER_0_AUTH");
        assertThat(catalog.servers().get(0).authHeader().value()).isEqualTo(CREDENTIALS);
    }

    @Test
    void theSameCredentialsInAFileAreNotRecognisedAsAnEnvironmentVariable() {
        StandardEnvironment environment = withVariables(Map.of());
        environment.getPropertySources().addLast(new MapPropertySource("file", Map.of(
                "jenkins.server[0].url", URL, "jenkins.server[0].auth", CREDENTIALS)));

        ServerCatalog catalog = new ServerCatalog(bound(environment), environment);

        assertThat(catalog.servers()).isEmpty();
        assertThat(catalog.problems().get(0).message()).contains("must not be written in a configuration file");
    }

    @Test
    void anEnvironmentVariableWinsOverAFileForTheSameServer() {
        StandardEnvironment environment = withVariables(Map.of("JENKINS_SERVER_0_AUTH", CREDENTIALS));
        // the file comes after the environment, as an application.properties does
        environment.getPropertySources().addLast(new MapPropertySource("file", Map.of(
                "jenkins.server[0].url", URL, "jenkins.server[0].auth", "NOT_USED_ANY_MORE")));

        ServerCatalog catalog = new ServerCatalog(bound(environment), environment);

        assertThat(catalog.problems()).isEmpty();
        assertThat(catalog.servers().get(0).authHeader().value()).isEqualTo(CREDENTIALS);
    }
}
