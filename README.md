# Vortex

AI Agent 记忆与状态管理内核。为长时运行的 LLM Agent 提供三层分级记忆（L1/L2/L3）、语义淘汰、任务 DAG 快照与增量恢复。

## 核心能力

**分级记忆（HMC）**
- L1 — Caffeine 热缓存，token 配额管理，毫秒级读写
- L2 — Milvus 向量库，BGE-Small-ZH 512 维嵌入，语义相似度召回
- L3 — MinIO 对象存储，冷存档与 checkpoint 持久化

**语义淘汰**
- 评分公式：`score = α·recency + β·similarity + γ·importance`
- 推理链分组淘汰，避免拆散上下文
- `AdaptiveWeightLearner`：基于用户反馈的 bandit 在线学习，自动调整 α/β/γ

**任务 DAG 快照**
- WAL（Write-Ahead Log）+ FULL/DELTA 增量 checkpoint 链
- 恢复流程：FULL → DELTA₁ → ... → WAL replay，exactly-once 语义
- 自动 checkpoint 调度（action 计数 + 时间双触发）
- 缓存驱逐时紧急 checkpoint，防止数据丢失

**语义分页**
- K-Means++ 聚类将 fragment 组织成 SemanticPage
- 三种预取策略：DAG 拓扑 BFS、语义邻域、Branch 投机预取
- 页表持久化到 L3，重启后自动恢复

## 架构

```
┌─────────────────────────────────────────────────────────┐
│                    vortex-app (REST API)                 │
│          MemoryController    TaskController              │
└──────────────────┬──────────────────┬───────────────────┘
                   │                  │
┌──────────────────▼──────────────────▼───────────────────┐
│                   vortex-kernel                          │
│  HierarchicalMemoryController   SnapshotService          │
│  SemanticEvictionPolicy         ActionLogWriter/Reader   │
│  AdaptiveWeightLearner          IncrementalCheckpoint    │
│  SemanticPageTable              BranchManager            │
│  PrefetchEngine                 DotGraphExporter         │
└──────────────────┬──────────────────┬───────────────────┘
                   │                  │
┌──────────────────▼──────────────────▼───────────────────┐
│                   vortex-storage                         │
│  L1HotStore (Caffeine)   L2WarmStore (Milvus)            │
│  L3ColdStore (MinIO)                                     │
└─────────────────────────────────────────────────────────┘
```

## 快速启动

**前提**：JDK 21、Maven 3.9+、Docker Desktop

```bash
# 1. 启动基础设施
bash ops/compose-up.sh

# 2. 启动应用
mvn spring-boot:run -pl vortex-app
```

如果你在 Windows PowerShell 下运行，也可以使用：

```powershell
.\ops\compose-start.ps1
```

停止服务但保留容器（这样它们会继续显示在 Docker Desktop 的 Containers 里，后续可直接点 Start）：

```bash
bash ops/compose-stop.sh
```

或：

```powershell
.\ops\compose-stop.ps1
```

只有在你明确想删除容器、让它们从 Docker Desktop 列表中消失时，才运行：

```bash
docker compose down
```

应用默认监听 `http://localhost:8080`。
启动后可直接访问 `http://localhost:8080/swagger-ui.html` 查看 OpenAPI 文档。

## 运行测试

```bash
# 单元测试（无需 Docker）
mvn test -pl vortex-common,vortex-kernel,vortex-storage

# 默认集成回归（需要 Docker，自动启停 compose）
mvn verify -pl vortex-app -am
```

默认集成测试覆盖：memory store → evict → L2 recall → L1 re-admission、checkpoint → recover、delta chain 恢复、feedback → 权重演化。

LLM memory baseline 的本地/CI 治理门禁不调用真实模型，也不需要 API Key；它只验证当前代码中的 profile、`ops/eval-fixtures/baselines` 中已接受的 v3.1 official strict 证据汇总，以及每轮既有报告是否仍通过 strict verifier：

```powershell
powershell -ExecutionPolicy Bypass -File .\ops\run-baseline-governance-check.ps1
```

如果你希望 `verify` 后保留本地 compose 服务继续运行：

```bash
mvn verify -pl vortex-app -am -Dvortex.it.skipComposeDown=true
```

`FullLifecycleIT` 是可选的 Testcontainers 版端到端生命周期测试，默认不跑。需要显式启用时：

