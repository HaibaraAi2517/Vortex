# Real Model Agent Recovery Demo

This example runs a real OpenAI-compatible chat model through LangChain4j,
requires the model to call read-only tools, injects durable Vortex memory, then
creates a Vortex task checkpoint. The orchestrator kills the phase-one process
tree and starts a new JVM that recovers the checkpoint and continues the task.
After recovery, the demo opens a live console where every user question is
answered by the recovered Agent and persisted as another checkpoint.

It demonstrates five separate behaviors:

1. A fresh model conversation does not know private demo facts.
2. Vortex recalls those facts and injects them before model generation.
3. The model calls real read-only tools for Git, Vortex health, and recovered task state.
4. A new process recovers the checkpointed task and appends phase two instead of repeating phase one.
5. The user can continue talking to the recovered Agent while task nodes and checkpoint IDs visibly increase.

## Prerequisites

- Java 21 and Maven 3.9+
- Docker Compose v2 and at least 6 GB available memory when using `-StartQuickstart`
- Bash, curl, and OpenSSL for the Linux/macOS script
- An OpenAI-compatible model that supports tool calling
- A model API key; local endpoints may accept a placeholder value such as `ollama`

Known compatible configurations include OpenAI, DeepSeek, and OpenAI-compatible
Ollama models with tool support. Model behavior still depends on the selected
provider and model.

## Fastest DeepSeek Demo On Windows

No environment variable setup is required. Either double-click:

```text
examples\real-agent-demo\run-deepseek.cmd
```

Or run this command from the repository root:

```powershell
.\examples\real-agent-demo\run-deepseek.ps1
```

The launcher securely prompts for the DeepSeek API key without displaying it or
writing it to disk. It then:

1. Generates temporary Vortex credentials.
2. Builds and starts Vortex from the current source checkout.
3. Runs the real-model memory and tool comparison.
4. Kills the first Agent JVM and visibly recovers its checkpoint in a new JVM.
5. Opens an interactive `YOU >` prompt for live questions.
6. Stops the Quickstart containers after `/exit`.
7. Restores the PowerShell process environment to its original values.

`-BuildCurrentSource` is retained as a compatibility flag; the launcher now
always builds the current source because the published GHCR image may be
unavailable:

```powershell
.\examples\real-agent-demo\run-deepseek.ps1 -BuildCurrentSource
```

Keep the Quickstart stack running after the demo for inspection:

```powershell
.\examples\real-agent-demo\run-deepseek.ps1 -KeepQuickstart
```

## Advanced Windows Usage

The provider-neutral runner also prompts for the key when `MODEL_API_KEY` and
`-ModelApiKey` are both absent:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass `
  -File .\examples\real-agent-demo\run.ps1 `
  -StartQuickstart `
  -UsePublishedImage
```

OpenAI example:

```powershell
$env:MODEL_API_KEY = "your-api-key"
$env:MODEL_BASE_URL = "https://api.openai.com/v1"
$env:MODEL_NAME = "gpt-4.1-mini"
.\examples\real-agent-demo\run.ps1 -StartQuickstart
```

Local Ollama example, after pulling a tool-capable model:

```powershell
$env:MODEL_API_KEY = "ollama"
$env:MODEL_BASE_URL = "http://127.0.0.1:11434/v1"
$env:MODEL_NAME = "qwen2.5:7b"
.\examples\real-agent-demo\run.ps1 -StartQuickstart
```

When Vortex is already running, omit `-StartQuickstart` and provide its token:

```powershell
$env:VORTEX_SECURITY_BEARER_TOKEN = "your-vortex-token"
$env:MODEL_API_KEY = "your-model-key"
.\examples\real-agent-demo\run.ps1
```

## One Command On Linux Or macOS

```bash
export MODEL_API_KEY="your-api-key"
START_QUICKSTART=true bash examples/real-agent-demo/run.sh
```

Override the provider with `MODEL_BASE_URL` and `MODEL_NAME` as needed.

