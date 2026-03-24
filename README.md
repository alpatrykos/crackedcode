# CrackedCode

CrackedCode is a Kotlin/JVM coding agent CLI that runs locally in your workspace, uses an OpenAI-compatible `/chat/completions` backend, and requires approval before mutating actions.

```shell
./gradlew installCcode
scripts/install-cli.sh
ccode
```

Set `OPENAI_API_KEY` before launching `ccode`, or provide `apiKey` in config. If `~/bin` is not on your `PATH`, add `export PATH="$HOME/bin:$PATH"`.

## What It Does

- Runs in the current directory by default, or against an explicit workspace with `--workspace`.
- Auto-approves read-only tools and requires approval for mutating tools.
- Persists sessions, approvals, and artifacts in `.crackedcode-agent/`.
- Streams model responses through an OpenAI-compatible `/chat/completions` provider.
- Ships with `list_files`, `read_file`, `search_files`, `run_shell`, and `apply_patch`.

## Quickstart

- `ccode` starts the REPL in the current directory.
- `ccode --workspace /path/to/repo` starts the REPL for an explicit workspace.
- `ccode resume <session-id>` resumes a saved session and enters the REPL.
- `ccode status [session-id]` prints recent sessions or one specific session.
- `ccode tools` lists the available tools.
- `ccode version` prints the packaged version.

REPL mode requires an interactive terminal. In non-interactive environments, use commands such as `ccode status` and `ccode tools`.

## Configuration

CrackedCode reads configuration in this order:

1. Environment variables: `OPENAI_API_KEY`, `OPENAI_BASE_URL`, `CRACKEDCODE_MODEL`
2. Workspace config: `.crackedcode-agent/config.properties`
3. Global config: `~/.config/ccode/config.properties`

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
- Run `./gradlew test verifyCcodeInstallDist` to execute the test suite and verify the generated launcher.
- `scripts/install-cli.sh` installs the distribution under `~/.local/share/ccode/<version>` and symlinks `~/bin/ccode`.

Licensed under the [MIT License](LICENSE).
