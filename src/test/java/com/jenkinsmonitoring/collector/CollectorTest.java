package com.jenkinsmonitoring.collector;

import com.jenkinsmonitoring.config.AppProperties;
import com.jenkinsmonitoring.logging.ParameterMasker;
import com.jenkinsmonitoring.rules.RuleService;
import com.jenkinsmonitoring.server.ServerCatalog;
import com.jenkinsmonitoring.storage.Cursor;
import com.jenkinsmonitoring.storage.CursorRepository;
import com.jenkinsmonitoring.storage.JobRecord;
import com.jenkinsmonitoring.storage.JobRepository;
import com.jenkinsmonitoring.storage.NotificationRecord;
import com.jenkinsmonitoring.storage.NotificationRepository;
import com.jenkinsmonitoring.storage.NotificationStatus;
import com.jenkinsmonitoring.storage.RuleRepository;
import com.jenkinsmonitoring.support.FakeJenkins;
import com.jenkinsmonitoring.support.TestServers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CollectorTest {

    private static final String SERVER = TestServers.SERVER;

    @TempDir
    Path folder;

    private FakeJenkins jenkins;
    private RuleService rules;
    private CursorRepository cursors;
    private JobRepository jobs;
    private NotificationRepository notifications;
    private Collector collector;

    @BeforeEach
    void setUp() {
        build(TestServers.catalog());
    }

    private void build(ServerCatalog catalog) {
        var files = TestServers.files(folder);
        jenkins = new FakeJenkins();
        cursors = new CursorRepository(files);
        jobs = new JobRepository(files);
        notifications = new NotificationRepository(files);
        rules = new RuleService(new RuleRepository(files), catalog, jenkins, cursors, Clock.systemDefaultZone());
        rules.load();
        collector = CollectorFixture.collector(rules, catalog, jenkins, cursors, jobs, notifications, Clock.systemDefaultZone());
    }

    private Cursor cursor() {
        return cursors.find(SERVER, "deploy").orElseThrow();
    }

    private void watch(Map<String, Object> conditions) {
        rules.add("jenkins.example.com", "deploy", conditions);
    }

    @Test
    void firstSightOnlyRemembersTheLatestBuildAndNotifiesNothing() {
        jenkins.add(1, "SUCCESS").add(2, "FAILURE").add(3, "SUCCESS");
        watch(null);

        collector.runOnce();

        assertThat(cursor().buildId()).isEqualTo(3);
        assertThat(cursor().url()).isEqualTo(SERVER + "/job/deploy/3/");
        assertThat(jobs.findAll()).isEmpty();
        assertThat(notifications.find(null)).isEmpty();
    }

    @Test
    void aNewBuildIsStoredWithAPendingNotification() {
        jenkins.add(1, "SUCCESS");
        watch(null);
        collector.runOnce();

        jenkins.add(2, "FAILURE", "artem.holovchenko", Map.of("Playbook", "Azure Resources", "ENVIRONMENT", "DEV"));
        collector.runOnce();

        JobRecord job = jobs.findAll().get(0);
        assertThat(job.url()).isEqualTo(SERVER + "/job/deploy/2/");
        assertThat(job.server()).isEqualTo(SERVER);
        assertThat(job.job()).isEqualTo("deploy");
        assertThat(job.buildNumber()).isEqualTo(2);
        assertThat(job.status()).isEqualTo("FAILURE");
        assertThat(job.triggeredBy()).isEqualTo("artem.holovchenko");
        assertThat(job.playbook()).isEqualTo("Azure Resources");
        assertThat(job.parameters()).containsOnlyKeys("ENVIRONMENT");
        assertThat(job.duration()).isEqualTo("3m 5s");
        NotificationRecord notification = notifications.find(null).get(0);
        assertThat(notification.status()).isEqualTo(NotificationStatus.PENDING);
        assertThat(notification.ruleId()).isEqualTo(1);
        assertThat(notification.url()).isEqualTo(job.url());
        assertThat(cursor().buildId()).isEqualTo(2);
    }

    @Test
    void severalNewBuildsAreProcessedInOrder() {
        jenkins.add(1, "SUCCESS");
        watch(null);
        collector.runOnce();

        jenkins.add(2, "SUCCESS").add(3, "FAILURE").add(4, "SUCCESS");
        collector.runOnce();

        assertThat(jobs.findAll()).extracting(JobRecord::buildNumber).containsExactly(2, 3, 4);
        assertThat(cursor().buildId()).isEqualTo(4);
    }

    @Test
    void runningTheCollectorAgainDoesNotDuplicateAnything() {
        jenkins.add(1, "SUCCESS");
        watch(null);
        collector.runOnce();
        jenkins.add(2, "SUCCESS");

        collector.runOnce();
        collector.runOnce();
        collector.runOnce();

        assertThat(jobs.findAll()).hasSize(1);
        assertThat(notifications.find(null)).hasSize(1);
    }

    @Test
    void aCrashBeforeTheCursorMovedDoesNotCreateDuplicates() {
        jenkins.add(1, "SUCCESS");
        watch(null);
        collector.runOnce();
        jenkins.add(2, "SUCCESS");
        collector.runOnce();
        // Simulate a crash after the build was saved but before the cursor was moved
        cursors.save(cursor().advancedTo(1, SERVER + "/job/deploy/1/", List.of(), java.time.LocalDateTime.now()));

        collector.runOnce();

        assertThat(jobs.findAll()).hasSize(1);
        assertThat(notifications.find(null)).hasSize(1);
        assertThat(cursor().buildId()).isEqualTo(2);
    }

    @Test
    void buildsThatDoNotMatchTheConditionsAreNotStoredButTheCursorMoves() {
        jenkins.add(1, "SUCCESS");
        watch(Map.of("status", "FAILURE"));
        collector.runOnce();

        jenkins.add(2, "SUCCESS").add(3, "FAILURE");
        collector.runOnce();

        assertThat(jobs.findAll()).extracting(JobRecord::buildNumber).containsExactly(3);
        assertThat(cursor().buildId()).isEqualTo(3);
    }

    @Test
    void aRunningBuildIsTrackedAndStoredWhenItFinishes() {
        jenkins.add(1, "SUCCESS");
        watch(null);
        collector.runOnce();

        jenkins.add(2, null);
        collector.runOnce();
        assertThat(jobs.findAll()).isEmpty();
        assertThat(cursor().buildId()).isEqualTo(2);
        assertThat(cursor().openBuilds()).containsExactly(2);

        jenkins.finish(2, "SUCCESS");
        collector.runOnce();
        assertThat(jobs.findAll()).extracting(JobRecord::buildNumber).containsExactly(2);
        assertThat(cursor().openBuilds()).isEmpty();
    }

    @Test
    void aSlowBuildDoesNotBlockTheBuildsAfterIt() {
        jenkins.add(1, "SUCCESS");
        watch(null);
        collector.runOnce();

        jenkins.add(2, null).add(3, "SUCCESS");
        collector.runOnce();

        assertThat(jobs.findAll()).extracting(JobRecord::buildNumber).containsExactly(3);
        assertThat(cursor().buildId()).isEqualTo(3);
        assertThat(cursor().openBuilds()).containsExactly(2);

        jenkins.finish(2, "FAILURE");
        collector.runOnce();

        assertThat(jobs.findAll()).extracting(JobRecord::buildNumber).containsExactlyInAnyOrder(2, 3);
        assertThat(cursor().openBuilds()).isEmpty();
    }

    @Test
    void aBuildThatIsRunningAtFirstSightIsReportedWhenItFinishes() {
        jenkins.add(1, "SUCCESS").add(2, null);
        watch(null);
        collector.runOnce();
        assertThat(cursor().openBuilds()).containsExactly(2);

        jenkins.finish(2, "SUCCESS");
        collector.runOnce();

        assertThat(jobs.findAll()).extracting(JobRecord::buildNumber).containsExactly(2);
    }

    @Test
    void aBuildThatJenkinsDiscardedWhileOpenIsForgotten() {
        jenkins.add(1, "SUCCESS");
        watch(null);
        collector.runOnce();
        jenkins.add(2, null);
        collector.runOnce();

        jenkins.remove(2);
        collector.runOnce();

        assertThat(cursor().openBuilds()).isEmpty();
        assertThat(jobs.findAll()).isEmpty();
    }

    @Test
    void whenTheCursorBuildWasDiscardedTheNumbersAfterItAreScanned() {
        jenkins.add(1, "SUCCESS").add(2, "SUCCESS");
        watch(null);
        collector.runOnce();
        jenkins.remove(2).add(3, "SUCCESS").add(5, "FAILURE").nextBuildNumber(6);

        collector.runOnce();

        assertThat(jobs.findAll()).extracting(JobRecord::buildNumber).containsExactly(3, 5);
        assertThat(cursor().buildId()).isEqualTo(5);
    }

    @Test
    void aJobThatHasNeverRunStartsFromItsFirstBuild() {
        watch(null);
        collector.runOnce();
        assertThat(cursor().buildId()).isZero();

        jenkins.add(1, "SUCCESS");
        collector.runOnce();

        assertThat(jobs.findAll()).extracting(JobRecord::buildNumber).containsExactly(1);
    }

    @Test
    void aRecreatedJobWithResetNumbersStartsOverWithoutNotifications() {
        jenkins.add(1, "SUCCESS").add(2, "SUCCESS").add(3, "SUCCESS");
        watch(null);
        collector.runOnce();
        jenkins.remove(1).remove(2).remove(3).add(1, "SUCCESS").nextBuildNumber(2);

        collector.runOnce();

        assertThat(cursor().buildId()).isEqualTo(1);
        assertThat(jobs.findAll()).isEmpty();
        assertThat(collector.problems()).containsKey(1);
    }

    @Test
    void catchUpIsLimitedPerCycleAndFinishesOnTheNext() {
        jenkins.add(1, "SUCCESS");
        watch(null);
        collector.runOnce();
        for (int i = 2; i <= 30; i++) {
            jenkins.add(i, "SUCCESS");
        }

        collector.runOnce();
        assertThat(cursor().buildId()).isEqualTo(21);
        collector.runOnce();

        assertThat(jobs.findAll()).hasSize(29);
        assertThat(cursor().buildId()).isEqualTo(30);
    }

    @Test
    void aServerThatIsDownIsReportedAndNothingIsChanged() {
        jenkins.add(1, "SUCCESS");
        watch(null);
        collector.runOnce();
        jenkins.add(2, "SUCCESS");
        jenkins.unavailable = true;

        collector.runOnce();

        assertThat(collector.problems().get(1)).contains("down");
        assertThat(jobs.findAll()).isEmpty();
        assertThat(cursor().buildId()).isEqualTo(1);

        jenkins.unavailable = false;
        collector.runOnce();
        assertThat(collector.problems()).isEmpty();
        assertThat(jobs.findAll()).hasSize(1);
    }

    @Test
    void aJobThatNoLongerExistsIsReported() {
        jenkins.add(1, "SUCCESS");
        watch(null);
        collector.runOnce();
        jenkins.jobMissing = true;

        collector.runOnce();

        assertThat(collector.problems().get(1)).contains("was not found");
    }

    @Test
    void secretParametersAreMaskedAndLongOrMultiLineOnesAreLeftOut() {
        jenkins.add(1, "SUCCESS");
        watch(null);
        collector.runOnce();

        jenkins.add(2, "SUCCESS", "artem.holovchenko", Map.of(
                "DEPLOY_TOKEN", "abc123", "REGION_NAME", "eastus", "SETTINGS_JSON", "{\n \"a\": 1\n}",
                "LONG", "x".repeat(201), "EMPTY", ""));
        collector.runOnce();

        assertThat(jobs.findAll().get(0).parameters())
                .containsEntry("DEPLOY_TOKEN", "***").containsEntry("REGION_NAME", "eastus")
                .doesNotContainKeys("SETTINGS_JSON", "LONG", "EMPTY");
    }

    @Test
    void oneServerThatIsDownDoesNotBlockTheOthers() {
        build(new ServerCatalog(TestServers.properties(TestServers.server("https://jenkins.example.com/mcp"),
                TestServers.server("https://jenkins2.example.com/mcp")), TestServers.environment()));
        jenkins.add(1, "SUCCESS");
        rules.add("jenkins.example.com", "deploy", null);
        rules.add("jenkins2.example.com", "deploy", null);
        collector.runOnce();
        jenkins.add(2, "SUCCESS");
        jenkins.downServers.add("https://jenkins.example.com");

        collector.runOnce();

        assertThat(collector.problems()).containsOnlyKeys(1);
        assertThat(jobs.findAll()).extracting(JobRecord::server).containsExactly("https://jenkins2.example.com");

        jenkins.downServers.clear();
        collector.runOnce();

        assertThat(collector.problems()).isEmpty();
        assertThat(jobs.findAll()).hasSize(2);
    }

    @Test
    void aRemovedRuleTakesItsProblemWithIt() {
        jenkins.add(1, "SUCCESS");
        watch(null);
        collector.runOnce();
        jenkins.jobMissing = true;
        collector.runOnce();
        assertThat(collector.problems()).containsKey(1);

        rules.remove("jenkins.example.com", "deploy");
        collector.runOnce();

        assertThat(collector.problems()).isEmpty();
    }

    @Test
    void aRemovedRuleGetsNeitherItsCursorNorNewRecordsBack() {
        jenkins.add(1, "SUCCESS");
        watch(null);
        collector.runOnce();
        var removed = rules.list().get(0);
        var lastCursor = cursor();
        rules.remove("jenkins.example.com", "deploy");

        // What a pass that was already running when the rule was removed would try to write
        rules.runIfWatched(removed, () -> cursors.save(lastCursor));

        assertThat(cursors.find(SERVER, "deploy")).isEmpty();
    }

    @Test
    void aMissingNextBuildNumberIsAVisibleProblemNotASilentStall() {
        jenkins.add(1, "SUCCESS").add(2, "SUCCESS");
        watch(null);
        collector.runOnce();
        jenkins.remove(2).nextBuildNumber(0);

        collector.runOnce();

        assertThat(collector.problems().get(1)).contains("next build number");
    }

    @Test
    void aLinkedBuildThatCannotBeReadIsAVisibleProblemAndIsRetried() {
        jenkins.add(1, "SUCCESS");
        watch(null);
        collector.runOnce();
        jenkins.add(2, "SUCCESS");
        jenkins.hidden.add(2);

        collector.runOnce();

        assertThat(collector.problems().get(1)).contains("#2").contains("cannot be read");
        assertThat(jobs.findAll()).isEmpty();

        jenkins.hidden.clear();
        collector.runOnce();

        assertThat(collector.problems()).isEmpty();
        assertThat(jobs.findAll()).extracting(JobRecord::buildNumber).containsExactly(2);
    }
}
