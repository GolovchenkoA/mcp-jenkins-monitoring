# Jenkins Monitoring MCP — Requirements

Status: **v1.1**, 2026-09-20. Written from the design discussion; Phase 1 is implemented (see section 18).

Conventions used in this document:

- **[proposed]** — my proposal that you have not explicitly confirmed. All of them are listed again in section 16.
- **[deferred]** — decided to postpone; not part of Phase 1.
- Untagged items were stated or confirmed by you.

---

## 1. Overview

"Jenkins Monitoring MCP" is a Spring Boot application that runs on the user's local computer. It has three roles:

1. **MCP client** of several remote Jenkins MCP servers, authenticating with Basic auth (user + API token).
2. **Monitor**: on a schedule it looks for new builds of watched jobs, evaluates user-defined rules, stores matching builds in local files (long-term memory, LTM) and creates notification records.
3. **MCP server** for the user's AI clients (Claude Desktop, Codex Desktop, ChatGPT). Through tools the user manages rules and reads the stored history and notifications.

Key properties:

- The application is deterministic. No LLM is used in Phase 1. An LLM and a RAG store may be added later [deferred].
- It exposes MCP over **Streamable HTTP**, bound to **127.0.0.1:2026**, with **no authentication** for its own clients.
- Connection settings and secrets for the Jenkins servers are externalized (section 4).
- Notification channels and the LTM storage must be replaceable.

Stack: **Java 25, Spring Boot 4.1, Maven, JUnit 5**, with Spring AI 2.0 for the MCP server (`@McpTool` annotations) and the MCP Java SDK for the client that talks to Jenkins. (Decided by you; `CLAUDE.md` says "Java (17+)", which this satisfies.) It is built with JDK 25. The default build targets Java 25 (`target/application.jar`); the Maven profile `java17` builds a second jar for machines with only Java 17 (`target/java17/application-java17.jar`). Both come from the same source, which therefore uses only Java 17 APIs (no virtual threads).

"Startup error" in this document means: the problem is logged as an ERROR, the affected server is not used, and `status` reports it as CRITICAL. The application itself keeps running, because it must be able to say what is wrong. The only exception is a second instance on the same storage folder, which refuses to start.

## 2. Phases

| Phase | Content |
|---|---|
| **1** | Configuration, Jenkins gateway, rules (`addRule`/`removeRule`/`listRules`), collector, cursor, `jobs.json`, PENDING notification records (no delivery), tools `getRecentJobs`, `getNotifications`, `status`, retention cleanup, debug logging |
| **2** | Notification delivery (notifier scheduler, Microsoft Teams via Azure identity, retries, Azure-auth health check). Started once events exist in storage |
| **3** | LLM inside the application; RAG store for the in-memory tool catalog |
| **4** (optional) | Visualization of LTM data grouped by rule, for users of Claude Desktop, Codex Desktop and ChatGPT |

## 3. Components

| Component | Responsibility |
|---|---|
| MCP server | Exposes the tools in section 9 over Streamable HTTP |
| Jenkins gateway | The single class through which every call to a Jenkins MCP server passes (section 8) |
| Rule store | Rules held in memory, persisted to `rules.json` (section 6) |
| Collector | Scheduled task that finds new builds, matches rules, writes storage (section 7) |
| Storage | One JSON file per table under the storage folder (section 5) |
| Retention cleaner | Scheduled task that deletes old records (section 5.6) |
| Notifier | Scheduled task that delivers PENDING notifications — Phase 2 (section 11) |
| Health | Aggregates checks for the `status` tool (section 10) |

The collector, the retention cleaner and (in Phase 2) the notifier run on **separate threads** and never overlap with another run of themselves. [proposed]

## 4. Configuration

All configuration is in `application.properties`. Nothing is hardcoded. Profiles: the default profile and `debug` (`application-debug.properties`). Activate debug with `--spring.profiles.active=debug`.

Note: the project `CLAUDE.md` says `application.yml`. Your requirement (`application.properties`) is followed here.

### 4.1 Properties

