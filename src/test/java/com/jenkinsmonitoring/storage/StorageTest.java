package com.jenkinsmonitoring.storage;

import com.jenkinsmonitoring.support.TestServers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StorageTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 20, 14, 5, 33, 123_000_000);

    @TempDir
    Path folder;

    private JobRepository jobs;
    private NotificationRepository notifications;
    private RuleRepository rules;
    private CursorRepository cursors;

    @BeforeEach
    void repositories() {
        var files = TestServers.files(folder);
        jobs = new JobRepository(files);
        notifications = new NotificationRepository(files);
        rules = new RuleRepository(files);
        cursors = new CursorRepository(files);
    }

    private static JobRecord job(String url, LocalDateTime createdAt) {
        return new JobRecord(createdAt, url, "https://jenkins.example.com", "deploy", 1, "SUCCESS", "artem.holovchenko",
                null, null, "3m 5s", "Azure Resources", Map.of("ENVIRONMENT", "DEV"), List.of("origin/develop"));
    }

    private static NotificationRecord notification(int ruleId, String url, LocalDateTime createdAt, NotificationStatus status) {
        return new NotificationRecord(createdAt, ruleId, url, status, "https://jenkins.example.com", "deploy", 1, "SUCCESS");
    }

    @Test
    void aBuildUrlIsStoredOnlyOnce() {
        assertThat(jobs.insertIfAbsent(job("https://j/1/", T0))).isTrue();
        assertThat(jobs.insertIfAbsent(job("https://j/1/", T0.plusMinutes(1)))).isFalse();

        assertThat(jobs.findAll()).hasSize(1);
    }

    @Test
    void recordsAreReadOldestFirstEvenIfInsertedOutOfOrder() {
        jobs.insertIfAbsent(job("https://j/3/", T0.plusMinutes(3)));
        jobs.insertIfAbsent(job("https://j/1/", T0.plusMinutes(1)));
        jobs.insertIfAbsent(job("https://j/2/", T0.plusMinutes(2)));

        assertThat(jobs.findAll()).extracting(JobRecord::url).containsExactly("https://j/1/", "https://j/2/", "https://j/3/");
    }

    @Test
    void recordsWithTheSameTimeKeepTheirInsertionOrder() {
        jobs.insertIfAbsent(job("https://j/b/", T0));
        jobs.insertIfAbsent(job("https://j/a/", T0));

        assertThat(jobs.findAll()).extracting(JobRecord::url).containsExactly("https://j/b/", "https://j/a/");
    }

    @Test
    void recentReturnsTheNewestRecordsOldestFirst() {
        for (int i = 1; i <= 5; i++) {
            jobs.insertIfAbsent(job("https://j/" + i + "/", T0.plusMinutes(i)));
        }

        assertThat(jobs.recent(2)).extracting(JobRecord::url).containsExactly("https://j/4/", "https://j/5/");
        assertThat(jobs.recent(50)).hasSize(5);
    }

    @Test
    void theFileHasSnakeCaseNamesAndAFriendlyTimeWithMilliseconds() throws IOException {
        jobs.insertIfAbsent(job("https://j/1/", T0));

        String text = Files.readString(folder.resolve("db").resolve("jobs.json"));

        assertThat(text).contains("\"created_at\" : \"2026-09-20 14:05:33.123\"", "\"build_number\" : 1",
                "\"triggered_by\" : \"artem.holovchenko\"", "\"scm_branches\"");
        assertThat(text).doesNotContain("description");
    }

    @Test
    void oldRecordsAreDeleted() {
        jobs.insertIfAbsent(job("https://j/old/", T0.minusDays(40)));
        jobs.insertIfAbsent(job("https://j/new/", T0));

        int deleted = jobs.deleteOlderThan(T0.minusDays(30));

        assertThat(deleted).isEqualTo(1);
        assertThat(jobs.findAll()).extracting(JobRecord::url).containsExactly("https://j/new/");
    }

    @Test
    void aNotificationIsStoredOncePerRuleAndBuild() {
        assertThat(notifications.insertIfAbsent(notification(1, "https://j/1/", T0, NotificationStatus.PENDING))).isTrue();
        assertThat(notifications.insertIfAbsent(notification(1, "https://j/1/", T0, NotificationStatus.PENDING))).isFalse();
        assertThat(notifications.insertIfAbsent(notification(2, "https://j/1/", T0, NotificationStatus.PENDING))).isTrue();

        assertThat(Files.exists(folder.resolve("db").resolve("notification_1.json"))).isTrue();
        assertThat(Files.exists(folder.resolve("db").resolve("notification_2.json"))).isTrue();
    }

    @Test
    void notificationsCanBeFilteredByStatusAndAreSortedAcrossRules() {
        notifications.insertIfAbsent(notification(2, "https://j/2/", T0.plusMinutes(2), NotificationStatus.PENDING));
        notifications.insertIfAbsent(notification(1, "https://j/1/", T0.plusMinutes(1), NotificationStatus.DELIVERED));
        notifications.insertIfAbsent(notification(1, "https://j/3/", T0.plusMinutes(3), NotificationStatus.PENDING));

        assertThat(notifications.find(null)).extracting(NotificationRecord::url)
                .containsExactly("https://j/1/", "https://j/2/", "https://j/3/");
        assertThat(notifications.find(NotificationStatus.PENDING)).extracting(NotificationRecord::url)
                .containsExactly("https://j/2/", "https://j/3/");
    }

    @Test
    void oldNotificationsAreDeletedFromEveryRuleFile() {
        notifications.insertIfAbsent(notification(1, "https://j/old/", T0.minusDays(40), NotificationStatus.PENDING));
        notifications.insertIfAbsent(notification(2, "https://j/new/", T0, NotificationStatus.PENDING));

        assertThat(notifications.deleteOlderThan(T0.minusDays(30))).isEqualTo(1);
        assertThat(notifications.find(null)).extracting(NotificationRecord::url).containsExactly("https://j/new/");
    }

    @Test
    void ruleIdsAreNeverReused() {
        int first = rules.nextId(List.of());
        int second = rules.nextId(List.of(new Rule(first, T0, "s", "j", null)));
        // The rule with the highest id is removed; its id must still not come back
        int third = rules.nextId(List.of());

        assertThat(List.of(first, second, third)).containsExactly(1, 2, 3);
    }

    @Test
    void conditionsAreWrittenAsAStringOrAListAndReadBack() throws IOException {
        Conditions conditions = new Conditions(Map.of("user", List.of("artem.holovchenko"), "playbook", List.of("a", "b")));
        rules.saveAll(List.of(new Rule(1, T0, "https://jenkins.example.com", "deploy", conditions)), List.of(), List.of());

        String text = Files.readString(folder.resolve("db").resolve("rules.json"));

        assertThat(text).contains("\"user\" : \"artem.holovchenko\"").containsPattern("\"playbook\" : \\[\\s*\"a\",\\s*\"b\"\\s*\\]");
        assertThat(rules.load().rules().get(0).conditionsOrNone()).isEqualTo(conditions);
    }

    @Test
    void aRuleWithoutConditionsHasNoConditionsField() throws IOException {
        rules.saveAll(List.of(new Rule(1, T0, "https://jenkins.example.com", "deploy", null)), List.of(), List.of());

        assertThat(Files.readString(folder.resolve("db").resolve("rules.json"))).doesNotContain("conditions");
        assertThat(rules.load().rules().get(0).conditionsOrNone().isEmpty()).isTrue();
    }

    @Test
    void aHandEditedRuleFileWithASingleStringOrNumberIsUnderstood() throws IOException {
        Files.createDirectories(folder.resolve("db"));
        Files.writeString(folder.resolve("db").resolve("rules.json"), """
                [{"id": 7, "created_at": "2026-09-20 14:05:33.123", "server": "jenkins-server1.com", "job": "deploy",
                  "conditions": {"user": "artem.holovchenko", "BUILD": 42, "status": ["FAILURE", "UNSTABLE"]}}]
                """);

        Rule rule = rules.load().rules().get(0);

        assertThat(rule.id()).isEqualTo(7);
        assertThat(rule.conditionsOrNone().asMap()).containsEntry("BUILD", List.of("42"))
                .containsEntry("status", List.of("FAILURE", "UNSTABLE"));
    }

    @Test
    void cursorsAreReplacedPerServerAndJob() {
        Cursor first = new Cursor(T0, T0, "https://jenkins.example.com", "deploy", 5, "https://j/5/", null);
        cursors.save(first);
        cursors.save(first.advancedTo(6, "https://j/6/", List.of(6), T0.plusMinutes(1)));
        cursors.save(new Cursor(T0, T0, "https://jenkins.example.com", "other", 1, null, null));

        Cursor found = cursors.find("https://jenkins.example.com", "deploy").orElseThrow();
        assertThat(found.buildId()).isEqualTo(6);
        assertThat(found.openBuilds()).containsExactly(6);

        cursors.delete("https://jenkins.example.com", "deploy");
        assertThat(cursors.find("https://jenkins.example.com", "deploy")).isEmpty();
        assertThat(cursors.find("https://jenkins.example.com", "other")).isPresent();
    }

    @Test
    void aCorruptFileIsReportedNotSilentlyOverwritten() throws IOException {
        Files.createDirectories(folder.resolve("db"));
        Files.writeString(folder.resolve("db").resolve("jobs.json"), "[ this is not json");

        assertThatThrownBy(() -> jobs.findAll()).isInstanceOf(StorageException.class).hasMessageContaining("jobs.json");
        assertThatThrownBy(() -> jobs.insertIfAbsent(job("https://j/1/", T0))).isInstanceOf(StorageException.class);
        assertThat(Files.readString(folder.resolve("db").resolve("jobs.json"))).isEqualTo("[ this is not json");
    }

    @Test
    void noTemporaryFileIsLeftBehind() throws IOException {
        jobs.insertIfAbsent(job("https://j/1/", T0));

        try (var files = Files.list(folder.resolve("db"))) {
            assertThat(files.map(p -> p.getFileName().toString())).noneMatch(name -> name.endsWith(".tmp"));
        }
    }

    @Test
    void aSecondInstanceCannotUseTheSameStorageFolder() throws IOException {
        StorageLock first = new StorageLock(TestServers.files(folder));
        try {
            assertThatThrownBy(() -> new StorageLock(TestServers.files(folder)))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("Another instance");
        } finally {
            first.destroy();
        }
        new StorageLock(TestServers.files(folder)).destroy();
    }
}
