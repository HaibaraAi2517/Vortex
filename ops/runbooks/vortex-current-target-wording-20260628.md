# Vortex Current Target Wording - 2026-06-28

This file is the current wording source for README, resume, and project-summary
work. It supersedes `ops/runbooks/vortex-current-target-wording-20260626.md`
and has been updated with the 2026-06-29 main-path latency benchmark.

## Recommended Project Summary

Vortex | Java Agent Memory & RAG Runtime infrastructure

Tech stack: Java 21, Spring Boot, Maven multi-module, Milvus, Redis, Caffeine,
MinIO, Docker Compose, GitHub Actions.

One-line description:

> Built a Java-based Agent Memory and RAG Runtime foundation for long-running
> LLM agent tasks, covering long-term memory, hybrid retrieval, checkpoint-based
> runtime recovery, async memory ingest, and deterministic evaluation.

Chinese version:

> 面向 LLM Agent 长任务场景，基于 Java 21 + Spring Boot 从零设计 Agent
> Memory & RAG Runtime 基础设施，覆盖长期记忆、混合检索、checkpoint
> 运行时恢复、异步 Memory ingest pipeline 与 deterministic benchmark，解决上下文遗忘、
> 检索退化、主链路阻塞和任务中断后的状态重建问题。

## Resume / README Bullets

- Hybrid Retrieval: implemented a hybrid retrieval pipeline with keyword
  candidates, vector candidates, reranking, diagnostics, and a vector-only
  control mode. In the deterministic v3.1 real-agent workload benchmark
  (Milvus + BGE-Small, shared namespace candidate pool), Recall@5 improved from
  0.6667 to 0.8583 vs vector-only, a +28.75% relative lift.
- Runtime Recovery: implemented Task DAG, WAL, checkpoint/recover, branch/merge,
  request-level Execution ID replay idempotency, conversation state recovery,
  tool failure state recovery, and LLM timeout retry state recovery. In the
  deterministic runtime recovery benchmark, 32/32 covered cases across service
  restart, tool failure, LLM exception, state integrity, and concurrency passed,
  a 100.00% covered-case recovery success rate.
- Async Memory Pipeline: implemented async memory ingest covering extraction,
  summary, semantic split, embedding, L1 admission, L2 indexing, and L3 archive.
  In the deterministic Docker-backed Milvus/MinIO main-path benchmark, moving
  those stages off the request main path reduced P99 latency from 1172.50 ms to
  220.34 ms and average latency from 829.40 ms to 186.64 ms, a 77.50% average
  reduction, while main-path success and L2/L3 readiness success remained
  100.00%.
- Tiered Memory Storage: implemented L1 Caffeine hot cache, L2 Milvus vector
  retrieval, and L3 MinIO cold archive, with recency/similarity/importance
  scoring, pin/regret signals, namespace quotas, async L2/L3 persistence, and
  feedback-driven recall diagnostics.
- Engineering Delivery: organized as a Maven multi-module Spring Boot project
  with Docker Compose dependencies, eval CLI/reporting, Prometheus alert assets,
  and focused regression tests for retrieval, eval reporting, runtime recovery,
  async memory pipeline, execution idempotency, and task runtime APIs.

Chinese resume-ready version:

- **混合检索 Recall@5 提升 +28.75%**：构建关键词召回 + 向量召回 +
  reranking + diagnostics 的 Hybrid Retrieval Pipeline，并提供 Vector-only
  control mode；在自建 deterministic recall benchmark（v3.1 real-agent
  workload，Milvus + BGE-Small，shared namespace candidate pool）中，Hybrid
  Retrieval 的 Recall@5 从 0.6667 提升到 0.8583，relative lift 为 +28.75%。
- **运行时恢复 covered-case success rate 100.00%**：实现 Task DAG、WAL、
  checkpoint/recover、branch/merge、Execution ID replay 幂等、Conversation
  状态恢复、Tool Failure 状态恢复与 LLM Timeout retry 状态恢复；在扩展版
  deterministic runtime recovery benchmark 中，覆盖 Service Restart / Tool
  Failure / LLM Exception / State Integrity / Concurrency 五类异常的 32/32
  covered cases 通过。