| Property | Default | Notes |
|---|---|---|
| `server.address` | `127.0.0.1` | |
| `server.port` | `2026` | |
| `server.shutdown` | `immediate` | Do not wait for connected MCP clients when stopping [proposed] |
| `files.root.folder` | `jenkins-monitoring-mcp` | Resolved **next to the jar file** (see 13.4) |
| `files.storage.path` | `${files.root.folder}/db` | |
| `retention.policy.days` | `30` | |
| `scheduling.jenkins-job-check.cron` | `0 */5 * * * *` | Your value `* */5 * * * *` fires every second during every 5th minute; corrected [proposed] |
| `scheduling.notifications.cron` | `*/30 * * * * *` | Phase 2 |
| `scheduling.retention-cleanup.cron` | `0 0 3 * * *` | [proposed] |
| `tools.recent-jobs.default-limit` | `20` | Property name [proposed] |
| `tools.notifications.max-results` | `200` | [proposed] |
| `collector.max-builds-per-job-per-cycle` | `20` | Throttles catch-up after downtime [proposed] |
| `collector.max-parameter-value-length` | `200` | Longer or multi-line parameter values are not stored [proposed] |
| `jenkins.build-tree` | see 8.3 | `tree` used for `getBuild` [proposed] |
| `jenkins.request-timeout` | `10s` | [proposed] |
| `jenkins.allowed-tools` | `getBuild,getJob,whoAmI,getStatus` | Allowlist, see 8.1 [proposed] |
| `jenkins.servers[n].url` | — | MCP endpoint of a Jenkins server; the server's identity is derived from it (see 4.3, 4.4) |
| `jenkins.servers[n].protocol` | `STREAMABLE` | Uppercase (see 4.3) |
| `jenkins.servers[n].auth` | — | **Name of the environment variable** that holds the Basic auth header value (see 4.3) |
| `logging.mask-parameter-pattern` | `(?i).*(password\|token\|secret\|key).*` | Parameter names whose values are masked [proposed] |
| `notifications.max-attempts` | `5` | Phase 2 [proposed] |

Debug profile only:

| Property | Value |
|---|---|
| `logging.file.name` | `${files.root.folder}/logs/console.log` |
| `logging.logback.rollingpolicy.max-file-size` | `10MB` |
| `logging.logback.rollingpolicy.max-history` | `1` (days; the default file pattern is date-based) |
| `logging.logback.rollingpolicy.total-size-cap` | `100MB` [proposed] — bounds disk to about 10 files without a custom `logback-spring.xml` |

### 4.2 Secrets

- Every secret has a property that only **references an environment variable**. Secret values are never written in a configuration file.
- A missing or malformed required environment variable is a startup error and is reported as CRITICAL by `status`. Only the variable's name is ever shown in messages, never its value.

### 4.3 Jenkins server configuration

The user can configure **any number of Jenkins servers** as **external configuration**: in `application.properties`, not in code and not baked into the jar. It must be possible to add a server without rebuilding the application. The configuration is read at startup, so adding or changing a server needs a restart [proposed]. The external `application.properties` is loaded from beside the jar (or from `--spring.config.additional-location`) and overrides packaged defaults [proposed].

Each server has exactly three settings:

| Setting | Value |
|---|---|
| `url` | Full MCP endpoint, e.g. `https://jenkins-server1.com/mcp-server/mcp` |
| `protocol` | `STREAMABLE`. Written in uppercase; the application also normalizes to uppercase when reading. Any other value is a startup error for that server [proposed] |
| `auth` | The **name of an environment variable**, e.g. `JENKINS_SERVER1_AUTH`. The variable is always an environment variable, for security reasons, and always holds Basic auth in the form `Basic <base64 secret>` |

Servers are a numbered list. A server has no separate name; its identity is its URL (section 4.4).

Example:

```properties
jenkins.servers[0].url=https://jenkins-server1.com/mcp-server/mcp
jenkins.servers[0].protocol=STREAMABLE
jenkins.servers[0].auth=JENKINS_SERVER1_AUTH

jenkins.servers[1].url=https://jenkins-server2.com/mcp-server/mcp
jenkins.servers[1].protocol=STREAMABLE
jenkins.servers[1].auth=JENKINS_SERVER2_AUTH
```

and, in the environment (the value is the complete `Authorization` header value; here `user:token` encoded in Base64):

```
JENKINS_SERVER1_AUTH=Basic dXNlcjp0b2tlbg==
```

Rules:

- The application reads the variable named in `auth` from the environment and sends its value **verbatim** as the `Authorization` header of every call to that server.
- Startup validation: the variable exists and matches `Basic <valid Base64>`. If not, that server is reported as CRITICAL (naming the variable, not its value) [proposed].
- The value is read from the **real operating-system environment** only (`System.getenv`). A property with the same name in `application.properties` or a `-D` option is not accepted, so the secret can neither sit in a file nor show in the process list.
- A literal value such as `Basic dXNl...` written in `auth` is **rejected** at startup, so plaintext secrets cannot be committed to the file [proposed].
- No servers configured is reported as CRITICAL [proposed].

### 4.4 Server identity

Servers are configured by their MCP URL, but everything else in the application identifies a server by its **canonical server URL**: `scheme://host[:port]`, taken from the configured MCP `url`.

