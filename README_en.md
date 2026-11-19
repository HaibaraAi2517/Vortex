# Vortex

[简体中文](README.md) · English

[![CI](https://github.com/HaibaraAi2517/Vortex/actions/workflows/ci.yml/badge.svg)](https://github.com/HaibaraAi2517/Vortex/actions/workflows/ci.yml)
[![Java 21](https://img.shields.io/badge/Java-21-orange.svg)](pom.xml)
[![License](https://img.shields.io/badge/License-Apache--2.0-blue.svg)](LICENSE)

**Share your personal memory across agents.** Save a fact in Codex, update it when it changes, and carry its current version into a new conversation in Doubao. Vortex stores the facts, their revisions and the search index on your computer.

## Start on Windows

1. Find a preview with a tag starting with `local-` and download `vortex-local-windows-x64.zip` from its Assets. It includes Java 21 and the Chinese retrieval model. [GitHub Releases](https://github.com/HaibaraAi2517/Vortex/releases)
2. Extract the entire ZIP into a new folder and double-click **`Start Vortex.cmd`**. The launcher opens the workbench in your browser when it is ready. Keep the launch window open. [Default workbench address](http://127.0.0.1:8080/)
3. Click **运行真实演示** (run demo). It saves Beijing as the home city in a separate space, then updates it to Shanghai. Click **当前住所** (current address), select just the Shanghai fact, check the preview, and click **复制任务和所选记忆** (copy the task and selected memories). Paste it into a fresh Doubao chat and send it. Check whether Doubao answers with the current city. To add your own facts, click **新建记忆** (new memory).

The workbench can store, retrieve and copy facts without an API key. You choose which facts to send. Codex CLI can access the same space directly through MCP using its existing login. Automatic fact extraction in Python or Spring AI requires a configured chat model.

If there is no `local-` preview in Releases, sign in to GitHub and open a successful Local Memory workflow run containing this launcher. Download the `vortex-local-windows-release` artifact, extract it, then extract the Windows ZIP inside. The older `v0.2.0` deployment uses Milvus and MinIO. [Local Memory workflow](https://github.com/HaibaraAi2517/Vortex/actions/workflows/local-memory.yml)

New Windows installations store memory in `%LOCALAPPDATA%\Vortex\data`. To upgrade, stop the server with `Ctrl+C`, back up that directory, and extract the new version into another folder. Older ZIPs may use `data/vortex` inside the old folder. The launcher prints the actual location. On Linux, use the universal `vortex-local-alpha.zip` with Java 21 and run `bash start.sh`. [Data and upgrade guide (Chinese)](docs/local-memory.md#持久化边界与备份)

## Choose an integration

| Start with | Entry point |
| --- | --- |
| Shared Codex and Python facts | [Codex CLI guide](docs/codex-memory.md) |
| Automatic memory in Python | [MemorySession and model configuration](docs/automatic-agent-memory.md) |
| Automatic memory in Spring AI | [ChatClient Advisor example](examples/spring-ai-integration/README.md) |
| Other MCP clients or a custom agent | [Python SDK and MCP configuration](sdk/python/README.md) |
| Run from source or in a container | [Local setup (Chinese)](docs/local-memory.md#从源码启动) |

The commands below assume a source checkout. With a ZIP, use the commands in `README.txt` to install the included wheel from `python/` before running the examples.

## Use in an agent

For explicit reads and writes:

```python
from vortex_memory import VortexClient

memory = VortexClient(namespace="personal")
key = "user.home.city"
old = memory.get_fact(key)
memory.remember(key, "I now live in Shanghai",
                expected_revision=old["revision"] if old else 0)
context = memory.context("Where does the user live now?")
# Include context as reference data in your agent's prompt.
```

Use a stable namespace for a person/agent and a stable key for each mutable fact.
`remember` explicitly replaces a fact; `store` appends events/documents without inferring contradictions.
Conflicting revisions produce HTTP 409 instead of silently overwriting another writer.
The bundled BGE-Small-ZH model is optimized for Chinese; English semantic quality requires separate evaluation.

For automatic retrieval and fact updates during normal chat, use Python `MemorySession`
or Spring AI `VortexMemoryAdvisor` with `VortexFactLearner`. They use a separate model
call after answering to extract configured facts from user statements and commit with revision checks.
The default schema covers home city, coffee preference, name, work role and reply language.
This flow requires chat-model credentials in addition to the local service.

```python
from vortex_memory import CompatibleChatModel, MemorySession, VortexClient

memory = VortexClient(namespace="personal")
session = MemorySession(memory, CompatibleChatModel.from_env())
turn = session.chat("I have moved to Shanghai permanently.")
print(turn.answer)
print(turn.updates)  # Only facts the service confirmed as committed
print(turn.errors)   # Extraction failures, conflicts or unknown write outcomes
```

After configuring `DEEPSEEK_API_KEY` or `MODEL_BASE_URL`, `MODEL_NAME` and `MODEL_API_KEY`,
run `python examples/personal-memory/agent.py --namespace personal`. Use `/new` to clear chat
history while retaining durable memory, or restart the process. See the
[automatic agent guide](docs/automatic-agent-memory.md) for model configuration, custom fields
and the Spring AI `ChatClient` integration. Java examples are built from source.

For MCP, install `python -m pip install "./sdk/python[mcp]"` and configure
`python -m vortex_memory.mcp` as a stdio server. Tools: `remember`, `recall`, `get_memory`,
`memory_history`, `retract_memory`, `forget_memory`. Read the current revision before updating
a fact. MCP exposes tools; it does not monitor all chats or decide when the host calls them.
[Python API and complete MCP configuration](sdk/python/README.md).

If Codex CLI already works on your machine, start a session with Vortex:

```bash
python -m pip install "./sdk/python[mcp]"
python ops/run-codex-memory.py --namespace personal
```

The launcher keeps your existing model and authentication configuration, configures MCP for this run,
and loads example fact-management instructions in a separate temporary directory. It does not edit
global Codex configuration. A Python agent using the same service and namespace can read and update
the same facts. See the [Codex integration guide](docs/codex-memory.md) and
[nine-turn Codex/Python verification](docs/codex-memory-verification.md).

## Behavior and limits

| Need | Behavior |
| --- | --- |
| Chinese lexical recall | CJK bigrams and BM25, fused with local vectors |
| Growing corpus | Full namespace index; topK limits results, never an arbitrary 256-document corpus sample |
| Changing facts | Atomic namespace/key revision replacement; history is excluded from ordinary recall |
| Temporary/incorrect facts | Expiry, retraction with history, or deletion of live content/history |
| Restart durability | Ordinary local writes commit before success |
| Late background writes | Replaced revisions cannot be resurrected by reinforcement, eviction or retries |
| Agent isolation | Namespace/tag filters and optional Bearer token/namespace allowlist |

This is a personal, single-process, loopback service. It does not provide public multi-tenant hosting,
unrestricted automatic contradiction inference or a capacity SLA. Automatic learning is bounded
by configured fact fields and the extraction model's judgment. `forget` is logical deletion; existing backups
and secure disk erasure are separate concerns. Back up the entire data directory while the service is stopped.

## Verification

The [Chinese cross-session quality baseline](docs/memory-quality-baseline-20261117.md) compares no memory,
free-text summaries and the production Vortex agent on 100 synthetic cases. It retains failed calls and
full traces, using a separate call to the same configured model as an automatic judge. These results are
not independent human accuracy labels or a competitor ranking. See the [evaluation guide](docs/memory-quality.md).
The first attempt scored 23/100, 94/100 and 86/100 respectively, with 96, 95 and 95 valid judgments;
all arms had execution errors. Confirmation stopped after provider HTTP 402, so there is no complete,
error-free confirmation. Development traces identify attribution, reference extraction and answering
from correctly recalled facts as the next quality priorities.

```bash
python ops/download-model.py --all
mvn verify
python -m pip install "./sdk/python[mcp]"
python -m unittest discover -s sdk/python/tests -v
python ops/verify-local-memory.py --mcp
```

The last command runs the packaged service with real BGE, an isolated directory/port, 300 distractor memories,
revision updates, a forced restart and official MCP calls. It does not touch your everyday memory directory.
[Current verification evidence and limitations](docs/local-memory-verification.md).

Recorded real-model checks use a fresh process for every turn, without carrying chat history between
clients:

| Recorded check | Observed result | Evidence |
| --- | --- | --- |
| Python ↔ Spring AI, 2026-11-16 | Seven turns: Python saved Beijing/Americano; Spring AI read and updated them to Shanghai/oat latte; fresh clients read the new facts. MCP read the same revisions. | [Inputs, answers and boundaries](docs/automatic-agent-memory-verification.md) |
| Codex CLI ↔ Python, 2026-11-17 | Nine turns: Codex saved facts; Python read and updated them; a fresh Codex session read the updates and retracted the city. Both clients then answered that the city was unknown while retaining the coffee preference. | [Tool events, revisions and boundaries](docs/codex-memory-verification.md) |
| Python tests, 2026-11-17 | 92/92 passed, including MCP stdio, evaluation validity and diagnostic redaction checks. | [Test code](sdk/python/tests) |

The Codex run used CLI 0.163.0 with the supplied policy and native memory disabled; it did not verify
the desktop UI, Claude Code or Doubao. Codex read known fact keys with `get_memory`, so that run is
not a semantic-retrieval benchmark. The Python/Spring AI run used `deepseek-chat`; its recorded
12 Spring AI tests also cover streaming completion and cancellation behavior. These small scenario
checks establish specific integration behavior, not general answer accuracy or superiority to other
memory systems. The linked guides explain how to rerun them with your model credentials.

Historical [LongMemEval oracle results](docs/benchmark.md) measure fragment retrieval, not answer correctness.
The analyzer and default fusion changed, so old results do not establish this version's quality.
Non-oracle answer evaluation and sustained feedback from real users remain release gates in [ROADMAP](ROADMAP.md).
The [LongMemEval-S hypothesis runner](docs/non-oracle-evaluation.md) is available; answer accuracy is still unmeasured.

## Existing integrations

The Task DAG, Checkpoint, WAL and Execution ID runtime remains available for advanced integrations.
Local mode's Execution ID backend is in memory; it does not guarantee side-effect deduplication across restarts.

- [Spring AI integration](examples/spring-ai-integration/README.md)
- [LangChain4j integration](examples/langchain4j-integration/README.md)
- [Local configuration, lifecycle API and backups (Chinese)](docs/local-memory.md)
- [Distributed quickstart](docs/quickstart.md), explicitly using the `distributed` profile
- [Architecture](docs/architecture.md)

The distributed compatibility backend does not yet support atomic facts. Its lexical fallback scans every
namespace page, so cost grows with corpus size. New personal integrations should use local mode.

## License

[Apache-2.0](LICENSE). Model assets retain their upstream licenses; see [THIRD_PARTY_NOTICES](THIRD_PARTY_NOTICES.md).
