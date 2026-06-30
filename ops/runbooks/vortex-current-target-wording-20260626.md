# Vortex Current Target Wording - 2026-06-26

This file is the current wording source for README/resume/project-summary work.
It supersedes the metric placeholders in `ops/runbooks/目标.md` until that file
can be safely edited.

## Recommended Project Summary

Vortex | Java Agent Memory & RAG Runtime infrastructure

Tech stack: Java 21, Spring Boot, Maven multi-module, Milvus, Redis, Caffeine,
MinIO, Docker Compose, GitHub Actions.

One-line description:

> Built a Java-based Agent Memory and RAG Runtime foundation for long-running
> LLM agent tasks, covering long-term memory, hybrid retrieval, checkpoint-based
> runtime recovery, and deterministic evaluation.

Chinese version:

> 面向 LLM Agent 长任务场景，基于 Java 21 + Spring Boot 从零设计 Agent
> Memory & RAG Runtime 基础设施，覆盖长期记忆、混合检索、checkpoint
> 运行时恢复与 deterministic benchmark，解决上下文遗忘、检索退化和任务中断后的状态重建问题。

## Resume / README Bullets

- Hybrid Retrieval: implemented a hybrid retrieval pipeline with keyword
  candidates, vector candidates, reranking, diagnostics, and a vector-only
  control mode. In the deterministic v3.1 real-agent workload benchmark
  (Milvus + BGE-Small, shared namespace candidate pool), Recall@5 improved from
  0.6667 to 0.8583 vs vector-only, a +28.75% relative lift.
- Tiered Memory Storage: implemented L1 Caffeine hot cache, L2 Milvus vector
  retrieval, and L3 MinIO cold archive, with asynchronous L2/L3 persistence,
  recency/similarity/importance scoring, pin/regret signals, namespace quotas,
  and feedback-driven recall diagnostics.
- Runtime Recovery Foundation: implemented Task DAG, WAL, checkpoint, recover,
  branch, merge, and request-level Execution ID idempotency. Execution ID
  replay can use an in-memory backend by default or Redis when configured.
- Engineering Delivery: organized as a Maven multi-module Spring Boot project
  with Docker Compose dependencies, GitHub Actions CI, eval CLI/reporting,
  Prometheus alert assets, and focused regression tests for retrieval, eval
  reporting, execution idempotency, and task runtime APIs.

Chinese resume-ready version:

- **混合检索 Recall@5 提升 +28.75%**：构建关键词召回 + 向量召回 +
  reranking + diagnostics 的 Hybrid Retrieval Pipeline，并提供 Vector-only
  control mode；在自建 deterministic recall benchmark（v3.1 real-agent
  workload，Milvus + BGE-Small，shared namespace candidate pool）中，Hybrid
  Retrieval 的 Recall@5 从 0.6667 提升到 0.8583，relative lift 为 +28.75%。
- **三级记忆存储与召回治理**：设计 L1 Caffeine 热缓存、L2 Milvus 向量检索、
  L3 MinIO 冷归档，结合异步 L2/L3 持久化、recency/similarity/importance
  评分、pin/regret 信号、namespace quota 与 feedback diagnostics，降低长任务中的关键记忆遗漏风险。
- **运行时恢复基础能力**：实现 Task DAG、WAL、checkpoint、recover、branch、
  merge 与请求级 Execution ID 幂等；Execution ID 默认支持内存后端，也可切换 Redis 后端用于跨进程请求回放。
- **工程化交付**：基于 Maven 多模块 + Spring Boot 组织工程，配套 Docker
  Compose 依赖、GitHub Actions CI、eval CLI/report 输出、Prometheus 告警资产，以及覆盖检索、评测报告、幂等和任务 runtime API 的回归测试。

## Claims Allowed Today

- Hybrid retrieval has code support for keyword recall, vector recall, reranking,
  diagnostics, and vector-only comparison.
- Deterministic recall benchmark evidence exists for v3.1 real-agent workload.
- The defensible compact metric is: Recall@5 relative lift +28.75% vs vector-only.
- Execution ID idempotency exists at the application API layer, with Redis
  backend support when enabled.
- Runtime recovery foundation exists for Task DAG/WAL/checkpoint/recover.

## Claims Not Yet Allowed

- Do not claim LLM answer accuracy improved by +28.75%; the current metric is
  deterministic retrieval recall, not generation correctness.
- Do not claim main-path latency decreased. The current recall benchmark shows
  higher average latency for hybrid retrieval than vector-only.
- Do not claim `恢复成功率 XX%` until a runtime recovery benchmark exists.
- Do not claim complete Agent Runtime snapshot coverage for Conversation and
  Tool state yet.
- Do not claim Tool Failure or LLM Timeout task-level resume is complete.
- Do not claim a full async extraction/summary/embedding/indexing pipeline yet;
  current async support is strongest around L2/L3 persistence.
- Do not claim Frequency-based scoring unless a true frequency metric is added;
  current wording should use recency, similarity, importance, pin/regret, and
  namespace quota.

## Evidence References

- Benchmark evidence index: `ops/runbooks/vortex-recall-benchmark-evidence-20260626.md`
- Recall@5 report: `ops/eval-reports/20260626-recall-benchmark-v3-1-top5-shared-001/recall-benchmark-20260625-162412.md`
- Recall@1 report: `ops/eval-reports/20260626-recall-benchmark-v3-1-top1-shared-001/recall-benchmark-20260625-162328.md`

## Next Development Target

The next highest-value implementation target is a runtime recovery benchmark
that can produce a defensible `恢复成功率 XX%` number. Suggested cases:

- recover after clearing in-memory runtime state after checkpoint;
- repeated recover is idempotent;
- Execution ID replay does not create duplicate task nodes or checkpoints;
- tool failure state is persisted and recoverable;
- LLM timeout state is persisted and can be resumed at task level.