- The MCP path (`/mcp-server/mcp`) is removed; the host is lowercase; there is no trailing slash; the default port (443 for `https`, 80 for `http`) is omitted. Example: the configured `https://jenkins-server1.com/mcp-server/mcp` gives `https://jenkins-server1.com`.
- **The canonical URL is the only form used wherever a value identifies just a server**: the `server` field in `rules.json`, `jobs.json`, notification files and `latest_jobs.json`, and the server shown in tool results, error messages, `status` checks and logs.
- **URLs that merely contain a server keep their original form.** Build URLs, such as `https://jenkins-server1.com/job/my-job/1018/`, are stored and returned exactly as Jenkins provides them: not truncated and not rewritten. This applies to `url` in `jobs.json`, notification files and `latest_jobs.json`, and to URLs in tool results.
- **Input is normalized.** When a user gives a server to `addRule` or `removeRule`, the application accepts `https://jenkins-server1.com`, `jenkins-server1.com`, a trailing slash, different letter case, or a full MCP URL. It matches by host (and port) and ignores scheme and path. The saved value is always the canonical URL of the matching configured server. The same normalization applies to rules read from a hand-edited `rules.json` at startup [proposed].
- No configured server matches: error listing the configured canonical URLs.
- Two configured servers with the same canonical URL are a startup error [proposed]. Limitation: Jenkins instances that share one host and differ only by context path cannot be told apart; if that becomes necessary, the identity must include the path.
- The canonical URL comes from the configuration, not from the build URLs in Jenkins responses, because Jenkins' configured root URL can differ from the MCP host [proposed].

## 5. Storage (long-term memory)

### 5.1 General rules

- One JSON file per table in `files.storage.path`.
- Every record has `created_at`, formatted `yyyy-MM-dd HH:mm:ss.SSS`, local time zone [proposed: milliseconds everywhere; see section 16].
- Whenever records are read from any file they are returned **ordered by `created_at`, oldest first**. Records with the same `created_at` keep insertion order [proposed].
- Files are pretty-printed JSON arrays [proposed]. Writes are crash-safe (write to a temporary file, then atomic replace) and thread-safe (one writer per file) [proposed].
- A lock file prevents two instances from using the same storage folder. Port 2026 alone would not stop a second instance on another port [proposed].
- The storage is replaceable: access goes through repository classes per table, so a database can replace the files later. No interface is introduced until a second implementation exists (project guideline).
- Console logs and full build responses are **never** stored. Only the necessary fields plus a few user-friendly extras.

### 5.2 `jobs.json`

One record per Jenkins build that matched a rule. Builds that match no rule are not saved. Unique key: `url`; inserting an existing `url` is a no-op.

| Field | Notes |
|---|---|
| `created_at` | Insertion time |
| `url` | Build URL exactly as returned by Jenkins (not normalized); unique identifier |
| `server` | Canonical server URL (section 4.4) |
| `job` | `fullName` of the job |
| `build_number` | |
| `status` | Normalized build result: `SUCCESS`, `FAILURE`, `UNSTABLE`, `ABORTED`, `UNKNOWN` |
| `triggered_by` | The cause's `userId` (`name.surname`) for user-started builds; otherwise the cause text, e.g. the GitLab merge request |
| `description` | Build description when present (often null) |
| `started_at` | Build start, from the epoch-millisecond `timestamp` |
| `duration` | Human-readable, e.g. `3m 5s` |
| `playbook` | Value of the `Playbook` parameter; absent when empty |
| `parameters` | Filtered build parameters (see 7.5) |
| `scm_branches` | Distinct branch names from `lastBuiltRevision`; informational |

Example:

```json
{
  "created_at": "2026-09-20 14:10:02.517",
  "url": "https://my-jenkins-server.com/job/iot-platform-deploy-dev/1018/",
  "server": "https://my-jenkins-server.com",
  "job": "iot-platform-deploy-dev",
  "build_number": 1018,
  "status": "FAILURE",
  "triggered_by": "artem.holovchenko",
  "started_at": "2026-09-20 14:04:51.865",
  "duration": "3m 5s",
  "playbook": "Azure Resources",
  "parameters": { "ENVIRONMENT": "DEV", "BRANCH": "develop", "REGION_NAME": "eastus" },
  "scm_branches": ["origin/develop"]
}
```

Status normalization: a string `result` is used as is. Any other shape (you left the failure "complex JSON" unspecified) is handled by a tolerant parser: search an object for a status, otherwise `UNKNOWN` plus a WARN log with **key names only, never values**.

### 5.3 `rules.json`

See section 6 for semantics. Structure:

| Field | Required | Notes |
|---|---|---|
| `id` | auto | Sequential number, unique, **never reused** |
| `created_at` | auto | |
| `server` | yes | Canonical server URL, e.g. `https://jenkins-server1.com` (section 4.4). Users may enter other forms; it is always saved in this one |
| `job` | yes | Exact `jobFullName` |
| `conditions` | no | Map of optional conditions; absent means every build of the job matches |

