# Jenkins Monitoring MCP

A local MCP server that watches builds on several Jenkins servers. You tell it which jobs to watch (and, optionally, which builds matter); it finds new builds, keeps their history in plain JSON files and prepares notifications. You talk to it from Claude Desktop, Codex or any other MCP client.

The full design is in [REQUIREMENTS.md](REQUIREMENTS.md). This page is the short version.

## Build and run

Build with JDK 25. There are two builds, made from the same source:

| Command | Jar | Runs on |
|---|---|---|
| `./mvnw clean package` | `target/mcp-jenkins-monitoring.jar` | Java 25 |
| `./mvnw package -Pjava17` | `target/java17/mcp-jenkins-monitoring-java17.jar` | Java 17 or newer |

The `java17` profile compiles for Java 17 (the compiler rejects any newer API) and writes to its own folder, so both jars can exist side by side. On Windows PowerShell use `.\mvnw`. Each build runs the tests; `./mvnw test` runs only the tests.

Run it:

```bash
java -jar target/mcp-jenkins-monitoring.jar                       # Java 25
java -jar target/java17/mcp-jenkins-monitoring-java17.jar         # a machine with Java 17
```

It listens on `http://127.0.0.1:2026/mcp` (Streamable HTTP, local only, no authentication). To see every MCP call and its full response in a log file, start it with the debug profile:

```bash
java -jar target/mcp-jenkins-monitoring.jar --spring.profiles.active=debug   # same for the Java 17 jar
```

Data and logs go to a `jenkins-monitoring-mcp` folder **next to the jar**: `db/` holds the JSON files (`rules.json`, `jobs.json`, `latest_jobs.json`, `notification_<rule id>.json`), `logs/` holds the debug log. Records older than 30 days are deleted (`retention.policy.days`).

## Configure the Jenkins servers

Put an `application.properties` next to the jar. Add one block per server:

```properties
jenkins.servers[0].url=https://jenkins-server1.com/mcp-server/mcp
jenkins.servers[0].protocol=STREAMABLE
jenkins.servers[0].auth=JENKINS_SERVER1_AUTH
```

`auth` is the **name of an environment variable**, never the credentials. The variable holds the Basic auth header value, `Basic <base64 of user:apiToken>`:

```bash
export JENKINS_SERVER1_AUTH="Basic $(printf 'user:apiToken' | base64)"
```

Use a Jenkins account that can only read (Overall/Read, Job/Read). The application only ever calls the read-only tools `getBuild`, `getJob`, `whoAmI` and `getStatus`.

## Connect a client

Add an MCP server of type "Streamable HTTP" with the URL `http://127.0.0.1:2026/mcp`. The tools:

| Tool | What it does |
|---|---|
| `addRule` | Watch a job. `server` and `job` are required; `conditions` narrows which builds count, for example `{"user": "artem.holovchenko", "status": "FAILURE", "playbook": ["a", "b"]}` |
| `removeRule` | Stop watching a job (`server` and `job`) |
| `listRules` | All rules |
| `getRecentJobs` | The newest builds that matched a rule (default 20) |
| `getNotifications` | Notifications by status (`PENDING`, `DELIVERED`, `FAILED`); all when omitted |
| `status` | `OK`, `PROBLEMS` or `CRITICAL`, with the checks behind it (Jenkins host names and VPN, reachability, storage, rules, scheduler) |

A server can be given as `https://jenkins-server1.com`, `jenkins-server1.com` or the full MCP URL; it is always stored as `https://jenkins-server1.com`.

## Status

Phase 1 (rules, collector, history, tools, status, retention) is implemented and tested. Notification delivery (Microsoft Teams) is Phase 2 and not built yet: notifications are written with status `PENDING`. It has not yet been run against a real Jenkins MCP server; see section 18 of the requirements for what to check first.
