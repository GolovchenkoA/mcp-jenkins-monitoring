package com.jenkinsmonitoring.config;

import com.jenkinsmonitoring.logging.ParameterMasker;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class StartupConfigTest {

    private final RootFolderEnvironmentPostProcessor processor = new RootFolderEnvironmentPostProcessor();

    @Test
    void relativeFileLocationsAreResolvedNextToTheJar() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("files.root.folder", "jenkins-monitoring-mcp")
                .withProperty("files.storage.path", "${files.root.folder}/db");

        processor.postProcessEnvironment(environment, null);

        Path home = AppHome.directory();
        assertThat(environment.getProperty("files.root.folder")).isEqualTo(home.resolve("jenkins-monitoring-mcp").toString());
        assertThat(environment.getProperty("files.storage.path")).isEqualTo(home.resolve("jenkins-monitoring-mcp").resolve("db").toString());
    }

    @Test
    void absoluteFileLocationsAreLeftAlone() {
        String absolute = Path.of("").toAbsolutePath().resolve("somewhere").toString();
        MockEnvironment environment = new MockEnvironment().withProperty("files.root.folder", absolute)
                .withProperty("files.storage.path", absolute + "/db");

        processor.postProcessEnvironment(environment, null);

        assertThat(environment.getProperty("files.root.folder")).isEqualTo(absolute);
    }

    @Test
    void debugLogFileFollowsTheRootFolder() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("files.root.folder", "jenkins-monitoring-mcp")
                .withProperty("logging.file.name", "${files.root.folder}/logs/console.log");

        processor.postProcessEnvironment(environment, null);

        assertThat(environment.getProperty("logging.file.name"))
                .startsWith(AppHome.directory().toString()).endsWith("console.log");
    }

    @Test
    void secretsLookingParametersAreMasked() {
        ParameterMasker masker = new ParameterMasker(new AppProperties.Masking("(?i).*(password|token|secret|key).*"));

        assertThat(masker.maskValue("DB_PASSWORD", "x")).isEqualTo("***");
        assertThat(masker.maskValue("apiKey", "x")).isEqualTo("***");
        assertThat(masker.maskValue("REGION", "eastus")).isEqualTo("eastus");
    }

    @Test
    void maskedJsonHidesParameterValuesAndSecretKeys() {
        ParameterMasker masker = new ParameterMasker(new AppProperties.Masking("(?i).*(password|token|secret|key).*"));

        String masked = masker.maskJson("""
                {"result": {"actions": [{"parameters": [{"name": "DB_PASSWORD", "value": "hunter2"},
                                                        {"name": "REGION", "value": "eastus"}]}]}, "api_token": "abc"}""");

        assertThat(masked).doesNotContain("hunter2", "abc").contains("eastus", "***");
        assertThat(masker.maskJson("not json at all")).isEqualTo("not json at all");
    }
}
