# Vortex Architecture

Vortex is organized around three runtime concerns for long-running agents:
memory recall, memory durability, and task recovery. The diagrams below are
GitHub-native Mermaid diagrams and do not depend on external image hosting.

## Hybrid Retrieval Pipeline

```mermaid
flowchart LR
    Q[Agent query] --> N[Namespace and tag filter]
    N --> K[Keyword recall]
    N --> V[Vector recall]
    K --> M[Hybrid candidate merge]
    V --> M
    M --> R[Rerank and budget]
    R --> C[Context assembly]
    C --> A[Agent prompt / response path]
```

The retrieval path combines lexical and vector candidates, then applies
reranking, namespace isolation, tag filtering, and token-budget-aware context
assembly before handing memory back to the agent runtime.

## Three-Tier Memory Storage

```mermaid
flowchart TB
    W[Memory write] --> S[Split and embed]
    S --> L1[L1 Hot: Caffeine]
    S --> P[Async persistence pipeline]
    P --> L2[L2 Warm: Milvus]
    P --> L3[L3 Cold: MinIO]
    L1 --> R[Low-latency recall]
    L2 --> REC[L1 recovery after eviction]
    L3 --> ARC[Cold archive and durable artifacts]
```

L1 is optimized for hot low-latency access, L2 for vector retrieval and
recovery after eviction, and L3 for cold fragments and checkpoint/WAL-related
artifacts.

L1 capacity is enforced by the kernel admission coordinator rather than by a
second Caffeine size policy. Capacity check, semantic eviction, and insertion
run under one admission lock. Pinned fragments are excluded from victim
selection; repeated recalls refresh recency and reinforce importance, so access
history contributes to retention. If the protected working set leaves no
effective capacity, the incoming fragment is rejected from L1 instead of
silently displacing a pinned entry.

## Model Provider Adapters

The optional `vortex-langchain4j` module keeps provider integration outside the
memory kernel. `LangChain4jGenerationAdapter` maps a LangChain4j `ChatModel` to
Vortex's `GenerationService`, while `LangChain4jEmbeddingAdapter` maps an
`EmbeddingModel` to `EmbeddingService`. Provider authentication, retry, timeout,
and transport policy remain owned by the supplied LangChain4j model.

The default application path remains OpenAI-compatible generation plus local
ONNX BGE embeddings. The adapter module is an explicit integration choice and
does not change the benchmark provider path.

## Runtime Recovery Flow

```mermaid
flowchart LR
    F[Failure or restart] --> CP[Checkpoint load]
    CP --> WAL[WAL replay]
    WAL --> RS[Runtime state reconstruction]
    RS --> DAG[Task DAG and context restored]
    DAG --> ID[Execution ID idempotency check]
    ID --> RES[Task resume]
```

Recovery reconstructs covered task/runtime state from checkpoint + WAL,
including DAG state, context, conversation/tool/LLM runtime snapshots, and
Execution ID replay protection in the deterministic benchmark scope.
