# Real Model Agent Recovery Demo

This example runs a real OpenAI-compatible chat model through LangChain4j,
requires the model to call read-only tools, injects durable Vortex memory, then
creates a Vortex task checkpoint. The orchestrator kills the phase-one process
tree and starts a new JVM that recovers the checkpoint and continues the task.

It demonstrates four separate behaviors:

1. A fresh model conversation does not know private demo facts.
2. Vortex recalls those facts and injects them before model generation.
3. The model calls real read-only tools for Git, Vortex health, and recovered task state.
4. A new process recovers the checkpointed task and appends phase two instead of repeating phase one.

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
3. Runs the complete real-model crash recovery demo.
4. Stops the Quickstart containers.
5. Restores the PowerShell process environment to its original values.

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

The output includes these checkpoints:

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
DEMO COMPLETE: real model + real tools + Vortex memory + checkpoint recovery.
```

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
