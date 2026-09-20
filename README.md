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
jenkins.server[0].url=https://jenkins-server1.com/mcp-server/mcp
jenkins.server[0].protocol=STREAMABLE
jenkins.server[0].auth=JENKINS_SERVER1_AUTH
```

`auth` says where the credentials are. They are `Basic <base64 of user:apiToken>`, and they are never written in a file, so in a properties file `auth` is the **name of an environment variable** that holds them:

```bash
export JENKINS_SERVER1_AUTH="Basic $(printf 'user:apiToken' | base64)"
```

### Servers from environment variables instead

Instead of a file, the same settings can come from environment variables. Here the credentials can go straight into `..._AUTH`. Spring Boot turns `jenkins.server[0].url` into `JENKINS_SERVER_0_URL`, and so on:

```bash
export JENKINS_SERVER_0_URL=https://jenkins-server1.com/mcp-server/mcp
export JENKINS_SERVER_0_AUTH="Basic $(printf 'user:apiToken' | base64)"   # the credentials themselves
export JENKINS_SERVER_1_URL=https://jenkins-server2.com/mcp-server/mcp
export JENKINS_SERVER_1_AUTH="Basic $(printf 'user2:apiToken2' | base64)"
```

`JENKINS_SERVER_n_AUTH` may hold the credentials directly, as above, or the name of another variable that holds them (`JENKINS_SERVER_0_AUTH=JENKINS0_CREDENTIALS` and `JENKINS0_CREDENTIALS="Basic ..."`). The credentials themselves are accepted only when they come from an environment variable: `jenkins.server[0].auth=Basic ...` in a properties file is refused, so a secret cannot be committed by mistake. The error never shows the value.

Note the singular `server`. The numbers only have to be different from each other; gaps are fine. Servers from a file and from environment variables are combined, so some can be in the file and others in the environment. If the same number is given in both, the environment variable wins for that setting.

Every server setting needs its number: `JENKINS_SERVER_0_URL`, not `JENKINS_SERVER_URL`. A setting without a number (`jenkins.server.url`) stops the application at startup with `failed to convert java.lang.String to java.lang.Integer ... "url"`; add the number.

### What the startup log says

At startup the application logs every server it found and where each came from, before it connects to anything. Credentials are never logged, only the name of the variable that holds them:

```
Jenkins servers in the configuration: 2 (1 in use, 1 not usable)
  jenkins.server[0] = https://jenkins-server1.com (MCP endpoint /mcp-server/mcp, protocol STREAMABLE), url from URL [file:C:/app/application.properties] - 1:23, credentials from environment variable JENKINS_SERVER1_AUTH
  jenkins.server[1] = https://jenkins-server2.com is NOT USED: environment variable JENKINS_SERVER2_AUTH is not set (url from System Environment Property "JENKINS_SERVER_1_URL")
```

If nothing is configured, it logs a warning that explains how to add a server. The `status` tool shows the same problems.

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