## Expected Evidence

The output is divided into five large numbered sections. It includes this
evidence before opening the live prompt:

```text
WITHOUT VORTEX MEMORY:
...
TOOL CALL: inspectRepository
TOOL CALL: inspectVortexHealth
VORTEX RECALL:
... Aurora Ledger ... Lin-7 ...
CHECKPOINT READY: taskId=..., checkpointId=..., nodeCount=1
Checkpoint is durable. Terminating phase-one process tree PID ...
RECOVERED: taskId=..., checkpointId=..., nodeCount=1
TOOL CALL: inspectRecoveredTask
TOOL CALL: inspectRepository
FINAL TASK: status=COMPLETED, nodeCount=2, resumedCheckpointId=..., finalCheckpointId=...
[SUCCESS] DEMO COMPLETE: real model + tools + memory + crash recovery + live interaction.
```

## Live Interaction

After checkpoint recovery, the new Agent process displays:

```text
==============================================================================
 [5/5] Interact with the recovered Agent
==============================================================================
YOU >
```

Enter a normal question to talk to the recovered Agent. Useful examples:

```text
What is the release codename and approval owner?
Prove that this is a recovered task instead of a fresh task.
Inspect the repository and tell me the current commit.
```

Every normal question produces a visible `RECOVERED AGENT` answer, appends a
new Vortex task node, and prints a `CHECKPOINT PERSISTED` panel containing the
new checkpoint ID and node count.

The console also supports:

| Command | Result |
| --- | --- |
| `/status` | Read the current Vortex task status, node count, and checkpoint ID |
| `/memory` | Inspect the durable private facts directly |
| `/help` | Show commands and a suggested question |
| `/exit` | Complete the task and stop the one-click demo |

Use `-NonInteractive` with `run.ps1` for automation. That mode still runs and
verifies the complete crash recovery flow but does not wait at `YOU >`.

The provider-neutral `run.ps1` script intentionally leaves the Quickstart stack
running. Stop it from the repository root when inspection is complete. The
DeepSeek launcher stops it automatically unless `-KeepQuickstart` is used:

```powershell
docker compose -f docker-compose.quickstart.yml down
```

## Configuration

| Variable | Default | Purpose |
| --- | --- | --- |
| `MODEL_API_KEY` | prompted when absent | Model provider credential |
| `MODEL_BASE_URL` | `https://api.deepseek.com/v1` | OpenAI-compatible API base URL |
| `MODEL_NAME` | `deepseek-chat` | Tool-capable chat model |
| `MODEL_TIMEOUT_SECONDS` | `120` | Individual model call timeout |
| `VORTEX_BASE_URL` | `http://127.0.0.1:8080` | Vortex REST base URL |
| `VORTEX_SECURITY_BEARER_TOKEN` | required unless the script starts Quickstart | Vortex API credential |
| `VORTEX_NAMESPACE` | generated `quickstart-real-agent-*` value | Isolated demo namespace |
| `DEMO_INTERACTIVE` | `true` in an interactive launcher | Enable the recovered Agent live console |

No credential is written to the repository or the checkpoint handoff file.
The handoff file contains only a run ID, namespace, task ID, checkpoint ID, and
the first node ID, and is removed when the script exits.

## API Surface Used

- `GET /actuator/health`
- `POST /api/v1/memory/store`
- `POST /api/v1/memory/recall`
- `POST /api/v1/tasks`
- `GET /api/v1/tasks/{taskId}`
- `POST /api/v1/tasks/{taskId}/nodes`
- `POST /api/v1/tasks/{taskId}/nodes/complete`
- `POST /api/v1/tasks/{taskId}/checkpoint`
- `POST /api/v1/tasks/{taskId}/recover`
- `POST /api/v1/tasks/{taskId}/complete`

Task mutations use stable `X-Execution-Id` values for this run. The example
demonstrates deterministic recovery within Vortex's documented single-runtime
boundary; it does not claim distributed exactly-once execution.
