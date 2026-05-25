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
docker compose up -d

# 2. 等待 Milvus 就绪（约 30 秒）
docker compose ps

# 3. 启动应用
mvn spring-boot:run -pl vortex-app
```

应用默认监听 `http://localhost:8080`。

## 运行测试

```bash
# 单元测试（无需 Docker）
mvn test -pl vortex-common,vortex-kernel,vortex-storage

# 完整集成回归（需要 Docker，自动启停 compose）
mvn verify -pl vortex-app -am
```

集成测试覆盖：memory store → evict → L2 recall → L1 re-admission、checkpoint → recover、delta chain 恢复、feedback → 权重演化。

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

# 导出 DAG（Graphviz DOT 格式）
curl http://localhost:8080/api/v1/tasks/{taskId}/dag
```

### 观测端点

```bash
curl http://localhost:8080/api/v1/memory/health    # L1 token 用量
curl http://localhost:8080/api/v1/memory/slo       # SLO 指标快照
curl http://localhost:8080/api/v1/memory/learning  # 自适应权重状态
curl http://localhost:8080/actuator/metrics        # Micrometer 指标
```
