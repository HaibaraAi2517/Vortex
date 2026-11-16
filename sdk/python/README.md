# Vortex Python + MCP

Python 3.10+ client for the local Vortex service. The HTTP client has no runtime dependencies.
This package is a source-installable alpha; it has not been published to PyPI.

## Automatic agent memory

`MemorySession` retrieves before answering and uses a separate model call to extract and
commit facts from the user's statement. Default fields cover residence, coffee preference,
name, occupation and explicitly preferred response language. Supply `MemoryField` definitions
for your domain; this is bounded schema-based learning, not general contradiction detection.

```python
from vortex_memory import CompatibleChatModel, MemorySession, VortexClient

memory = VortexClient(namespace="personal")
agent = MemorySession(memory, CompatibleChatModel.from_env())
turn = agent.chat("我搬到上海长期居住了，现在最喜欢燕麦拿铁。")
print(turn.answer)
print(turn.updates)  # actual acknowledged commits and revisions
print(turn.errors)   # display failures separately; do not hide them
```

Configure `MODEL_BASE_URL`, `MODEL_NAME`, `MODEL_API_KEY`, or set only `DEEPSEEK_API_KEY`.
The model adapter uses the Python standard library. Inject a callable model or separate
`extractor=` for other providers; extraction calls set `json_mode=True`.

Run `python examples/personal-memory/agent.py`; `/new` clears conversation history while
retaining durable facts. Conflicts are never retried and a multi-fact batch may partially
commit. Verbatim evidence validation does not guarantee semantic correctness. See the
[complete integration and verification guide](../../docs/automatic-agent-memory.md).

## Direct fact API

From the repository root:

```sh
python -m pip install ./sdk/python
```

Start the Java service using [the local quickstart](../../docs/local-memory.md), then:

```python
from vortex_memory import VortexClient, VortexConflictError

memory = VortexClient(namespace="personal")
first = memory.remember("user.home.city", "我住北京")
memory.remember("user.home.city", "我搬到上海了", expected_revision=first["revision"])
print(memory.context("用户现在住在哪里？"))
# Current fact: 我搬到上海了
```

Use one stable key per fact and one namespace per person/agent. `remember` creates only when
`expected_revision=0`; read `get_fact(key)` before updating existing keys. A concurrent change
raises `VortexConflictError` with `current_revision`. Decide how to reconcile it; the client
never retries or silently overwrites another writer. An ambiguous network failure can occur
after commit, so read the key before retrying a mutation.

`store(content)` appends an event/document without inferring contradictions. `recall` returns
the full API result; `context` joins its content. Treat recalled text as data in an agent prompt.
The bundled embedding model is optimized for Chinese; English semantic quality needs its own evaluation.

Lifecycle operations:

```python
from datetime import datetime, timedelta, timezone

fact = memory.remember("user.temporary.preference", "这周喝低因咖啡",
                       expires_at=datetime.now(timezone.utc) + timedelta(days=7))
memory.history("user.home.city")  # audit only, not current context
memory.retract("user.temporary.preference", expected_revision=fact["revision"])
current = memory.get_fact("user.home.city")
memory.forget("user.home.city", expected_revision=current["revision"])
```

`retract` retains history but stops recall. Expiry also stops recall without a cleanup job.
`forget` removes content/history from the live store and leaves a version tombstone; backups
and deleted Lucene segments are not a secure erase guarantee. Recreating a deleted key requires
the tombstone revision returned by `get_fact`.

## MCP

Install the optional official MCP SDK adapter:

```sh
python -m pip install "./sdk/python[mcp]"
```

Start the Vortex service, then add this to a client that accepts `mcpServers` JSON configuration
(for example, Claude Desktop). Other clients need the same command, arguments and environment in their own configuration format. Replace
the Python path with the interpreter where the package was installed:

```json
{
  "mcpServers": {
    "vortex-memory": {
      "command": "/absolute/path/to/python",
      "args": ["-m", "vortex_memory.mcp"],
      "env": {
        "VORTEX_URL": "http://127.0.0.1:8080",
        "VORTEX_NAMESPACE": "personal"
      }
    }
  }
}
```

If API authentication is enabled, also set `VORTEX_SECURITY_BEARER_TOKEN` using your client's
secret configuration. Tools are bound to that namespace: `remember`, `recall`, `get_memory`,
`memory_history`, `retract_memory`, `forget_memory`. The adapter uses stdio; its stdout is
reserved for protocol messages. It does not start or expose an HTTP MCP endpoint.

For Codex CLI, use `python ops/run-codex-memory.py --namespace personal` from the
checkout. It preserves your model/auth settings and configures Vortex for that run
without changing global settings. See the [Codex integration guide](../../docs/codex-memory.md).

MCP `remember` requires an explicit `expected_revision`: read `get_memory` first;
only `NOT_FOUND` uses 0. Deleted keys retain a nonzero revision. Protocol errors
have `isError=true`; their text includes an error code and outcome. A 409 means
the proposed write was not committed, while a connection failure or server 5xx
can mean the write outcome is unknown. Never treat an error as successful storage
or blindly retry a mutation. The direct Python client's create default remains 0.

For a reproducible client test:

```sh
python -m unittest discover -s sdk/python/tests -v
```