- **异步 Memory Pipeline 主链路 latency 平均降低 77.50%**：将 Memory 抽取 /
  摘要 / 语义切分 / Embedding / L1 admission / L2 indexing / L3 archive
  从请求主路径解耦到后台；在 Docker-backed Milvus/MinIO deterministic
  main-path benchmark 中，主路径 P99 latency 从 1172.50 ms 降至 220.34 ms，
  平均 latency 从 829.40 ms 降至 186.64 ms，平均降低 77.50%，主路径与
  L2/L3 readiness success rate 均为 100.00%。
- **三级记忆存储与召回治理**：设计 L1 Caffeine 热缓存、L2 Milvus 向量检索、
  L3 MinIO 冷归档，结合 recency/similarity/importance 评分、pin/regret
  信号、namespace quota、异步 L2/L3 持久化与 feedback diagnostics，降低长任务中的关键记忆遗漏风险。
- **工程化交付**：基于 Maven 多模块 + Spring Boot 组织工程，配套 Docker
  Compose 依赖、eval CLI/report 输出、Prometheus 告警资产，以及覆盖检索、评测报告、
  runtime recovery、异步 Memory Pipeline、幂等和任务 runtime API 的回归测试。

## Compact Resume Header

Chinese:

> Vortex | Java 实现的 Agent Memory & RAG Runtime 基础设施。基于 Java 21
> + Spring Boot + Milvus + Caffeine + MinIO 设计长期记忆、混合检索、运行时恢复与
> 异步 Memory Pipeline；deterministic benchmark 中 Recall@5 relative lift
> +28.75%，covered-case recovery success rate 100.00%，Memory Pipeline
> 主路径平均 latency 降低 77.50%。

## Claims Allowed Today

- Hybrid retrieval has benchmark evidence for Recall@5 relative lift +28.75%
  vs vector-only on the v3.1 real-agent workload.
- Runtime recovery has deterministic covered-case evidence: 32/32 cases passed,
  100.00% covered-case recovery success rate.
- Async memory pipeline has deterministic Docker-backed main-path evidence: P99
  latency reduced from 1172.50 ms to 220.34 ms, average latency reduced from
  829.40 ms to 186.64 ms, a 77.50% average reduction, and main-path plus L2/L3
  readiness success rates were 100.00%.
- L1/L2/L3 storage, async memory ingest, diagnostics, and eval CLI/reporting
  are implemented.

## Claims Not Allowed

- Do not claim LLM answer accuracy improved by +28.75%; the recall metric is
  deterministic retrieval recall, not generation correctness.
- Do not claim all workloads, all TopK values, or all production traffic receive
  the same recall lift.
- Do not claim production Agent Runtime recovery success rate is 100%; the
  recovery number is covered-case benchmark success.
- Do not claim full end-to-end Agent execution latency improved; the benchmark
  excludes external LLM generation.
- Do not claim `99.99%` main-path latency reduction; that was the older
  request-admission/enqueue-only benchmark wording.
- Do not claim LLM generation latency improved; generation is not part of the
  async memory pipeline benchmark.
- Do not claim recall latency improved based on the async memory pipeline
  benchmark.
- Do not hide async readiness latency when discussing latency tradeoffs.

## Evidence References

- Recall benchmark evidence: `ops/runbooks/vortex-recall-benchmark-evidence-20260626.md`
- Recall@5 report: `ops/eval-reports/20260626-recall-benchmark-v3-1-top5-shared-001/recall-benchmark-20260625-162412.md`
- Runtime recovery evidence: `ops/runbooks/vortex-runtime-recovery-benchmark-evidence-20260627.md`
- Runtime recovery report: `ops/eval-reports/20260629-runtime-recovery-benchmark-003/runtime-recovery-benchmark-20260629-135453.md`
- Main-path latency evidence: `ops/runbooks/vortex-main-path-latency-benchmark-evidence-20260629.md`
- Main-path latency report: `ops/eval-reports/20260629-main-path-latency-benchmark-003/async-pipeline-latency-benchmark-20260629-151448.md`
- Replaced async admission-only evidence: `ops/runbooks/vortex-async-pipeline-latency-benchmark-evidence-20260628.md`

## Current Placeholder Values

The headline quantitative placeholders now have defensible evidence:

- `Recall@K +XX%`: use `Recall@5 +28.75% relative lift`.
- `恢复成功率 XX%`: use `32/32 covered cases passed; covered-case recovery success rate 100.00%`.
- `主链路延迟降低 XX%`: use `P99 1172.50 ms -> 220.34 ms; average 829.40 ms -> 186.64 ms; average reduction 77.50%`.