```json
[
  {
    "id": 1,
    "created_at": "2026-09-20 14:05:33.123",
    "server": "https://my-jenkins-server.com",
    "job": "iot-platform-deploy-dev",
    "conditions": {
      "playbook": ["Azure Resources", "project12"],
      "user": "artem.holovchenko",
      "status": "FAILURE",
      "ENVIRONMENT": "DEV"
    }
  },
  {
    "id": 2,
    "created_at": "2026-09-20 14:07:10.481",
    "server": "https://my-jenkins-server.com",
    "job": "Build_all_release-dev"
  }
]
```

The id counter is persisted in `sequences.json` (`{"rules": 2}`) so ids are never reused after a rule is removed [proposed].

### 5.4 `notification_{ruleId}.json`

One file per rule. One record per matching build. Unique key within the file: `url`.

| Field | Notes |
|---|---|
| `created_at` | |
| `rule_id` | |
| `url` | Build URL exactly as returned by Jenkins (not normalized) |
| `status` | `PENDING`, `DELIVERED`, `FAILED` |
| `server`, `job`, `build_number`, `build_status` | Copied for readability [proposed] |
| `attempts`, `last_error`, `delivered_at` | Added in Phase 2 [proposed] |

Phase 1 writes only `PENDING` records and never delivers them. Because a build matches at most one rule, there is no cross-rule duplicate to suppress.

### 5.5 `latest_jobs.json` (collector cursor)

One record per tracked (server, job), i.e. one per rule.

| Field | Notes |
|---|---|
| `created_at`, `updated_at` | `updated_at` [proposed] |
| `server` | Canonical server URL (section 4.4) |
| `job` | Added to your columns; needed for the key (server + job) |
| `build_id` | Highest build number seen, whether or not it matched |
| `url` | URL of that build exactly as returned by Jenkins (not normalized) |
| `open_builds` | Numbers of builds seen but still running; re-checked each cycle [proposed] |

### 5.6 Retention

- `retention.policy.days=30`, evaluated on `created_at`.
- Applies to `jobs.json` and every `notification_*.json`.
- `rules.json`, `latest_jobs.json` and `sequences.json` are never purged.
- The cleanup runs on `scheduling.retention-cleanup.cron`.
- The cursor makes re-reading old builds unnecessary, so purging does not cause duplicate events.

## 6. Rules

### 6.1 Definition

- A rule is identified by **server + job**. There is **one rule per (server, job)**.
- Everything else lives in `conditions`. Keys:
  - `user` — the Jenkins user who started the build, in the form `name.surname`.
  - `status` — build result.
  - `playbook` — the `Playbook` build parameter. Optional because not every job has it.
  - any other key — the name of a build parameter (e.g. `ENVIRONMENT`, `BRANCH`, `REGION_NAME`).
- A condition value is a string or a list of strings. A list means **any of**.

### 6.2 Matching

- All conditions must match (AND).
- Names and values are compared case-insensitively.
- A condition on a parameter the build does not have, or has empty, does not match.
- `user` is compared with the build's `triggered_by`. Builds without a user (for example GitLab webhook runs) never match a `user` condition.
- A rule with no conditions matches every build of the job.

### 6.3 Lifecycle

- Rules are read from `rules.json` **at startup** and held in memory.
- `addRule` and `removeRule` update memory and the file. Hand edits of the file require a restart. Before writing, the file is re-read if it changed on disk, so hand edits are not lost [proposed].
- On load, each rule's `server` is normalized to the canonical URL (section 4.4); the file is rewritten in that form on the next write [proposed].
- Invalid entries (missing `server` or `job`, a `server` that matches no configured server, an entry that cannot be read at all) are skipped **one by one** with a WARN and reported by `status` as PROBLEMS; the other rules stay in use. Skipped entries are written back unchanged whenever the file is saved, so `addRule` or `removeRule` never deletes them, and their ids are never handed out again [proposed].
- If the file as a whole cannot be read (not a JSON array, corrupt), no rule is loaded, `status` reports it, and `addRule` and `removeRule` refuse to run so the user's file is never overwritten. Once the file is fixed the next change reloads it [proposed].

### 6.4 `addRule` validation

Checks run in this order; the first failure returns an error response naming the check:

1. `server` and `job` are present and not blank. Otherwise: *"addRule requires both `server` and `job`. Rules by user alone are not supported. Example: `addRule(server="https://my-jenkins-server.com", job="iot-platform-deploy-dev", conditions={"user":"artem.holovchenko"})`"*.
2. The server matches a configured server, after normalization (section 4.4): `https://jenkins-server1.com` and `jenkins-server1.com` both resolve to `https://jenkins-server1.com`. The rule is saved with the canonical URL. If nothing matches, the error lists the configured canonical URLs.
3. The server is reachable and the credentials are accepted (`whoAmI`).
4. The job exists (`getJob`). "Not found" cannot be told apart from "no permission", so the message says "not found or not accessible".
5. Conditions are valid: every `user` value is `name.surname` (otherwise an error with an example such as `artem.holovchenko`); lists are not empty. Unknown condition keys are accepted as build parameter names, because they cannot be verified.
6. No rule exists for the same (server, job). Otherwise: an error saying the rule already exists, with the existing rule's id and conditions.

