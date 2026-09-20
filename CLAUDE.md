# Project Guidelines



## Stack

Java (17+), Spring Boot, Maven, JUnit 5, AI/LLM integrations.



## Engineering approach

- Write code like an experienced engineer: correct first, then clear, then clever only when necessary.

- Apply design patterns (Strategy, Factory, Builder, etc.) and architecture patterns (layered, hexagonal, etc.) only when they solve a real problem in this codebase today. Do not add patterns, abstractions, or interfaces "just in case."

- Keep code simple. Prefer the plainest solution that works: no interface with a single implementation, no generic framework for a one-off need, no speculative extension points.

- Follow existing conventions in the surrounding code before introducing new ones.

- Small, focused methods and classes; meaningful names; comments explain "why", not "what."



## Spring Boot conventions

- Constructor injection only (no field @Autowired).

- Keep controllers and `@McpTool` methods thin; business logic in services; persistence in repositories.

- Validate tool input in the `@McpTool` methods and return errors as tool error responses.

- Configuration via application.properties and @ConfigurationProperties, never hardcoded.



## AI/LLM code

- Isolate model/provider calls behind a small service so they can be swapped and mocked.

- Never log secrets, API keys, or full user prompts containing sensitive data.

- Handle timeouts, retries, and malformed model output explicitly.



## Commands Java

- Run program: `java -jar target/mcp-jenkins-monitoring.jar` or use full path to the jar file

## Command Maven

For Windows use the Maven wrapper. Prefix it with the path: `.\mvnw` in PowerShell, `./mvnw` in Git Bash (plain `mvnw` works only in cmd.exe).

Windows examples:

- Build: `.\mvnw -q compile`

- Test: `.\mvnw test`

- Full check: `.\mvnw verify`

For Linux use Maven 'mvn'

Linux examples:

- Build: `mvn -q compile`

- Test: `mvn test`

- Full check: `mvn verify`



## Workflow

After implementing any change, invoke `code-reviewer` and `qa-reviewer`.

Fix all blocking issues and re-run both. Stop after 3 rounds or when both score 4+.

