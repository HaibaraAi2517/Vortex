# Quickstart Agent Demo

This example is the shortest no-key demo for Vortex. It assumes the quickstart
stack is running at `http://localhost:8080` and shows two visible comparisons:

1. Memory off vs memory on: the same follow-up question is answered without
   prior context, then answered with facts recalled from Vortex memory.
2. Local crash vs Vortex recovery: a worker process checkpoints step 1, gets
   killed, and a new process resumes the task from the checkpoint instead of
   restarting from step 1.

No external LLM API key is required. The demo uses a deterministic toy agent so
that the difference comes from Vortex memory and task recovery, not from a model
provider.

## Prerequisites

Start the quickstart stack from the repository root:

```powershell
docker compose -f docker-compose.quickstart.yml up --build -d
```

Wait until health is `UP`:

```powershell
Invoke-RestMethod http://localhost:8080/actuator/health
```

## Run On Windows

```powershell
.\examples\quickstart-agent\run.ps1
```

To let the script start the quickstart stack first:

```powershell
.\examples\quickstart-agent\run.ps1 -StartQuickstart
```

## Run On Bash

```bash
bash examples/quickstart-agent/run.sh
```

To let the script start the quickstart stack first:

```bash
START_QUICKSTART=true bash examples/quickstart-agent/run.sh
```

## Expected Output

The memory section prints a no-memory answer like:

```text
NO MEMORY: I only see the current question, so I do not know the codename or launch goal.
```

Then it prints recalled Vortex fragments containing the project codename and
launch goal.

The recovery section starts a worker, waits until it writes a checkpoint, kills
that worker process, and then recovers the task with `nodeCount=1` before adding
step 2. This shows that the resumed process continues from durable Vortex state.

## API Surface Used

- `POST /api/v1/memory/store`
- `POST /api/v1/memory/recall`
- `POST /api/v1/tasks`
- `POST /api/v1/tasks/{taskId}/nodes`
- `POST /api/v1/tasks/{taskId}/checkpoint`
- `POST /api/v1/tasks/{taskId}/recover`