If Jenkins is unreachable (for example VPN off), the rule is **not** saved.

### 6.5 `removeRule(server, job)`

- Both parameters are required. `server` is normalized like in `addRule` (section 4.4), so `jenkins-server1.com` finds the rule saved for `https://jenkins-server1.com`. It needs no Jenkins connection, only the configuration.
- Deletes the rule from memory and `rules.json`, and the cursor row for that job. A later re-add starts from the latest build without a notification flood [proposed].
- History in `jobs.json` and the rule's notification file stay until retention removes them. The Phase 2 notifier ignores notifications whose rule no longer exists [proposed].
- Unknown (server, job): error "rule not found".
- To change a rule's conditions: `removeRule` then `addRule`.

## 7. Collector

### 7.1 Schedule and isolation

- Runs on `scheduling.jenkins-job-check.cron`. Separate thread; a run never overlaps the previous one.
- Each server is processed independently. An unreachable server never blocks the others.
- Tracked jobs are exactly the (server, job) pairs in rules.

### 7.2 Bootstrap

For a tracked job with no cursor row, the collector fetches the job's latest build, stores it as the cursor and **does not notify** about it or older builds [proposed]. History can be created only from builds that finish afterwards.

### 7.3 Each cycle, per tracked job

1. Request the cursor build (`getBuild` with the configured `tree`) and read `nextBuild` (`{number, url}` or null). Null means nothing new.
2. Otherwise fetch that next build.
   - If it is not final (`building` is true or `result` is null): add its number to `open_builds` and advance the cursor to it, so later builds are not blocked. Pipelines with approval steps can stay in progress for hours.
   - If it is final: evaluate the rule (section 6.2). On a match, insert into `jobs.json` and create a PENDING record in `notification_{ruleId}.json`.
3. Repeat from step 1 until `nextBuild` is null or `collector.max-builds-per-job-per-cycle` is reached.
4. Re-check every build in `open_builds`; when one becomes final, process it as in step 2 and remove it from the list.
5. **Write order:** save the job and notification first, update the cursor last. All inserts are idempotent by `url`, so a crash re-processes at most one build and creates no duplicates.

### 7.4 Failure handling

- **Cursor build no longer exists** (Jenkins discarded it; the response is "no results"): call `getJob`, then scan build numbers from cursor + 1 up to `nextBuildNumber` − 1, bounded by the per-cycle limit, skipping missing numbers. This replaces re-bootstrapping in the normal case. Only when `nextBuildNumber` is not above the cursor (the numbers went backwards, so the job was recreated) is the cursor re-bootstrapped from the latest build without notifying; `status` reports that as PROBLEMS until the next pass [proposed].
- **A rule removed during a pass** cannot get its cursor or records back: everything the collector writes for a rule goes through the rule service, which holds the rule lock and checks the rule still exists [proposed].
- **Tracked job stops resolving** (deleted or renamed): reported by `status` as PROBLEMS naming the rule [proposed].
- **Application crash:** acceptable. Recovery relies on the cursor and idempotent inserts.
- **Catch-up after downtime:** builds missed while the application was off are processed and produce notifications, throttled by the per-cycle limit [proposed].

### 7.5 Extraction from a build

- `triggered_by`: from `actions[].causes[]`. A user cause gives `userId`; other causes give `shortDescription`.
- `playbook`: the parameter named `Playbook`; an empty value counts as absent.
- `parameters` stored: string-like values only; values longer than `collector.max-parameter-value-length` or multi-line are skipped (they are typically JSON settings blobs); values of parameters whose names match `logging.mask-parameter-pattern` are masked [proposed].
- The full parameter list is used for rule matching in memory before filtering for storage.

## 8. Jenkins gateway

All calls to any Jenkins MCP server go through **one gateway class**. The collector, `addRule` validation and the health checks never use the MCP client directly. It provides:

### 8.1 Safety

- **Allowlist** of read-only tools: `getBuild`, `getJob`, `whoAmI`, `getStatus`. Any other tool name is refused. The four build-changing tools (`triggerBuild`, `rebuildBuild`, `replayBuild`, `updateBuild`) can never be called: they are refused by a fixed deny-list in the gateway even if someone adds them to `jenkins.allowed-tools`, and an error is logged at startup when that happens.
- **Mandatory `tree`** on `getBuild` and `getJob`. A call without it is refused. An unfiltered build response is about 190 KB.
- **Credentials**: the `Authorization` header value of each server (from the environment variable named in its `auth` setting, section 4.3), held only in the gateway. Never logged.
- **No passthrough**: Jenkins tools are not re-exposed to end users.
- Each Jenkins server should have a dedicated read-only service account (Overall/Read, Job/Read) whose Basic auth (user + API token) is supplied through the environment variable named in `auth`. Jenkins permissions are the real enforcement.

