# kotlin-agent

`kotlin-agent` is a Kotlin/JVM coding agent CLI that runs locally in your workspace, uses an OpenAI-compatible `/chat/completions` backend, and requires approval before mutating actions.

```shell
./gradlew installAgent
scripts/install-cli.sh
agent
```

Set `OPENAI_API_KEY` before launching `agent`, or provide `apiKey` in config. If `~/bin` is not on your `PATH`, add `export PATH="$HOME/bin:$PATH"`.

## What It Does

- Runs in the current directory by default, or against an explicit workspace with `--workspace`.
- Auto-approves read-only tools and requires approval for mutating tools.
- Persists sessions, approvals, and artifacts in `.agent/`.
- Streams model responses through an OpenAI-compatible `/chat/completions` provider.
- Ships with `list_files`, `read_file`, `search_files`, `run_shell`, and `apply_patch`.

## Quickstart

- `agent` starts the REPL in the current directory.
- `agent --workspace /path/to/repo` starts the REPL for an explicit workspace.
- `agent resume <session-id>` resumes a saved session and enters the REPL.
- `agent status [session-id]` prints recent sessions or one specific session.
- `agent tools` lists the available tools.
- `agent version` prints the packaged version.

REPL mode requires an interactive terminal. In non-interactive environments, use commands such as `agent status` and `agent tools`.

## Configuration

The `agent` CLI reads configuration in this order:

1. Environment variables: `OPENAI_API_KEY`, `OPENAI_BASE_URL`, `AGENT_MODEL`
2. Workspace config: `.agent/config.properties`
3. Global config: `~/.config/agent/config.properties`

Default values:

- `baseUrl=https://api.openai.com/v1`
- `model=gpt-4.1-mini`

Example `config.properties`:

```properties
baseUrl=https://api.openai.com/v1
model=gpt-4.1-mini
apiKey=your-api-key
```

## Project Layout

- `agent-core`: domain model, orchestration loop, approvals, session persistence, and SQLite-backed state.
- `agent-provider-openai`: OpenAI-compatible streaming provider adapter.
- `agent-tools-local`: local filesystem, search, shell, and patch tools.
- `agent-cli`: terminal UI, command parsing, config loading, and engine wiring.

## Build

- Requires JDK 17+ to build locally.
- Run `./gradlew test verifyAgentInstallDist` to execute the test suite and verify the generated launcher.
- `scripts/install-cli.sh` installs the distribution under `~/.local/share/agent/<version>` and symlinks `~/bin/agent`.

Licensed under the [MIT License](LICENSE).