```bash
mvn verify -pl vortex-app -am -Dvortex.it.fullLifecycleExclude= -Drun.full.lifecycle.it=true
```

## API 速览

### 记忆子系统

```bash
# 存储文本（自动分片 + 嵌入）
curl -X POST http://localhost:8080/api/v1/memory/store \
  -H "Content-Type: application/json" \
  -d '{"content": "Java 线程安全：synchronized 保证可见性与原子性", "namespace": "session-1"}'

# 语义召回
curl -X POST http://localhost:8080/api/v1/memory/recall \
  -H "Content-Type: application/json" \
  -d '{"query": "Java 并发锁机制", "namespace": "session-1", "topK": 5, "tokenBudget": 2048}'

# 反馈（驱动自适应权重学习）
curl -X POST http://localhost:8080/api/v1/memory/feedback \
  -H "Content-Type: application/json" \
  -d '{"recallSessionId": "<id>", "usedFragmentIds": ["<frag-id>"], "answerAccepted": true}'
```

### 任务 DAG 子系统

```bash
# 创建任务
curl -X POST http://localhost:8080/api/v1/tasks \
  -H "Content-Type: application/json" \
  -d '{"description": "分析代码库", "namespace": "agent-1"}'

# 追加 DAG 节点
curl -X POST http://localhost:8080/api/v1/tasks/{taskId}/nodes \
  -H "Content-Type: application/json" \
  -d '{"type": "THOUGHT", "content": "需要先理解项目结构"}'

# 创建 checkpoint
curl -X POST http://localhost:8080/api/v1/tasks/{taskId}/checkpoint

# 从 checkpoint 恢复
curl -X POST http://localhost:8080/api/v1/tasks/{taskId}/recover \
  -H "Content-Type: application/json" \
  -d '{"checkpointId": "<checkpoint-id>"}'

# 分页列出任务
curl "http://localhost:8080/api/v1/tasks?page=0&size=20"

# 更新任务上下文
curl -X PUT http://localhost:8080/api/v1/tasks/{taskId}/context \
  -H "Content-Type: application/json" \
  -d '{"key":"mode","value":"strict"}'

# 切换 branch
curl -X POST http://localhost:8080/api/v1/tasks/{taskId}/branch/switch \
  -H "Content-Type: application/json" \
  -d '{"branchId":"<branch-id>"}'

# 标记任务失败
curl -X POST http://localhost:8080/api/v1/tasks/{taskId}/fail

# 导出 DAG（Graphviz DOT 格式，可按 branch 过滤）
curl "http://localhost:8080/api/v1/tasks/{taskId}/dag?branchId=<branch-id>"
```

### 观测端点

```bash
curl http://localhost:8080/api/v1/memory/health    # 真实内存健康探针（UP=200, 非UP=503，含 summary/details）
curl http://localhost:8080/api/v1/memory/health/catalog # 故障字典 / alert / runbook 映射
curl http://localhost:8080/api/v1/memory/slo       # SLO 指标快照
curl http://localhost:8080/api/v1/memory/slo/report # SLO + 诊断摘要（预取/遗憾/分页/学习，含 typed diagnosticSignals）
curl http://localhost:8080/api/v1/memory/learning  # 自适应权重状态
curl http://localhost:8080/actuator/metrics        # Micrometer 指标
```

`/api/v1/memory/health` 的 `summary.code` 与 Prometheus 告警规则中的 `health_code` 标签保持一致，可直接做跨系统关联。
`/api/v1/memory/health/catalog` 会返回每个 `code` 的严重级别、领域、告警名和 runbook 路径，同时附带 `migrationGuide` 和 `compatibility` 元数据；应用日志中的 `memory_health_*` 事件也使用同一套 code。
`/api/v1/memory/slo/report` 与 `/api/v1/memory/health.details` 中的 `diagnosticSignals` 会直接输出 `code/severity/source/message/attributes`，便于日志、告警和接口统一消费。
checkpoint/WAL 恢复链路和 memory persistence 链路会输出统一的 `memory_durability_degraded` / `memory_durability_recovered` 日志，并带上 `healthCode`、`chain`、`phase`、`failureReason` 字段；迁移细节见 `ops/runbooks/memory-health-migration.md`。