### 8.2 Robustness

- Timeouts (`jenkins.request-timeout`).
- Response envelope: every tool returns `{status, message, result}`. `COMPLETED` with no `result` (message "Search completed, but no results were found…") is normalized to **NotFound**, not to an error and not to an empty success.
- Connection or authentication failures are normalized to **Unavailable**.
- The `status` tool does not rely on remembered results: it probes every server live (`whoAmI`, `getStatus`), so it is always current. (The earlier idea of tracking the last success or failure per server was dropped as unneeded.)

### 8.3 Default `tree` for `getBuild` [proposed]

```
number,result,displayName,fullDisplayName,timestamp,duration,url,building,description,
nextBuild[number,url],
actions[causes[shortDescription,userId,userName],parameters[name,value],lastBuiltRevision[branch[name]]]
```

The property holds it as one line. `previousBuild` is not requested by the collector.

### 8.4 Tool discovery

At startup and on every reconnect, per server, the application calls the MCP `tools/list` and keeps the tool catalog (names, descriptions, input schemas) **in memory only**. It is lost on restart and rebuilt. The catalog is used to check that allowlisted tools exist and accept `tree`. Later, a RAG store may persist and reload it [deferred].

### 8.5 Debug logging

See section 12. The gateway is where outgoing calls are logged.

## 9. MCP tools

Exposed with `@McpTool` over Streamable HTTP. Read-only tools carry `readOnlyHint=true` so hosts can prompt less; `addRule` and `removeRule` do not. Tool names are [proposed].

| Tool | Parameters | Behavior |
|---|---|---|
| `addRule` | `server` (required; any form accepted, saved as the canonical URL), `job` (required), `conditions` (optional map) | Validates and stores a rule; returns the rule with its id or an error (6.4) |
| `removeRule` | `server` (required), `job` (required) | See 6.5 |
| `listRules` | — | Returns all rules |
| `getRecentJobs` | `limit` (optional, default `tools.recent-jobs.default-limit` = 20) | The newest `limit` records of `jobs.json`, returned oldest first [proposed] |
| `getNotifications` | `status` (optional) | Notification records with that status; all statuses when omitted. Capped by `tools.notifications.max-results`, with a "showing X of Y" note [proposed] |
| `status` | — | Server status, see section 10 |

Errors are returned as tool error responses with an explanation, not as exceptions.

## 10. Status and health checks

The `status` tool returns an overall status and the list of checks with their statuses.

Overall status: **OK** ("everything ok"), **PROBLEMS** ("some problems"), **CRITICAL** ("critical error"). It is the worst status of all checks. Implemented as a plain service without Spring Boot Actuator, which would only add a dependency for something this small; the Jenkins checks run in parallel so one dead server does not delay the answer.

| Check | Result |
|---|---|
| All Jenkins hosts resolve (DNS) | Any unresolved host → **CRITICAL** (typically VPN off) |
| Jenkins MCP servers reachable | Some unavailable → **PROBLEMS** ("servers available partially"); all unavailable → **CRITICAL** [proposed] |
| Credentials accepted (`whoAmI`) | Rejected → PROBLEMS, per server [proposed] |
| Jenkins `getStatus` | Quiet Mode on, non-empty administrative monitors, or Root URL Status not OK → PROBLEMS [proposed] |
| Storage files readable, writable and valid JSON | Failure → CRITICAL [proposed] |
| Each server's `auth` environment variable exists and has the form `Basic <base64>` | Missing or malformed → CRITICAL for that server [proposed] |
| Scheduler heartbeat | Collector has not completed a run within twice its interval → PROBLEMS [proposed] |
| Rules | Invalid rule skipped, tracked job not resolving, cursor re-bootstrapped → PROBLEMS [proposed] |
| Notification Azure authentication (Phase 2) | Cannot authenticate → **PROBLEMS**, not critical; the user can still read events by asking this application |

## 11. Notifications — Phase 2 [deferred]

Decisions so far, to be revisited once events exist in storage:

- A separate scheduler on `scheduling.notifications.cron` (every 30 seconds) delivers PENDING notifications and sets `DELIVERED` or `FAILED`.
- The channel is replaceable: at least Microsoft Teams and a file channel (the file channel also allows testing without Azure) [proposed].
- Teams authentication: `InteractiveBrowserCredential` (opens a browser at startup), falling back to `AzureCliCredential`. Use `com.azure:azure-identity` with the Microsoft Graph SDK; `spring-cloud-azure-starter-active-directory` is for web-app login and is not the right library. Persist the token cache so restarts do not reopen the browser.
- Open questions: the Teams target (a message to yourself through Graph, or a Workflows webhook) and whether the tenant allows the needed delegated Graph permission (e.g. `ChatMessage.Send`). A short spike should prove that a message can be sent before the rest is built.
- Retries: `notifications.max-attempts=5`. If authentication fails, notifications stay PENDING without using attempts, and authentication is retried each cycle [proposed].
- After a crash between "sent" and "marked delivered", the notification is re-sent; a rare duplicate is accepted.
- One notification per build. Because a build matches at most one rule, no cross-rule suppression is needed.

