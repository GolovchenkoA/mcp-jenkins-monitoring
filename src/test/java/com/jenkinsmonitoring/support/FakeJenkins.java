package com.jenkinsmonitoring.support;

import com.jenkinsmonitoring.config.JenkinsProperties;
import com.jenkinsmonitoring.jenkins.JenkinsException;
import com.jenkinsmonitoring.jenkins.JenkinsGateway;
import com.jenkinsmonitoring.server.JenkinsServer;
import com.jenkinsmonitoring.storage.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** A Jenkins with one job, in memory, that answers like the Jenkins MCP server does for the tree we request. */
public class FakeJenkins extends JenkinsGateway {

    private record FakeBuild(int number, String result, String userId, String cause, Map<String, String> parameters) {
    }

    private final TreeMap<Integer, FakeBuild> builds = new TreeMap<>();
    private int nextBuildNumber = 1;
    public boolean unavailable;
    public boolean jobMissing;
    /** When set, the outage looks like rejected credentials. */
    public boolean authFailure;
    /** Builds that exist in the chain of links but answer "no results" when asked for directly. */
    public final Set<Integer> hidden = new HashSet<>();
    /** Servers (canonical URL) that do not answer, while the others do. */
    public final Set<String> downServers = new HashSet<>();
    public JsonNode jenkinsStatus = Json.MAPPER.readTree(
            "{\"Quiet Mode\": false, \"Active administrative monitors\": [], \"Root URL Status\": \"OK\"}");
    public int buildCalls;

    public FakeJenkins() {
        super(new JenkinsProperties(Map.of(), List.of(), "", Duration.ofSeconds(1)), null, null);
    }

    /** A build started by a person. A null result means the build is still running. */
    public FakeJenkins add(int number, String result, String userId, Map<String, String> parameters) {
        builds.put(number, new FakeBuild(number, result, userId, "Started by user " + userId, parameters));
        nextBuildNumber = Math.max(nextBuildNumber, number + 1);
        return this;
    }

    public FakeJenkins add(int number, String result) {
        return add(number, result, "artem.holovchenko", Map.of());
    }

    public FakeJenkins finish(int number, String result) {
        FakeBuild old = builds.get(number);
        builds.put(number, new FakeBuild(number, result, old.userId(), old.cause(), old.parameters()));
        return this;
    }

    public FakeJenkins remove(int number) {
        builds.remove(number);
        return this;
    }

    public FakeJenkins nextBuildNumber(int number) {
        nextBuildNumber = number;
        return this;
    }

    @Override
    public JsonNode getBuild(JenkinsServer server, String job, Integer buildNumber) {
        buildCalls++;
        checkAvailable(server);
        FakeBuild build = buildNumber == null ? builds.lastEntry() == null ? null : builds.lastEntry().getValue()
                : hidden.contains(buildNumber) ? null : builds.get(buildNumber);
        if (build == null) {
            throw new JenkinsException.NotFound("no results");
        }
        return render(server, job, build);
    }

    @Override
    public JsonNode getJob(JenkinsServer server, String job, String tree) {
        checkAvailable(server);
        ObjectNode node = Json.MAPPER.createObjectNode();
        node.put("fullName", job);
        node.put("nextBuildNumber", nextBuildNumber);
        return node;
    }

    @Override
    public JsonNode whoAmI(JenkinsServer server) {
        checkAvailable(server);
        return Json.MAPPER.createObjectNode().put("fullName", "tester");
    }

    @Override
    public JsonNode getStatus(JenkinsServer server) {
        checkAvailable(server);
        return jenkinsStatus;
    }

    private void checkAvailable(JenkinsServer server) {
        if (unavailable || downServers.contains(server.canonical())) {
            throw new JenkinsException.Unavailable("down", authFailure, null);
        }
        if (jobMissing) {
            throw new JenkinsException.NotFound("no results");
        }
    }

    private JsonNode render(JenkinsServer server, String job, FakeBuild build) {
        ObjectNode node = Json.MAPPER.createObjectNode();
        node.put("number", build.number());
        node.put("url", server.canonical() + "/job/" + job + "/" + build.number() + "/");
        node.put("building", build.result() == null);
        if (build.result() == null) {
            node.putNull("result");
        } else {
            node.put("result", build.result());
        }
        node.put("timestamp", 1_789_133_991_865L);
        node.put("duration", 185_071L);
        Integer next = builds.higherKey(build.number());
        if (next != null) {
            node.putObject("nextBuild").put("number", next).put("url", server.canonical() + "/job/" + job + "/" + next + "/");
        } else {
            node.putNull("nextBuild");
        }
        ArrayNode actions = node.putArray("actions");
        actions.addObject().putArray("causes").addObject()
                .put("shortDescription", build.cause()).put("userId", build.userId()).put("userName", "Some User");
        Map<String, String> parameters = new LinkedHashMap<>(build.parameters());
        if (!parameters.isEmpty()) {
            ArrayNode list = actions.addObject().putArray("parameters");
            parameters.forEach((name, value) -> list.addObject().put("name", name).put("value", value));
        }
        return node;
    }
}
