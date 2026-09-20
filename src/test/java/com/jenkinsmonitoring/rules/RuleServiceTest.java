package com.jenkinsmonitoring.rules;

import com.jenkinsmonitoring.server.ServerCatalog;
import com.jenkinsmonitoring.storage.Cursor;
import com.jenkinsmonitoring.storage.CursorRepository;
import com.jenkinsmonitoring.storage.Rule;
import com.jenkinsmonitoring.storage.RuleRepository;
import com.jenkinsmonitoring.support.FakeJenkins;
import com.jenkinsmonitoring.support.TestServers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuleServiceTest {

    @TempDir
    Path folder;

    private FakeJenkins jenkins;
    private RuleRepository repository;
    private CursorRepository cursors;
    private RuleService service;

    @BeforeEach
    void setUp() {
        var files = TestServers.files(folder);
        jenkins = new FakeJenkins();
        repository = new RuleRepository(files);
        cursors = new CursorRepository(files);
        service = newService();
    }

    private RuleService newService() {
        ServerCatalog catalog = TestServers.catalog();
        RuleService created = new RuleService(repository, catalog, jenkins, cursors, Clock.systemDefaultZone());
        created.load();
        return created;
    }

    @Test
    void addsARuleAndSavesItWithTheCanonicalServer() {
        Rule rule = service.add("jenkins.example.com", " deploy ", Map.of("user", "artem.holovchenko"));

        assertThat(rule.id()).isEqualTo(1);
        assertThat(rule.server()).isEqualTo("https://jenkins.example.com");
        assertThat(rule.job()).isEqualTo("deploy");
        assertThat(repository.load().rules()).hasSize(1);
        assertThat(service.list()).containsExactly(rule);
    }

    @Test
    void theServerMayBeGivenInAnyForm() {
        assertThat(service.add("https://jenkins.example.com/", "a", null).server()).isEqualTo("https://jenkins.example.com");
        assertThat(service.add("HTTPS://Jenkins.Example.com/mcp-server/mcp", "b", null).server()).isEqualTo("https://jenkins.example.com");
    }

    @Test
    void serverAndJobAreRequiredWithAnExplanation() {
        assertThatThrownBy(() -> service.add("jenkins.example.com", null, Map.of("user", "artem.holovchenko")))
                .isInstanceOf(RuleException.class)
                .hasMessageContaining("requires both `server` and `job`").hasMessageContaining("Example");
        assertThatThrownBy(() -> service.add(" ", "deploy", null)).isInstanceOf(RuleException.class)
                .hasMessageContaining("requires both");
        assertThat(repository.load().rules()).isEmpty();
    }

    @Test
    void anUnknownServerListsTheConfiguredOnes() {
        assertThatThrownBy(() -> service.add("other.example.com", "deploy", null))
                .isInstanceOf(RuleException.class)
                .hasMessageContaining("does not match any configured").hasMessageContaining("https://jenkins.example.com");
    }

    @Test
    void aServerThatIsDownIsNotAccepted() {
        jenkins.unavailable = true;

        assertThatThrownBy(() -> service.add("jenkins.example.com", "deploy", null))
                .isInstanceOf(RuleException.class).hasMessageContaining("down").hasMessageContaining("rule was not saved");
        assertThat(repository.load().rules()).isEmpty();
    }

    @Test
    void aJobThatDoesNotExistIsNotAccepted() {
        jenkins.jobMissing = true;

        assertThatThrownBy(() -> service.add("jenkins.example.com", "missing", null))
                .isInstanceOf(RuleException.class).hasMessageContaining("was not found");
    }

    @Test
    void oneRulePerServerAndJob() {
        Rule first = service.add("jenkins.example.com", "deploy", null);

        assertThatThrownBy(() -> service.add("https://jenkins.example.com", "deploy", Map.of("status", "FAILURE")))
                .isInstanceOf(RuleException.class)
                .hasMessageContaining("already exists").hasMessageContaining("rule " + first.id());
        assertThat(service.add("jenkins.example.com", "other", null).id()).isEqualTo(2);
    }

    @Test
    void invalidConditionsAreNotSaved() {
        assertThatThrownBy(() -> service.add("jenkins.example.com", "deploy", Map.of("user", "Artem Holovchenko")))
                .isInstanceOf(RuleException.class).hasMessageContaining("name.surname");
        assertThat(repository.load().rules()).isEmpty();
    }

    @Test
    void removesARuleAndItsCursorWithoutTouchingJenkins() {
        service.add("jenkins.example.com", "deploy", null);
        cursors.save(new Cursor(LocalDateTime.now(), LocalDateTime.now(), "https://jenkins.example.com", "deploy", 5, null, null));
        jenkins.unavailable = true;

        Rule removed = service.remove("jenkins.example.com", "deploy");

        assertThat(removed.job()).isEqualTo("deploy");
        assertThat(service.list()).isEmpty();
        assertThat(repository.load().rules()).isEmpty();
        assertThat(cursors.find("https://jenkins.example.com", "deploy")).isEmpty();
    }

    @Test
    void removingAnUnknownRuleIsAnError() {
        assertThatThrownBy(() -> service.remove("jenkins.example.com", "deploy"))
                .isInstanceOf(RuleException.class).hasMessageContaining("Rule not found");
        assertThatThrownBy(() -> service.remove("jenkins.example.com", null))
                .isInstanceOf(RuleException.class).hasMessageContaining("requires both");
    }

    @Test
    void removedRuleIdsAreNotReused() {
        service.add("jenkins.example.com", "a", null);
        service.remove("jenkins.example.com", "a");

        assertThat(service.add("jenkins.example.com", "b", null).id()).isEqualTo(2);
    }

    @Test
    void rulesSurviveARestart() {
        service.add("jenkins.example.com", "deploy", Map.of("playbook", List.of("a", "b")));

        RuleService restarted = newService();

        assertThat(restarted.list()).hasSize(1);
        assertThat(restarted.list().get(0).conditionsOrNone().asMap()).containsEntry("playbook", List.of("a", "b"));
    }

    @Test
    void aHandEditedFileIsNormalizedAndBadEntriesAreSkippedWithAProblem() throws IOException {
        Files.createDirectories(folder.resolve("db"));
        Files.writeString(folder.resolve("db").resolve("rules.json"), """
                [{"id": 1, "created_at": "2026-09-20 14:05:33.123", "server": "jenkins.example.com", "job": "good"},
                 {"id": 2, "created_at": "2026-09-20 14:05:33.123", "server": "unknown.example.com", "job": "bad-server"},
                 {"id": 3, "created_at": "2026-09-20 14:05:33.123", "server": "jenkins.example.com", "job": "bad-user",
                  "conditions": {"user": "Nobody Nice"}},
                 {"id": 4, "created_at": "2026-09-20 14:05:33.123", "server": "https://jenkins.example.com", "job": "good"}]
                """);

        RuleService loaded = newService();

        assertThat(loaded.list()).extracting(Rule::server).containsExactly("https://jenkins.example.com");
        assertThat(loaded.problems()).hasSize(3);
        assertThat(loaded.problems()).anyMatch(p -> p.contains("Rule 2") && p.contains("matches no configured"));
        assertThat(loaded.problems()).anyMatch(p -> p.contains("Rule 3") && p.contains("name.surname"));
        assertThat(loaded.problems()).anyMatch(p -> p.contains("Rule 4") && p.contains("already watches"));
    }

    @Test
    void aHandEditMadeWhileRunningIsNotOverwritten() throws IOException {
        service.add("jenkins.example.com", "first", null);
        Path file = folder.resolve("db").resolve("rules.json");
        Files.writeString(file, """
                [{"id": 1, "created_at": "2026-09-20 14:05:33.123", "server": "jenkins.example.com", "job": "first"},
                 {"id": 9, "created_at": "2026-09-20 14:05:33.123", "server": "jenkins.example.com", "job": "hand-added"}]
                """);
        // Some file systems have a coarse clock; make sure the edit is visible as a change
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() + 5_000));

        service.add("jenkins.example.com", "third", null);

        assertThat(service.list()).extracting(Rule::job).containsExactly("first", "hand-added", "third");
        assertThat(service.list().get(2).id()).isEqualTo(10);
    }

    @Test
    void oneMalformedEntryDoesNotHideTheOthers() throws IOException {
        Files.createDirectories(folder.resolve("db"));
        Files.writeString(folder.resolve("db").resolve("rules.json"), """
                [{"id": "not a number", "created_at": "2026-09-20 14:05:33.123", "server": "jenkins.example.com", "job": "broken"},
                 {"id": 2, "created_at": "2026-09-20 14:05:33.123", "server": "jenkins.example.com", "job": "fine"}]
                """);

        RuleService loaded = newService();

        assertThat(loaded.list()).extracting(Rule::job).containsExactly("fine");
        assertThat(loaded.problems()).singleElement().satisfies(problem -> assertThat(problem).contains("Entry 1"));
    }

    @Test
    void aRulesFileThatCannotBeReadIsNeverOverwritten() throws IOException {
        Files.createDirectories(folder.resolve("db"));
        Path file = folder.resolve("db").resolve("rules.json");
        Files.writeString(file, "[ { this is not json");

        RuleService broken = newService();

        assertThat(broken.list()).isEmpty();
        assertThat(broken.problems()).isNotEmpty();
        assertThatThrownBy(() -> broken.add("jenkins.example.com", "deploy", null))
                .isInstanceOf(RuleException.class).hasMessageContaining("cannot be read");
        assertThatThrownBy(() -> broken.remove("jenkins.example.com", "deploy"))
                .isInstanceOf(RuleException.class).hasMessageContaining("cannot be read");
        assertThat(Files.readString(file)).isEqualTo("[ { this is not json");

        Files.writeString(file, "[]");
        assertThat(broken.add("jenkins.example.com", "deploy", null).id()).isEqualTo(1);
    }

    @Test
    void anActionGuardedByARuleRunsOnlyWhileTheRuleExists() {
        Rule rule = service.add("jenkins.example.com", "deploy", null);
        int[] runs = {0};

        service.runIfWatched(rule, () -> runs[0]++);
        service.remove("jenkins.example.com", "deploy");
        service.runIfWatched(rule, () -> runs[0]++);

        assertThat(runs[0]).isEqualTo(1);
    }

    @Test
    void entriesThatAreNotInUseAreWrittenBackUnchangedWhenARuleIsAdded() throws IOException {
        Files.createDirectories(folder.resolve("db"));
        Path file = folder.resolve("db").resolve("rules.json");
        Files.writeString(file, """
                [{"id": 5, "created_at": "2026-09-20 14:05:33.123", "server": "other-server.example.com", "job": "kept"},
                 {"id": "not a number", "created_at": "2026-09-20 14:05:33.123", "server": "jenkins.example.com", "job": "unreadable"}]
                """);
        RuleService loaded = newService();
        assertThat(loaded.list()).isEmpty();

        Rule added = loaded.add("jenkins.example.com", "deploy", null);

        String text = Files.readString(file);
        assertThat(text).contains("\"kept\"", "other-server.example.com", "\"unreadable\"", "not a number", "\"deploy\"");
        // The id of the unused entry is not handed out again
        assertThat(added.id()).isEqualTo(6);
        assertThat(newService().list()).extracting(Rule::job).containsExactly("deploy");
    }
}