## 12. Logging

- Default profile: console logging only.
- `debug` profile: log file at `${files.root.folder}/logs/console.log`; rotation 10 MB per file; `max-history=1` day; `total-size-cap=100MB` (section 4.1).
- In the `debug` profile **every MCP tool call is logged with its parameters and the full response body, untruncated**. By default this covers both directions: calls the collector makes to Jenkins (OUT) and calls users' clients make to this server (IN) [proposed]. A body that is not JSON cannot be masked and is logged as it is; Jenkins answers with JSON, and credentials are never part of a body. Outgoing calls are logged in the gateway; incoming calls by a small hook around the tool methods.
- Always, in every profile: credentials and Authorization headers are never logged; values of parameters whose names match `logging.mask-parameter-pattern` are masked.
- Debug logs can contain parameter blobs such as application-settings JSON, so `debug` stays opt-in and the logs stay local next to the jar.
- Outside `debug`, response bodies are not logged.

## 13. Security and non-functional requirements

1. **Localhost only**: bind `127.0.0.1`. Validate the `Origin` header on the Streamable HTTP endpoint, as the MCP specification asks, to block DNS-rebinding from web pages: an `Origin` that is not localhost, 127.0.0.1 or [::1] is refused with 403, and so is a `Host` header that does not name a local address (a rebinding page cannot fake it). Only `localhost`, `127.0.0.1` and `[::1]` are accepted, so a client that reaches the server through another name (for example `host.docker.internal` from a container) is refused. No authentication for the application's own clients (accepted for a local-only tool).
2. **Least privilege upstream**: read-only Jenkins accounts, tool allowlist, mandatory `tree`, no passthrough (section 8).
3. **Secrets** only through environment variables; nothing sensitive in files, in `rules.json`, or in logs.
4. **Root folder next to the jar**: `files.root.folder` is resolved against the directory that contains the jar at startup (working directory when run from an IDE) and used for both storage and logs. Spring resolves relative paths against the working directory by default, so this needs explicit handling. Placeholders use `${...}` syntax. The default folder name is spelled `jenkins-monitoring-mcp` [proposed].
5. **Threads**: schedulers on separate threads; no overlapping runs of the same task.
6. **Single instance** per storage folder (lock file).
7. **Windows and Linux**: paths and commands must work on both (see `CLAUDE.md`).
8. **Endpoint path**: `/mcp` [proposed].
9. Project workflow from `CLAUDE.md`: after each change, run the code and QA reviews and fix blocking issues.

## 14. Phase 4 (optional): visualization

Show LTM data grouped by rule to users of Claude Desktop, Codex Desktop and ChatGPT. The data model already supports it: each rule has its own notification file and `jobs.json` records join to rules through (server, job). Mechanism not decided.

## 15. Facts observed in the Jenkins MCP samples

Source: `jenkins-mcp-docs/`.

- Tools: `findJobsWithScmUrl`, `getBuild`, `getBuildChangeSets`, `getBuildLog`, `getBuildScm`, `getFlakyFailures`, `getJob`, `getJobScm`, `getJobs`, `getQueueItem`, `getReplayScripts`, `getStatus`, `getTestResults`, `rebuildBuild`, `replayBuild`, `searchBuildLog`, `triggerBuild`, `updateBuild`, `whoAmI`. Build-changing: `rebuildBuild`, `replayBuild`, `triggerBuild`, `updateBuild`.
- Parameters used: `getBuild(jobFullName, buildNumber?, tree?)`, `getJob(jobFullName, tree?)`, `whoAmI()`, `getStatus()`. There is no tool that lists a job's builds; builds are walked through `nextBuild` and `previousBuild`. Without `buildNumber`, `getBuild` returns the latest build. `getJobs` and `findJobsWithScmUrl` return at most 10 items per call.
- Envelope: `{status: "COMPLETED", message, result}`. "No results" also returns `COMPLETED`, with no `result` field.
- `getBuild` fields used: `number`, `result` (string; null while running), `building`, `timestamp` (epoch ms), `duration` (ms), `url`, `description`, `nextBuild` (`{number, url}` or null), `actions[]`.
- Manual builds carry a cause with `userId` (`name.surname`) and `userName`. Webhook builds carry only a `shortDescription`.
- Build parameters are in `actions[].parameters[]` as `{name, value}` (works through `tree`, e.g. `number,actions[parameters[name,value]]`). `Playbook` is one such parameter; it can be empty, and its values are free text (e.g. `Azure Resources`). There is no server-side playbook search, so playbook filtering is local.
- Job names can contain spaces or folders; URLs are percent-encoded.
- Pipelines can wait for approval (`InputAction`), so builds can be in progress for hours.

