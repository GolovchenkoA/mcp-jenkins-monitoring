package com.jenkinsmonitoring.rules;

import com.jenkinsmonitoring.jenkins.BuildInfo;
import com.jenkinsmonitoring.jenkins.BuildParser;
import com.jenkinsmonitoring.storage.Conditions;
import com.jenkinsmonitoring.storage.Json;
import com.jenkinsmonitoring.storage.Rule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Validation and matching of rule conditions. */
class RulesTest {

    private static BuildInfo build(String userId, String status, String playbook, String environment) {
        StringBuilder parameters = new StringBuilder();
        if (playbook != null) {
            parameters.append("{\"name\": \"Playbook\", \"value\": \"").append(playbook).append("\"},");
        }
        parameters.append("{\"name\": \"ENVIRONMENT\", \"value\": \"").append(environment).append("\"}");
        String causes = userId == null ? "{\"causes\": [{\"shortDescription\": \"Triggered by GitLab\"}]}"
                : "{\"causes\": [{\"shortDescription\": \"Started by user\", \"userId\": \"" + userId + "\"}]}";
        return BuildParser.parse(Json.MAPPER.readTree("{\"number\": 1, \"result\": \"" + status + "\", \"url\": \"u\", "
                + "\"actions\": [" + causes + ", {\"parameters\": [" + parameters + "]}]}"));
    }

    private static Rule rule(Map<String, Object> conditions) {
        return new Rule(1, null, "https://jenkins.example.com", "deploy", ConditionsValidator.validate(conditions));
    }

    private static Map<String, Object> map(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }

    // --- validation ---

    @Test
    void noConditionsMeansNone() {
        assertThat(ConditionsValidator.validate(null).isEmpty()).isTrue();
        assertThat(ConditionsValidator.validate(Map.of()).isEmpty()).isTrue();
    }

    @Test
    void knownKeysAreNormalized() {
        Conditions conditions = ConditionsValidator.validate(map("USER", "Artem.Holovchenko", "Status", "failure", "Playbook", "x"));

        assertThat(conditions.asMap()).containsEntry("user", List.of("artem.holovchenko"))
                .containsEntry("status", List.of("FAILURE")).containsEntry("playbook", List.of("x"));
    }

    @Test
    void otherKeysAreKeptAsGiven() {
        assertThat(ConditionsValidator.validate(map("ENVIRONMENT", "DEV")).asMap()).containsEntry("ENVIRONMENT", List.of("DEV"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Artem Holovchenko", "artem", "artem.holovchenko.x", "artem.", ".holovchenko", "artem holovchenko"})
    void aUserMustBeInTheFormNameDotSurname(String user) {
        assertThatThrownBy(() -> ConditionsValidator.validate(map("user", user)))
                .isInstanceOf(RuleException.class)
                .hasMessageContaining("name.surname").hasMessageContaining("artem.holovchenko");
    }

    @Test
    void everyUserInAListIsChecked() {
        assertThatThrownBy(() -> ConditionsValidator.validate(map("user", List.of("artem.holovchenko", "Bob Smith"))))
                .isInstanceOf(RuleException.class).hasMessageContaining("Bob Smith");
    }

    @Test
    void anUnknownStatusIsRejected() {
        assertThatThrownBy(() -> ConditionsValidator.validate(map("status", "BROKEN")))
                .isInstanceOf(RuleException.class).hasMessageContaining("SUCCESS, FAILURE");
    }

    @Test
    void emptyValuesAndListsAreRejected() {
        assertThatThrownBy(() -> ConditionsValidator.validate(map("playbook", List.of()))).isInstanceOf(RuleException.class);
        assertThatThrownBy(() -> ConditionsValidator.validate(map("playbook", " "))).isInstanceOf(RuleException.class);
        assertThatThrownBy(() -> ConditionsValidator.validate(map(" ", "x"))).isInstanceOf(RuleException.class);
    }

    @Test
    void theSameConditionTwiceIsRejected() {
        assertThatThrownBy(() -> ConditionsValidator.validate(map("user", "a.b", "USER", "c.d")))
                .isInstanceOf(RuleException.class).hasMessageContaining("more than once");
    }

    // --- matching ---

    @Test
    void aRuleWithoutConditionsMatchesEveryBuild() {
        assertThat(RuleMatcher.matches(rule(null), build(null, "SUCCESS", null, "DEV"))).isTrue();
    }

    @Test
    void allConditionsMustMatch() {
        Rule rule = rule(map("user", "artem.holovchenko", "status", "FAILURE"));

        assertThat(RuleMatcher.matches(rule, build("artem.holovchenko", "FAILURE", null, "DEV"))).isTrue();
        assertThat(RuleMatcher.matches(rule, build("artem.holovchenko", "SUCCESS", null, "DEV"))).isFalse();
        assertThat(RuleMatcher.matches(rule, build("someone.else", "FAILURE", null, "DEV"))).isFalse();
    }

    @Test
    void aListMeansAnyOfTheValues() {
        Rule rule = rule(map("playbook", List.of("project1", "project12")));

        assertThat(RuleMatcher.matches(rule, build(null, "SUCCESS", "project12", "DEV"))).isTrue();
        assertThat(RuleMatcher.matches(rule, build(null, "SUCCESS", "project2", "DEV"))).isFalse();
    }

    @Test
    void valuesAndNamesAreComparedIgnoringCase() {
        Rule rule = rule(map("environment", "dev", "user", "ARTEM.HOLOVCHENKO"));

        assertThat(RuleMatcher.matches(rule, build("artem.holovchenko", "SUCCESS", null, "DEV"))).isTrue();
    }

    @Test
    void aConditionOnSomethingTheBuildLacksDoesNotMatch() {
        assertThat(RuleMatcher.matches(rule(map("playbook", "x")), build(null, "SUCCESS", null, "DEV"))).isFalse();
        assertThat(RuleMatcher.matches(rule(map("REGION", "eastus")), build(null, "SUCCESS", null, "DEV"))).isFalse();
    }

    @Test
    void aBuildWithoutAUserNeverMatchesAUserCondition() {
        assertThat(RuleMatcher.matches(rule(map("user", "artem.holovchenko")), build(null, "SUCCESS", null, "DEV"))).isFalse();
    }
}
