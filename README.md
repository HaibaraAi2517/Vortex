# Vortex

<p align="center">
  English | <a href="README_zh.md">简体中文</a>
</p>

[![CI](https://github.com/HaibaraAi2517/Vortex/actions/workflows/ci.yml/badge.svg)](https://github.com/HaibaraAi2517/Vortex/actions/workflows/ci.yml)
[![Release: v0.1.0](https://img.shields.io/badge/release-v0.1.0-2EA44F.svg)](docs/releases/v0.1.0.md)
[![License: Apache-2.0](https://img.shields.io/badge/License-Apache--2.0-blue.svg)](LICENSE)
[![Java 21](https://img.shields.io/badge/Java-21-orange.svg)](pom.xml)
[![Spring Boot 3.3](https://img.shields.io/badge/Spring%20Boot-3.3-6DB33F.svg)](vortex-app/pom.xml)
[![Milvus](https://img.shields.io/badge/Milvus-2.4-00A1EA.svg)](docker-compose.yml)

**Memory and RAG runtime for long-running AI agents: remember across sessions,
retrieve the right context, and resume after crashes. Built with Java 21,
Spring Boot, Milvus, MinIO, Redis, and Caffeine.**

`v0.1.0` is the stable, evidence-backed portfolio release. Vortex is an
infrastructure kernel rather than a hosted SaaS: the repository keeps code,
deterministic benchmarks, failure-injection evidence, and reproduction paths
together.

<p align="center">
  <a href="#quick-start"><b>Quick Start</b></a> ·
  <a href="#three-core-engineering-decisions"><b>Engineering Decisions</b></a> ·
  <a href="docs/benchmark.md"><b>Benchmarks</b></a> ·
  <a href="docs/architecture.md"><b>Detailed Architecture</b></a>
</p>

## System Architecture

```mermaid
flowchart TB
    A[Agent / Spring AI / LangChain4j] --> API[Vortex REST and Java contracts]
    API --> K[Memory and Task Kernel]

    subgraph W[Write path]
        direction LR
        K --> E[Split and local embedding]
        E --> L1[L1 Caffeine write-through]
        L1 --> ACK[Return with read-your-own-write]
        L1 --> P[Bounded async pipeline]
        P --> L2[L2 Milvus vector index]
        P --> L3[L3 MinIO cold archive]
    end

    subgraph R[Recall path]
        direction LR
        K --> KW[Keyword candidates]
        K --> VC[Vector candidates]
        KW --> H[Hybrid merge and filters]
        VC --> H
        H --> B[Rerank and token budget]
        B --> CTX[Context returned to Agent]
    end

    subgraph S[Recovery path]
        direction LR
        K --> CP[Runtime snapshot and checkpoint]
        CP --> WAL[WAL deduplicated replay]
        WAL --> ID[Execution ID idempotency]
        ID --> RES[Resume task DAG]
    end
```

The synchronous boundary ends at L1 visibility. Durable indexing and archival
run behind a bounded pipeline; recall and recovery remain separate kernel paths.
This boundary is the central latency, consistency, and failure-recovery decision
in the project.

## Quick Start

Prerequisite: Docker Desktop or Docker Engine with Compose and at least 6 GB
available memory. One command builds the stack, waits for health, stores and
recalls memory, kills a worker after checkpointing, and resumes the task:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\examples\quickstart-agent\run.ps1 -StartQuickstart
```

Linux/macOS:

```bash
START_QUICKSTART=true bash examples/quickstart-agent/run.sh
```

A successful run prints `WITH VORTEX: recalled durable memory`, then
`WITH VORTEX: recovered task ...`, and finishes with
`No external LLM API key was used.` Stop the stack with:

```powershell
docker compose -f docker-compose.quickstart.yml down
```

The recorded output and expanded HTTP walkthrough live in
[examples/quickstart-agent](examples/quickstart-agent) and
[docs/quickstart.md](docs/quickstart.md).

## Benchmark Evidence

The headline numbers below are deterministic benchmark results with linked
evidence and reproduction commands. See [docs/benchmark.md](docs/benchmark.md)
for scope, boundaries, and reproduction notes. They are not production
guarantees.

| Area | Result | Evidence |
| --- | --- | --- |
| LongMemEval recall | On the official LongMemEval oracle, a 120-case case-isolated evaluation completed five modes and `600` paired runs with `0` errors. `VectorOnly` reached fragment Recall@5 `0.8094` and exceeded `KeywordOnly` by `+0.1856`, paired 95% CI `[+0.1086, +0.2632]`. | [LongMemEval evaluation report](ops/runbooks/vortex-recall-longmemeval-evaluation-report-20260729.md) |
| Cross-Encoder gate | A pinned ONNX Cross-Encoder DEV candidate changed ordering in `120/120` cases but failed five frozen quality and latency rules. `VectorOnly` remains the default; validation and reserve were not run. | [Cross-Encoder DEV decision](ops/runbooks/vortex-cross-encoder-dev-decision-20260729.md) |
| Main-path latency | With synchronous raw-memory L1 write-through and final processing in a bounded background pipeline, measured P99 fell from `818.82 ms` to `268.65 ms` (`-67.19%`) over 100 cases per mode. L1 visibility at return and eventual L2/L3 readiness were both `100%`. | [Write-through latency evidence](ops/runbooks/vortex-main-path-latency-write-through-evidence-20260728.md) |
| Runtime recovery | The deterministic fault-injection matrix passed `32/32` covered cases across service restart, tool failure, LLM exception, state integrity, and concurrency categories. | [Runtime recovery evidence](ops/runbooks/vortex-runtime-recovery-benchmark-evidence-20260627.md) |

Recall is oracle-fragment retrieval, not answer accuracy. Latency is from a
local deterministic benchmark with external LLM generation excluded, not
production P99 or full Agent latency. The linked evidence files define the
exact scope; the rejected Cross-Encoder result is not evidence of model gain.

## Three Core Engineering Decisions

### 1. Return after L1 write-through; persist final state asynchronously

**Problem.** Synchronously extracting, summarizing, indexing, and archiving every
memory made the request path pay for work that the caller did not need before
return.

**Decision.** Vortex keeps raw-memory L1 write-through inside the synchronous
boundary, then moves final extraction, L2 indexing, and L3 archival to a bounded
pipeline with retry and backpressure. The caller gets read-your-own-write
semantics without waiting for all durable tiers.

**Trade-off.** The design accepts eventual L2/L3 readiness and must expose
pipeline status and failure handling. In return, deterministic 100-case runs
reduced main-path P99 from `818.82 ms` to `268.65 ms` while preserving `100%`
L1 visibility at return and eventual L2/L3 readiness. See the
[write-through latency evidence](ops/runbooks/vortex-main-path-latency-write-through-evidence-20260728.md).

### 2. Prefer an auditable retrieval baseline over an unproven reranker

**Problem.** A shared evaluation namespace caused cross-case leakage, and a
reranker can appear useful simply because it changes ordering.

**Decision.** The contaminated result was discarded, LongMemEval was rerun with
case isolation, and model promotion was placed behind five frozen quality and
latency gates. The pinned ONNX Cross-Encoder changed `120/120` rankings but
failed the gate, so `VectorOnly` remains the default.

**Trade-off.** Vortex gives up speculative reranking gain and keeps a simpler,
lower-latency serving path until evidence clears the gate. The isolated
120-case run reached fragment Recall@5 `0.8094`, `+0.1856` over
`KeywordOnly`, with paired 95% CI `[+0.1086, +0.2632]`. See the
[LongMemEval report](ops/runbooks/vortex-recall-longmemeval-evaluation-report-20260729.md)
and [Cross-Encoder decision](ops/runbooks/vortex-cross-encoder-dev-decision-20260729.md).

### 3. Combine Snapshot, WAL, and Execution ID instead of claiming distributed exactly-once

**Problem.** A checkpoint alone cannot distinguish completed work from an
in-flight tool or LLM call after a restart, so replay can duplicate side
effects.

**Decision.** Runtime snapshots persist the task DAG, conversation, memory
references, and tool/LLM state. Recovery loads a checkpoint, deduplicates WAL
replay, reconstructs state, and uses Execution ID request hashes, atomic
reservation, and response replay for idempotency.

**Trade-off.** This adds serialization, WAL write amplification, and stricter
state-transition contracts. It provides deterministic single-runtime recovery,
not distributed consensus or cross-region exactly-once. The fault-injection
matrix passed `32/32` covered cases across five failure categories. See the
[runtime recovery evidence](ops/runbooks/vortex-runtime-recovery-benchmark-evidence-20260627.md).

## Implementation Surface

| Surface | What is implemented |
| --- | --- |
| Memory | Store, recall, feedback, pin/unpin, eviction, async ingest status, namespace/tag filtering, and token budgets |
| Retrieval | Keyword, vector, hybrid candidate merge, optional reranking gates, and context assembly |
| Runtime state | Task DAG mutation, checkpoint, WAL replay, branch/switch/merge, and Execution ID idempotency |
| Storage | L1 Caffeine, L2 Milvus, L3 MinIO, plus optional Redis-backed Execution ID state |
| Model integration | Vortex generation/embedding contracts, Spring AI example, and LangChain4j adapters |

The public REST surface is available through Swagger UI at
`http://localhost:8080/swagger-ui.html` after Quickstart. Detailed endpoints
and configuration remain in [docs/quickstart.md](docs/quickstart.md) and
[docs/architecture.md](docs/architecture.md).

## Build And Test

CI runs unit tests, app integration verification, baseline governance, and
learning governance:

```powershell
mvn -B test -pl vortex-common,vortex-kernel,vortex-storage,vortex-langchain4j -am
mvn -B verify -pl vortex-app -am
./ops/run-baseline-governance-check.ps1 -SkipMavenTest -SkipPackage
./ops/run-learning-governance-check.ps1 -SkipMavenTest -SkipPackage -SkipLearningRun
```

`vortex-app` integration verification starts Docker Compose during
`pre-integration-test` and stops it during `post-integration-test`.

## Evidence And Reproduction

- [Benchmark scope and headline results](docs/benchmark.md)
- [Architecture and component boundaries](docs/architecture.md)
- [LongMemEval case-isolated evaluation](ops/runbooks/vortex-recall-longmemeval-evaluation-report-20260729.md)
- [Main-path write-through latency evidence](ops/runbooks/vortex-main-path-latency-write-through-evidence-20260728.md)
- [Runtime recovery fault-injection evidence](ops/runbooks/vortex-runtime-recovery-benchmark-evidence-20260627.md)
- [Stable v0.1.0 release notes](docs/releases/v0.1.0.md)

## Project Status

For positioning against plain vector RAG and hand-rolled memory layers, see
[docs/comparison.md](docs/comparison.md). The stable portfolio release is
[`v0.1.0`](docs/releases/v0.1.0.md); the original alpha notes remain archived.

Implemented and covered by code/tests/runbooks:

- Hierarchical memory store, recall, feedback, pin/unpin, and eviction.
- Vector, keyword, hybrid, and rerank retrieval paths.
- Async memory ingest pipeline with persistence status tracking.
- Task DAG, checkpoint, WAL replay, branch/switch/merge, and recovery.
- Health catalog, SLO snapshots, Prometheus metrics, and monitoring assets.
- Deterministic benchmark and governance harnesses.
- Optional LangChain4j adapters for LLM generation and embedding providers.

Not claimed yet:

- Production-grade auth, RBAC, tenant isolation, rate limits, or audit logs.
- Long-duration high-concurrency production capacity results.
- Distributed consensus, multi-node scheduling, or cross-region replication.
- Full external process-manager crash-loop orchestration.
- Full Agent runtime integration with real LLM generation inside the latency benchmark.

## Repository Map

```text
.
|-- vortex-common/        shared contracts, DTOs, serialization, exceptions
|-- vortex-storage/       L1/L2/L3 storage APIs and implementations
|-- vortex-kernel/        memory, retrieval, recovery, snapshot, paging, learning
|-- vortex-langchain4j/   optional ChatModel and EmbeddingModel adapters
|-- vortex-app/           Spring Boot API, eval CLI, benchmark runners, tests
|-- ops/                  runbooks, evidence reports, CI/governance scripts
|-- docs/                 architecture and benchmark summaries
|-- demo/                 demo scripts
|-- examples/             focused runnable examples
|-- models/bge-small-zh/  default local BGE-Small model files
|-- docker-compose.yml    etcd, Milvus, MinIO, Redis
`-- pom.xml               Maven multi-module parent
```

## Tech Stack

- Java 21 with preview enabled
- Spring Boot 3.3.4
- Maven multi-module build
- Caffeine 3.1.8
- Milvus SDK 2.4.4
- MinIO 8.5.11
- Redis 7.2 optional Execution ID backend
- Kryo 5.6.0
- DJL 0.28.0 and ONNX Runtime 1.18.0 for local BGE embeddings
- LangChain4j 1.18.0 for optional LLM and embedding adapters
- Testcontainers 2.0.2

## Review Guide

For a focused code review, start with the system diagram and the three decisions
above, then use the linked evidence reports to inspect the benchmark boundaries.
The repository map above points to the corresponding implementation.

## License

Vortex code and documentation are licensed under the [Apache License 2.0](LICENSE).

Third-party model files, datasets, and external service names remain subject to
their upstream licenses and terms.