## 16. Defaults to confirm

Everything tagged [proposed] stands unless you object. The main ones:

1. Cron `0 */5 * * * *` instead of `* */5 * * * *`.
2. `created_at` with milliseconds in every file, local time, ties by insertion order (your text mixed milliseconds, seconds and minutes).
3. Rule ids never reused, via `sequences.json`.
4. Separate scheduler threads, no overlapping runs.
5. Root folder resolved next to the jar.
6. Retention cleanup daily at 03:00; scope as in 5.6.
7. Bootstrap without notifying about builds that exist when a rule is created.
8. Only finished builds are processed; running builds tracked in `open_builds`.
9. Catch-up after downtime notifies, throttled to 20 builds per job per cycle.
10. Health aggregation and the extra health checks in section 10.
11. Pretty-printed JSON arrays, atomic writes, lock file.
12. Endpoint path `/mcp`; property and tool names as listed.
13. Java 25, Spring Boot 4.1, Spring AI 2.0, Maven (your decision).
14. Extra fields: `open_builds`, `updated_at`, `job` in `latest_jobs`, denormalized fields in notification records.
15. Parameter value length limit 200; deny-pattern masking.
16. Request timeout 10 s.
17. `removeRule` keeps history, deletes the cursor.
18. `rules.json` reloaded before a write if it changed on disk.
19. `getRecentJobs` returns the newest N in chronological order.
20. Debug logging covers both directions.
21. Rotation via `total-size-cap=100MB`.
22. Servers are configured as a numbered list, `jenkins.servers[n].url|protocol|auth`, with no separate server name. A server's identity is its canonical URL (`scheme://host[:port]`).
23. Server input is matched by host and port, ignoring scheme and path; the canonical URL of the matching configured server is what gets saved.
24. Two configured servers with the same canonical URL are a startup error; servers that differ only by context path are not supported.
25. The canonical URL comes from the configuration, not from build URLs in Jenkins responses. Build URLs themselves are stored as returned, never truncated.
26. `auth` holds the environment variable's **name**; a literal `Basic ...` value in the file is rejected at startup.
27. `protocol` is normalized to uppercase; anything other than `STREAMABLE` is a startup error for that server.
28. Configuration is read at startup, so server changes need a restart; the external `application.properties` is loaded from beside the jar.
29. Zero configured servers is CRITICAL.

## 17. Open and deferred items

- Notification delivery (section 11): Teams target, Entra app registration, permission spike.
- LLM inside the application, and the RAG store for the tool catalog.
- Failure `result` format ("complex JSON") — handled by the tolerant parser until a sample exists.
- Error responses for unauthorized or unreachable servers are unknown; treated as **Unavailable**.
- Whether `getBuild` without `buildNumber` can return a running build.
- Visualization mechanism (Phase 4).

## 18. Implementation status

**Phase 1 is implemented** and covered by unit tests (collector, rules, storage, parsing, server configuration) and by an end-to-end test. The end-to-end test starts the real application and runs a fake Jenkins MCP server inside it, so the real Streamable HTTP client, tool scanning, the `Authorization` header, the Origin check and all six tools are exercised over HTTP. The packaged jar was also started and called with curl.

Decisions taken during implementation:

- `addRule` and `removeRule` do not mark `server` and `job` as required in the tool schema. A request that lacks them must reach the tool and get the explanation with an example, not be rejected by the schema check. The descriptions say REQUIRED.
- Tool results are JSON text with snake_case names and LF line endings. Errors are tool errors (`isError: true`) with a readable message.
- The Jenkins gateway reads a tool answer from `structuredContent` if present, otherwise from the first text content, and expects the `{status, message, result}` envelope.
- At startup the application connects to each Jenkins server in the background to learn its tools; a server that is down at that moment is retried on its first real call.
- `status` also checks that every allowlisted tool is offered by each server (the tool catalog learned at connection), and repairs a missing storage folder by creating it.
- The web server shuts down immediately (`server.shutdown=immediate`): a connected MCP client holds a stream open, and waiting for it (the Spring default is 30 seconds) would only delay stopping the application.
- The gateway distinguishes a protocol error answered by the server (kept connection, reported as a tool error) from a connection failure (connection dropped, reported as unavailable), so one bad request cannot make the whole server look down.

**Not yet verified against a real Jenkins MCP server** (only against the fake and the recorded samples). Check on the first real run:

1. The tool answer really arrives as JSON text in the envelope shape.
2. `getBuild` accepts the configured `tree`, including `actions[parameters[name,value]]`.
3. What an unauthorized or unknown-job call returns (mapped to Unavailable / NotFound by assumption).
4. Whether a running build reports `building: true` with `result: null`.

**Not built yet:** Phase 2 (notification delivery), Phase 3, Phase 4.
