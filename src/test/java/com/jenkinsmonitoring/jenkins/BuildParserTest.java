package com.jenkinsmonitoring.jenkins;

import com.jenkinsmonitoring.storage.Json;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BuildParserTest {

    /** A response shaped like the sample for a failed, user-started build, requested with the configured tree. */
    private static final String FAILED_BY_USER = """
            {"number": 1018, "result": "FAILURE", "displayName": "#1018", "fullDisplayName": "iot-platform-deploy-dev #1018",
             "timestamp": 1789133991865, "duration": 185071,
             "url": "https://my-jenkins-server.com/job/iot-platform-deploy-dev/1018/", "building": false,
             "nextBuild": {"number": 1019, "url": "https://my-jenkins-server.com/job/iot-platform-deploy-dev/1019/"},
             "actions": [
               {"causes": [{"shortDescription": "Started by user Artem Holovchenko", "userId": "artem.holovchenko", "userName": "Artem Holovchenko"}]},
               {"parameters": [{"name": "ENVIRONMENT", "value": "DEV"}, {"name": "Playbook", "value": "Azure Resources"},
                               {"name": "BRANCH", "value": ""}]},
               {"lastBuiltRevision": {"branch": [{"name": "origin/develop"}]}},
               {"lastBuiltRevision": {"branch": [{"name": "master"}, {"name": "origin/develop"}]}}
             ]}
            """;

    private static final String SUCCESS_BY_WEBHOOK = """
            {"number": 1035, "result": "SUCCESS", "building": false, "nextBuild": null,
             "description": "Triggered by GitLab Merge Request #166: IaaC/ESAAS-6931 => develop",
             "url": "https://my-jenkins-server.com/job/iot-platform-deploy-dev/1035/", "timestamp": 1789727713343, "duration": 56432,
             "actions": [{"causes": [{"shortDescription": "Triggered by GitLab Merge Request #166: IaaC/ESAAS-6931 => develop"}]}]}
            """;

    private static BuildInfo parse(String json) {
        return BuildParser.parse(Json.MAPPER.readTree(json));
    }

    @Test
    void readsAFailedBuildStartedByAPerson() {
        BuildInfo build = parse(FAILED_BY_USER);

        assertThat(build.number()).isEqualTo(1018);
        assertThat(build.status()).isEqualTo("FAILURE");
        assertThat(build.finished()).isTrue();
        assertThat(build.nextBuild()).isEqualTo(1019);
        assertThat(build.userId()).contains("artem.holovchenko");
        assertThat(build.triggeredBy()).isEqualTo("artem.holovchenko");
        assertThat(build.playbook()).contains("Azure Resources");
        assertThat(build.parameter("environment")).contains("DEV");
        assertThat(build.parameter("BRANCH")).isEmpty();
        assertThat(build.scmBranches()).containsExactly("origin/develop", "master");
        assertThat(build.durationMs()).isEqualTo(185071L);
        assertThat(build.startedAt()).isNotNull();
    }

    @Test
    void aWebhookBuildHasNoUserAndTheCauseTextTakesItsPlace() {
        BuildInfo build = parse(SUCCESS_BY_WEBHOOK);

        assertThat(build.userId()).isEmpty();
        assertThat(build.triggeredBy()).startsWith("Triggered by GitLab Merge Request #166");
        assertThat(build.nextBuild()).isNull();
        assertThat(build.playbook()).isEmpty();
        assertThat(build.description()).startsWith("Triggered by GitLab");
    }

    @Test
    void aRunningBuildIsNotFinished() {
        BuildInfo build = parse("""
                {"number": 7, "result": null, "building": true, "url": "https://j/7/", "actions": []}""");

        assertThat(build.status()).isNull();
        assertThat(build.finished()).isFalse();
    }

    @Test
    void anObjectResultIsSearchedForAStatus() {
        BuildInfo build = parse("""
                {"number": 7, "result": {"status": "FAILURE", "reason": "x"}, "building": false, "url": "https://j/7/"}""");

        assertThat(build.status()).isEqualTo("FAILURE");
    }

    @Test
    void anUnrecognisedResultIsUnknownButStillFinished() {
        BuildInfo build = parse("""
                {"number": 7, "result": {"weird": [1, 2]}, "building": false, "url": "https://j/7/"}""");

        assertThat(build.status()).isEqualTo("UNKNOWN");
        assertThat(build.finished()).isTrue();
    }

    @Test
    void aNormalizedStatusIsUppercaseAndOtherResultsAreUnknown() {
        assertThat(parse("{\"number\": 1, \"result\": \"unstable\", \"url\": \"u\"}").status()).isEqualTo("UNSTABLE");
        assertThat(parse("{\"number\": 1, \"result\": \"NOT_BUILT\", \"url\": \"u\"}").status()).isEqualTo("UNKNOWN");
    }

    @Test
    void aResponseWithoutANumberIsRejected() {
        JsonNode node = Json.MAPPER.readTree("{\"result\": \"SUCCESS\"}");

        assertThatThrownBy(() -> BuildParser.parse(node)).isInstanceOf(IllegalArgumentException.class);
    }
}
