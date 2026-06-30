# Vortex 下一轮开发交接状态

生成日期：2026-06-25

本文件用于下一次对话继续开发。下一轮建议先读：

- `ops/runbooks/目标.md`
- `ops/runbooks/未完成内容.md`
- 本文件：`ops/runbooks/vortex-next-dev-handoff-20260625.md`

## 当前目标

用户目标是把当前仓库逐步做成 `目标.md` 描述的项目形态：

- Java 21 + Spring Boot + Maven 多模块。
- Agent Memory & RAG Runtime 基础设施。
- 长期记忆 + 混合检索 + 运行时恢复。
- Milvus / Redis / Caffeine / MinIO / Docker / GitHub Actions。
- 可量化指标：Recall@K 提升、恢复成功率、主链路延迟降低。

当前开发策略：优先补真实能力和可验证证据，不用 README 或简历文案提前包装尚未实现的能力。

## 当前仓库状态

当前工作区不是干净状态，且有一些状态不是本轮新增。

`git status --short` 里的重要项：

- `README.md` 显示为 deleted。这是已有工作区状态，本轮没有恢复或删除它。下一轮不要误用 `git checkout -- README.md` 或 `git reset --hard`。
- `ops/runbooks/目标.md`、`ops/runbooks/未完成内容.md` 是用户给的目标对照文件，目前是 untracked。
- `readme-history/`、`简历建议5.md`、`ops/runbooks/vortex-project-status-20260609.md` 等也是已有/外部状态，不要随意清理。
- 本轮主要新增和修改集中在混合检索、eval benchmark 支撑、Redis/Execution ID 幂等、报告输出表格。

最后一次 `git diff --check` 已通过；只剩 Git 的 LF/CRLF warning。

## 本轮已完成能力

### 1. Hybrid Retrieval Pipeline

目标中“关键词召回 + 向量召回 + Re-ranking + 对比单一向量方案”的代码支撑已经补上。

关键文件：

- `vortex-common/src/main/java/com/vortex/common/dto/RetrievalMode.java`
- `vortex-common/src/main/java/com/vortex/common/dto/RecallQuery.java`
- `vortex-common/src/main/java/com/vortex/common/dto/RecallDiagnostics.java`
- `vortex-kernel/src/main/java/com/vortex/kernel/hmc/KeywordRecallIndex.java`
- `vortex-kernel/src/main/java/com/vortex/kernel/hmc/HybridRecallReranker.java`
- `vortex-kernel/src/main/java/com/vortex/kernel/hmc/RecallOrchestrator.java`
- `vortex-kernel/src/test/java/com/vortex/kernel/hmc/KeywordRecallIndexTest.java`
- `vortex-kernel/src/test/java/com/vortex/kernel/hmc/RecallOrchestratorTest.java`

行为摘要：

- `RecallQuery.retrievalMode` 默认是 `HYBRID`。
- `HYBRID` 同时收集关键词候选和向量候选，然后统一 rerank。
- `VECTOR_ONLY` 是 benchmark/control 路径，跳过 keyword branch 和 namespace fallback，用于和 hybrid 对比。
- `KEYWORD_ONLY` 保留为纯关键词路径。
- `RecallDiagnostics` 增加了 retrieval mode、keyword/vector/rerank 计数，方便报告和排障。

注意：这只是 benchmark 支撑，真实 `Recall@K +XX%` 还要跑真实 eval 才能宣称。

### 2. Vector-only Benchmark 支撑

目标中“自建 Benchmark 对比单一向量方案”的框架已经补上。

关键文件：

- `vortex-app/src/main/java/com/vortex/app/eval/LlmMemoryEvalMode.java`
- `vortex-app/src/main/java/com/vortex/app/eval/LlmMemoryEvalRunner.java`
- `vortex-app/src/main/java/com/vortex/app/eval/LlmMemoryEvalReport.java`
- `vortex-app/src/main/java/com/vortex/app/eval/LlmMemoryEvalReportWriter.java`
- `vortex-app/src/test/java/com/vortex/app/eval/LlmMemoryEvalRunnerTest.java`
- `vortex-app/src/test/java/com/vortex/app/eval/LlmMemoryEvalReportWriterTest.java`
- `vortex-app/src/main/resources/application.yml`

行为摘要：

