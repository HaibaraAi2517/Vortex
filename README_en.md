# Vortex

[简体中文](README.md) · English

[![CI](https://github.com/HaibaraAi2517/Vortex/actions/workflows/ci.yml/badge.svg)](https://github.com/HaibaraAi2517/Vortex/actions/workflows/ci.yml)
[![Java 21](https://img.shields.io/badge/Java-21-orange.svg)](pom.xml)
[![License](https://img.shields.io/badge/License-Apache--2.0-blue.svg)](LICENSE)

**Durable local memory for personal AI agents. Remember preferences and project facts, update them when they change, and recall their current versions.**

One Java process, one data directory. Connect through Python, Spring AI, MCP or REST.
The default local mode embeds Lucene BM25, CJK bigrams and HNSW vectors, backed by a local BGE model.
Storage and retrieval need no external database, Docker or chat-model API key.
Automatic chat and fact extraction require a configured chat model; Codex uses its existing model and login.

Local memory is an **alpha in the current source tree**. The published [v0.2.0](docs/releases/v0.2.0.md)
image uses Milvus/MinIO and does **not** include these features. The Python package is source-installable;
it is not on PyPI, and Maven artifacts are not published yet.

| Start with | Entry point |
| --- | --- |
| See updates and recall in action | Start the service and open the workbench below |
| Automatic memory in Python | [MemorySession and model configuration](docs/automatic-agent-memory.md) |
| Shared Codex and Python facts | [Codex CLI guide](docs/codex-memory.md) |
| Automatic memory in Spring AI | [ChatClient Advisor example](examples/spring-ai-integration/README.md) |
| Other MCP clients or a custom agent | [Python SDK and MCP configuration](sdk/python/README.md) |

## Start locally

Source requirements: JDK 21, Maven 3.9+, and Python 3.10+. The first launch downloads dependencies
and pinned model files, verifying SHA-256. Subsequent starts reuse them. The trial ZIP includes the model
and needs only Java 21 to run the service; Python/MCP clients also need Python.
Build it using the [local packaging instructions](docs/local-memory.md); no new public release download is available yet.

```powershell
# Windows, from the repository root
powershell -NoProfile -ExecutionPolicy Bypass -File .\ops\run-local.ps1
```

```bash
# Linux / macOS
bash ops/run-local.sh
```

Open the [memory workbench](http://127.0.0.1:8080/) and click **运行真实演示**.
The demo writes a Beijing address, updates it to Shanghai, and checks that ordinary recall excludes
the previous version. These are actual storage and retrieval operations; the browser demo does not
call a chat model. You can also create facts, inspect revision history, retract or delete memories,
and copy retrieved context for an agent. The workbench currently uses Chinese labels.

For the Python API demo, run in another terminal:

```bash
python -m pip install ./sdk/python
python examples/personal-memory/demo.py
```

The demo changes a remembered home city from Beijing to Shanghai, recalls the current fact, and rejects
a stale update. API: `127.0.0.1:8080`. Persistent directory: `./data/vortex`.
For subsequent starts, use `-SkipBuild` on Windows or `SKIP_BUILD=true` on Bash.

Alternatively, build and run one container:

```bash
docker compose -f docker-compose.local.yml up --build -d
```

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
| Python unit/protocol tests, 2026-11-17 | 81/81 passed, including MCP stdio and evaluation validity checks. | [Test code](sdk/python/tests) |

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
