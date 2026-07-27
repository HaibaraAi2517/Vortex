# How Vortex Fits

Vortex is a backend runtime for agent memory, retrieval, and task recovery. It
is meant to sit behind an agent framework or application, not to replace the
agent's planning loop, model provider, UI, or orchestration code.

## Positioning

| Need | Plain vector RAG | Hand-rolled memory layer | Vortex |
| --- | --- | --- | --- |
| Cross-session memory | Usually possible, but often modeled as document chunks only. | Possible, but schema, namespaces, retention, and recall contracts must be designed per app. | First-class memory fragments with namespaces, tags, feedback, pin/unpin, eviction, and recall APIs. |
| Exact operational facts | Vector similarity may miss IDs, names, commands, and other exact facts. | Usually added with custom keyword filters or separate search paths. | Hybrid keyword + vector recall with reranking and token-budgeted context assembly. |
| Storage lifecycle | Commonly depends on one vector database tier. | App owners must build hot cache, vector store, archive, and recovery behavior. | L1 Caffeine, L2 Milvus, and L3 MinIO are explicit storage tiers. |
| Long-running task recovery | Out of scope for most RAG stacks. | Often implemented as ad hoc checkpoints or logs. | Task DAG, checkpoints, WAL replay, runtime snapshots, and idempotency support are exposed as runtime APIs. |
| Local no-key evaluation | Depends on the chosen embedding and LLM provider. | Depends on local app scaffolding. | Quickstart uses local BGE embeddings and disables external generation by default. |
| Production readiness | Depends on the surrounding system. | Depends on the team implementation. | Alpha-stage infrastructure kernel; production auth, RBAC, tenant isolation, and rate limits are not claimed. |

## When Vortex Is A Good Fit

- You are building Java or Spring-based agent infrastructure.
- Your agent needs durable memory across sessions rather than only per-prompt
  context.
- Retrieval needs both semantic similarity and exact operational facts such as
  IDs, commands, names, paths, or user preferences.
- Long tasks need explicit checkpoints and recovery semantics.
- You want an auditable backend with deterministic benchmark runbooks rather
  than only demo scripts.

## When A Simpler Approach May Be Enough

- Your app only needs one-shot document Q&A over static files.
- The agent can recompute work cheaply after a crash.
- A single vector database plus a small amount of application code covers the
  required memory behavior.
- You need managed production features such as auth, billing, tenancy, and
  admin UI on day one.

## Integration Boundary

Vortex provides memory and recovery primitives through HTTP APIs and Java
modules. The caller is still responsible for:

- choosing the LLM or agent framework;
- constructing prompts and deciding when to recall memory;
- applying authentication and authorization in production deployments;
- monitoring real production traffic and setting operational SLOs;
- validating domain-specific retrieval quality with its own ground truth.