- 新增 `VORTEX_VECTOR_ONLY` eval mode。
- eval runner 会把 mode 对应的 retrieval mode 传给 recall。
- report summary 增加：
  - `recallHitRateLiftVsVectorOnly`
  - `recallHitRateRelativeLiftVsVectorOnly`
- 默认 eval modes 现在包含：
  - `BASELINE_NO_MEMORY`
  - `VORTEX_VECTOR_ONLY`
  - `VORTEX_MEMORY`
  - `VORTEX_RECOVERED_MEMORY`

注意：报告现在可以计算 lift，但实际数值需要运行真实 eval 产出。

### 3. Redis + Execution ID 幂等

目标中“Redis”和“基于 Execution ID 实现幂等”的核心缺口已经补上应用层实现。

关键文件：

- `docker-compose.yml`
- `vortex-app/pom.xml`
- `vortex-app/src/main/resources/application.yml`
- `vortex-app/src/main/java/com/vortex/app/runtime/ExecutionIdProperties.java`
- `vortex-app/src/main/java/com/vortex/app/runtime/ExecutionIdRecord.java`
- `vortex-app/src/main/java/com/vortex/app/runtime/ExecutionIdStore.java`
- `vortex-app/src/main/java/com/vortex/app/runtime/InMemoryExecutionIdStore.java`
- `vortex-app/src/main/java/com/vortex/app/runtime/RedisExecutionIdStore.java`
- `vortex-app/src/main/java/com/vortex/app/runtime/ExecutionIdConfiguration.java`
- `vortex-app/src/main/java/com/vortex/app/runtime/ExecutionIdService.java`
- `vortex-app/src/main/java/com/vortex/app/runtime/ExecutionIdConflictException.java`
- `vortex-app/src/test/java/com/vortex/app/runtime/ExecutionIdServiceTest.java`
- `vortex-app/src/main/java/com/vortex/app/controller/TaskController.java`
- `vortex-app/src/main/java/com/vortex/app/controller/TaskExceptionHandler.java`
- `vortex-app/src/test/java/com/vortex/app/controller/TaskControllerTest.java`

行为摘要：

- `docker-compose.yml` 增加 `redis:7.2-alpine`，volume 为 `redis-data`。
- `vortex-app` 增加 `spring-boot-starter-data-redis`。
- 配置项：
  - `spring.data.redis.host=${REDIS_HOST:localhost}`
  - `spring.data.redis.port=${REDIS_PORT:6379}`
  - `vortex.runtime.execution-id.backend=${VORTEX_EXECUTION_ID_BACKEND:MEMORY}`
  - `vortex.runtime.execution-id.ttl=${VORTEX_EXECUTION_ID_TTL:24h}`
  - `vortex.runtime.execution-id.key-prefix=${VORTEX_EXECUTION_ID_KEY_PREFIX:vortex:execution-id:}`
- 默认后端是 `MEMORY`，便于单测和本地离线运行。
- 设置 `VORTEX_EXECUTION_ID_BACKEND=REDIS` 后使用 Redis 后端。
- `TaskController` 的写操作支持 `X-Execution-Id`：
  - create task
  - complete/fail/delete task
  - append/complete/delete node
  - add edge
  - update context
  - checkpoint
  - recover
  - branch create/switch/merge
- 同一个 Execution ID + 同一请求：回放第一次响应，并返回 header `X-Execution-Id-Replayed: true`。
- 同一个 Execution ID + 不同请求：返回 `409 EXECUTION_ID_CONFLICT`。
- action 执行失败时会释放 reservation，允许重试。
- Redis 后端使用 `SETNX` 语义做原子 reservation。

### 4. Eval Report 表格修复

`LlmMemoryEvalReportWriter` 中新增诊断列后，Markdown 分隔行列数曾经不匹配。本轮已修正：

- Mode Summary 列数匹配。
- Latency Breakdown 列数匹配。
- Recall Diagnostics 列数匹配。
- Generation Telemetry 列数匹配。

## 已验证命令

以下命令已通过：

