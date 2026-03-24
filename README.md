# CrackedCode

Kotlin(JVM) coding agent with a reusable core, a local tool runtime, and an interactive CLI.

## Modules

- `agent-core`: domain model, orchestration loop, approvals, and session persistence.
- `agent-provider-openai`: OpenAI-compatible streaming provider adapter.
- `agent-tools-local`: local filesystem, search, shell, and patch tools.
- `agent-cli`: interactive terminal application.

## Quickstart

1. `./gradlew test`
2. Set `OPENAI_API_KEY`
3. Optionally set `OPENAI_BASE_URL` and `CRACKEDCODE_MODEL`
4. `./gradlew :agent-cli:run`