```powershell
mvn -pl vortex-app -am "-Dtest=ExecutionIdServiceTest,TaskControllerTest,LlmMemoryEvalReportWriterTest,LlmMemoryEvalRunnerTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

```powershell
mvn -pl vortex-app -am test
```

结果摘要：

- `vortex-app -am test` 通过。
- Surefire 总计显示 `Tests run: 125, Failures: 0, Errors: 0, Skipped: 0`。
- 没有运行 Docker Compose 集成测试 / Failsafe `verify`。
- 没有运行真实 LLM eval。

## 当前仍不能宣称完成的目标项

这些仍然不能写成已完成能力：

- `Recall@K +XX%`：现在有 hybrid vs vector-only 的计算框架，但还没有真实 benchmark 数值。
- `恢复成功率 XX%`：已有 checkpoint/recover 机制和 SLO 指标，但还没有统一的 runtime recovery benchmark 结果。
- `主链路延迟降低 XX%`：还没有 async vs sync 的性能对比报告。
- 完整 Agent Runtime：Task DAG/WAL/checkpoint 已有，但 Conversation / Tool 状态没有作为统一 runtime snapshot 完整持久化。
- Tool Failure 恢复：还没有完整 ToolExecution runtime 和失败恢复闭环。
- LLM Timeout 任务级恢复：generation/eval 有 timeout 错误处理，但还没有任务级 resume flow。
- Memory 抽取 / 摘要 / Embedding / 索引的完整异步 pipeline：L2/L3 持久化异步已有，但整条 pipeline 还不完整。
- Frequency 维度评分：当前更准确是 recency + similarity + importance，并有 pin/regret/namespace quota；如果要写 Importance/Frequency/Recency，需要补 frequency 统计或调整目标文案。

## 下一轮推荐开发顺序

### 优先级 1：跑真实 eval，产出 Recall@K / lift 证据

目标：把“Hybrid Retrieval Pipeline 提升 Recall@K +XX%”从代码支撑变成可复现实测指标。

建议做法：

- 使用包含 `VORTEX_VECTOR_ONLY` 和 `VORTEX_MEMORY` 的 eval modes。
- 跑真实 LLM memory eval，生成 JSON + Markdown report。
- 汇总：
  - vector-only recall hit rate
  - hybrid recall hit rate
  - absolute lift
  - relative lift
  - case-level diagnostics
- 如果真实 eval 太慢，先做一版离线 deterministic recall benchmark，只评 recall，不跑 generation。

### 优先级 2：补 Runtime Snapshot 的 Conversation / Tool 状态

目标：对齐 `目标.md` 的 “Task / Conversation / Memory / Tool 状态持久化”。

建议新增或扩展：

- `ConversationState`
- `ToolExecutionState`
- `ToolExecutionStatus`
- `TaskState` 中挂载 conversation/tool state，或定义更明确的 `RuntimeSnapshot`。
- WAL operation：
  - conversation append/update
  - tool start
  - tool success
  - tool failure
  - llm call started
  - llm timeout / retry
- checkpoint/recover 应覆盖这些状态。

### 优先级 3：补恢复 benchmark

目标：产出“恢复成功率 XX%”。

建议 benchmark 场景：

- checkpoint 后进程内 cache eviction，再 recover。
- Tool Failure 后从 checkpoint 继续。
- LLM Timeout 后保留 task/conversation/tool 状态并 resume。
- 重复 recover 保持幂等。
- Execution ID 重放不产生重复节点/重复 checkpoint。

输出：

- `ops/eval-reports/.../runtime-recovery-benchmark.json`
- `ops/eval-reports/.../runtime-recovery-benchmark.md`
- 指标：total cases、success cases、recovery success rate、failure reason 分类。

### 优先级 4：补 async pipeline latency benchmark

目标：产出“主链路延迟降低 XX%”。

建议先明确两个模式：

- sync baseline：store 阻塞到 embedding + L2 + L3 完成。
- async pipeline：主链路只完成 L1 admission，embedding/index/archive 后台完成。

输出：

- p50 / p95 / p99 store latency
- recall readiness latency
- persistence success rate
- async 相对 sync 的主链路降低比例。

## 下一轮注意事项

- 不要恢复或删除 `README.md`，除非用户明确要求。当前 deleted 状态可能是用户有意为之。
- 不要清理 untracked runbooks / readme-history / `简历建议5.md`。
- 不要为了目标文案硬写指标；必须从 eval/report 中拿数字。
- Redis 当前是应用层 execution-id 后端，不要宣称 Redis 已用于所有 memory cache 或 queue。
- `VORTEX_EXECUTION_ID_BACKEND=REDIS` 需要 Redis 可用；默认 MEMORY 是为了测试和本地启动稳。
- 若继续改已有文件，注意 PowerShell `Set-Content -Encoding UTF8` 会写 BOM；Java 文件要用 UTF-8 without BOM。

