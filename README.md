# Vortex

Vortex 是一个面向长时运行 AI Agent 的记忆与状态管理内核。它把 Agent 运行中产生的事实、偏好、工具结果、任务状态和 checkpoint 组织成可召回、可淘汰、可恢复、可观测的分级记忆系统。

当前项目不是一个完整的多租户 SaaS 产品，也不是通用 Agent 执行框架。它的定位更准确地说是：

```text
已通过真实 LLM 长任务 Agent memory workload 验证的分级记忆与状态管理内核。
```

## 当前状态

当前最强的已验证结论来自 v3.1 real-agent workload official strict baseline：

| 项 | 当前值 |
| --- | --- |
| Profile | `official-v3.1-real-agent-workload-strict` |
| Dataset | `classpath:llm-memory-eval-set-v3-1-real-agent-workload.json` |
| Case count | 20 |
| Modes | `Baseline-NoMemory`, `Vortex-Memory`, `Vortex-RecoveredMemory` |
| Accepted evidence | `ops/eval-fixtures/baselines/20260603-v3-1-real-agent-workload-official-strict-audit-003` |
| 结果 | 3/3 轮通过；`Baseline-NoMemory = 0/20`，`Vortex-Memory = 20/20`，`Vortex-RecoveredMemory = 20/20` |

这个结论说明：在受控的真实 LLM 长任务记忆评测中，接入 Vortex 后模型可以找回并使用长期上下文；在压低 L1 容量、强制触发 L2 recovery 的场景下也能保持正确。

需要明确边界：这证明的是“受控 workload 下的真实模型记忆增强能力”，不是生产级多租户、安全、长期高并发平台已经完成。

## 项目亮点

- 设计并实现 AI Agent 三层记忆内核：L1 Caffeine 热缓存、L2 Milvus 向量召回、L3 MinIO 持久化。
- 支持语义召回、tag/namespace 过滤、token budget、feedback-driven adaptive ranking、pin/unpin 和语义淘汰。
- 实现任务 DAG 状态管理：WAL、FULL/DELTA checkpoint、branch、merge、recover、Graphviz DOT export。
- 提供 semantic paging / prefetch、SLO health、Prometheus 指标、health signal catalog 和 runbook 体系。
- 建立真实 LLM memory eval 与无模型 governance：v3.1 official strict fixture 可在 CI 中复验，不依赖 API key。

## 核心能力

### 1. 三层分级记忆

| 层级 | 实现 | 作用 |
| --- | --- | --- |
| L1 Hot | Caffeine | 热记忆缓存、token capacity 管理、毫秒级读写 |
| L2 Warm | Milvus | 向量检索、namespace/tag 过滤、L1 eviction 后的语义恢复 |
| L3 Cold | MinIO | fragment 冷存档、checkpoint/WAL 相关持久化、page table 持久化 |

写入路径由 `HierarchicalMemoryController` 驱动：原始文本经 `SemanticTextSplitter` 分片，使用 BGE embedding，进入 L1，并异步持久化到 L2/L3。

L3 默认 key layout：

```text
fragments/{fragmentId}.json
checkpoints/{taskId}/{checkpointId}.kryo
checkpoints/{taskId}/{checkpointId}.meta.json
system/semantic-page-table.bin
```

### 2. 语义召回与反馈学习

召回路径由 `RecallOrchestrator` 驱动：

1. 先在 L1 内按 query embedding 做语义排序。
2. 支持 namespace、required tags、`topK`、`tokenBudget` 和 `scenario`。
3. L1 不足时查 L2 Milvus。
4. L2 命中后补全 fragment，并重新 admit 回 L1。
5. 每次召回生成 `recallSessionId`，后续 feedback 会驱动 `AdaptiveWeightLearner`。

`MemoryScenario` 当前支持：

```text
CHAT
CODING
SEARCH
```

### 3. 语义淘汰

L1 淘汰不是简单 LRU，而是 semantic-LRU 变体：

```text
score = alpha * recency + beta * similarity + gamma * importance
```

低分优先淘汰。实现还包含：

- `reasoningChainId` 分组淘汰，避免拆散推理链上下文。
- redundancy penalty / novelty bonus。
- pinned fragment 保护。
- namespace quota、regret 追踪和 SLO 诊断。
- active / shadow / baseline ranking，用 feedback 调整权重。

### 4. 任务 DAG、checkpoint 与恢复

`SnapshotService` 是任务状态门面，覆盖：

- task lifecycle：create/list/get/complete/fail/delete
- DAG node/edge mutation
- task context upsert/delete
- FULL/DELTA checkpoint
- WAL replay
- branch/create/switch/merge
- Graphviz DOT export

关键语义：

```text
validate-before-WAL
WAL-before-state
FULL -> DELTA... -> WAL replay
```

终态任务会尝试 final checkpoint；删除任务会先记录 durable delete intent，再清理 WAL/checkpoint artifacts。

### 5. 语义分页与预取

语义分页默认启用：

- page table 持久化到 `system/semantic-page-table.bin`。
- 基于 embedding 的 K-Means / 增量分配组织 `SemanticPage`。
- page fault 时整页加载回 L1。
- 预取策略包括 DAG topology、semantic neighborhood、branch speculative。
- 预取策略会记录命中率，并参与诊断。

### 6. 观测、SLO 与治理

运行时观测入口：

- `/api/v1/memory/health`
- `/api/v1/memory/health/catalog`
- `/api/v1/memory/slo`
- `/api/v1/memory/slo/report`
- `/actuator/prometheus`

`/api/v1/memory/health` 返回 `status`、`summary`、`statusReason`、`details`、L1 token 使用量等信息。`UP` 和 `DEGRADED` 返回 HTTP 200；`DOWN` 返回 HTTP 503。

配套资产：

- Prometheus rules：`ops/prometheus/vortex-memory-slo-alerts.yml`
- Alertmanager route example：`ops/alertmanager/memory-health-routes.yml`
- Grafana query reference：`ops/grafana/memory-health-queries.md`
- Health signal runbook：`ops/runbooks/memory-health-signals.md`
- Migration guide：`ops/runbooks/memory-health-migration.md`

## 架构

```text
┌───────────────────────────────────────────────────────────┐
│                     vortex-app                             │
│  REST API, Actuator, OpenAPI, eval CLI, integration tests   │
│  MemoryController, TaskController, health indicators        │
└───────────────────────────┬───────────────────────────────┘
                            │
┌───────────────────────────▼───────────────────────────────┐
│                    vortex-kernel                           │
│  HMC, recall, eviction, learning, SLO, generation           │
│  snapshot, WAL, checkpoint, branch, semantic paging         │
└───────────────────────────┬───────────────────────────────┘
                            │
┌───────────────────────────▼───────────────────────────────┐
│                    vortex-storage                          │
│  L1 Caffeine, L2 Milvus, L3 MinIO                           │
└───────────────────────────┬───────────────────────────────┘
                            │
┌───────────────────────────▼───────────────────────────────┐
│                    vortex-common                           │
│  model, DTO, serialization, exceptions, shared contracts    │
└───────────────────────────────────────────────────────────┘
```

## 仓库结构

```text
.
├── vortex-common/        # 公共模型、DTO、序列化、异常、健康信号
├── vortex-storage/       # L1/L2/L3 存储 API 与实现
├── vortex-kernel/        # 记忆、召回、淘汰、学习、快照、分页、generation
├── vortex-app/           # Spring Boot REST API、health、eval CLI、集成测试
├── ops/                  # compose、治理脚本、runbook、监控配置、eval fixtures
├── demo/                 # 一键 demo 脚本
├── models/bge-small-zh/  # 默认本地 BGE-Small-ZH 模型目录
├── docker-compose.yml    # etcd + Milvus + MinIO
└── pom.xml               # Maven multi-module parent
```

## 技术栈

- Java 21
- Maven 3.9+
- Spring Boot 3.3.4
- Caffeine 3.1.8
- Milvus SDK 2.4.4
- MinIO 8.5.11
- Kryo 5.6.0
- DJL 0.28.0
- ONNX Runtime 1.18.0
- Testcontainers 2.0.2

根 POM 对编译和测试启用了 `--enable-preview`，测试阶段也包含必要的 `--add-opens`。

## 快速启动

前置条件：

- JDK 21
- Maven 3.9+
- Docker Desktop / Docker Compose
- 本地 BGE 模型目录包含 `model.onnx` 和 `tokenizer.json`，默认路径是 `models/bge-small-zh`

### Windows PowerShell

```powershell
# 1. 启动并等待 etcd / Milvus / MinIO 健康
docker compose up -d --wait

# 2. 打包应用
mvn -pl vortex-app -am -DskipTests package

# 3. 启动应用
java -jar .\vortex-app\target\vortex-app-0.1.0-SNAPSHOT-exec.jar
```

### Bash

```bash
# 1. 启动并等待依赖健康
docker compose up -d --wait

# 2. 打包应用
mvn -pl vortex-app -am -DskipTests package

# 3. 启动应用
java -jar vortex-app/target/vortex-app-0.1.0-SNAPSHOT-exec.jar
```

开发时也可以直接运行：

```bash
mvn spring-boot:run -pl vortex-app
```

启动后：

- 应用：`http://localhost:8080`
- Swagger UI：`http://localhost:8080/swagger-ui.html`
- Actuator health：`http://localhost:8080/actuator/health`
- Prometheus：`http://localhost:8080/actuator/prometheus`
- MinIO Console：`http://localhost:9001`，默认账号密码 `minioadmin` / `minioadmin`

### 停止服务

停止 compose 服务但保留容器：

```powershell
.\ops\compose-stop.ps1
```

```bash
bash ops/compose-stop.sh
```

删除 compose 容器和网络：

```bash
docker compose down
```

## Demo

PowerShell 一键 demo 会启动依赖、打包应用、启动应用 jar、执行 store/recall/task/checkpoint/recover/health 流程，最后停止本次启动的应用进程：

```powershell
.\demo\run-demo.ps1
```

如果应用已经启动，可以只跑 API walkthrough：

```bash
BASE_URL=http://localhost:8080 bash ops/demo.sh
```

## API 速览

### Memory API

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/api/v1/memory/store` | 存储原始文本，自动分片、嵌入、写入三层记忆 |
| `POST` | `/api/v1/memory/store/fragment` | 写入预构造 `MemoryFragment` |
| `GET` | `/api/v1/memory/fragment/{fragmentId}` | 查询 fragment |
| `DELETE` | `/api/v1/memory/fragment/{fragmentId}` | 删除 fragment |
| `POST` | `/api/v1/memory/recall` | 语义召回 |
| `POST` | `/api/v1/memory/feedback` | 提交答案反馈，驱动自适应学习 |
| `POST` | `/api/v1/memory/pin` | 临时 pin fragment |
| `POST` | `/api/v1/memory/unpin` | 取消 pin |
| `GET` | `/api/v1/memory/learning?scenario=chat` | 查看学习状态 |
| `GET` | `/api/v1/memory/slo` | SLO 快照 |
| `GET` | `/api/v1/memory/slo/report` | 诊断报告 |
| `GET` | `/api/v1/memory/health` | 记忆子系统健康 |
| `GET` | `/api/v1/memory/health/catalog` | 健康信号字典 |

存储文本：

```bash
curl -X POST http://localhost:8080/api/v1/memory/store \
  -H "Content-Type: application/json" \
  -d '{
    "content": "Java synchronized provides mutual exclusion and visibility guarantees.",
    "namespace": "session-1",
    "tags": ["java", "concurrency"],
    "reasoningChainId": "chain-1",
    "pinTtlMillis": 60000
  }'
```

语义召回：

```bash
curl -X POST http://localhost:8080/api/v1/memory/recall \
  -H "Content-Type: application/json" \
  -d '{
    "query": "Java concurrency lock visibility",
    "namespace": "session-1",
    "topK": 5,
    "tokenBudget": 2048,
    "tags": ["java"],
    "scenario": "coding"
  }'
```

提交反馈：

```bash
curl -X POST http://localhost:8080/api/v1/memory/feedback \
  -H "Content-Type: application/json" \
  -d '{
    "recallSessionId": "<recallSessionId>",
    "usedFragmentIds": ["<fragmentId>"],
    "answerAccepted": true
  }'
```

pin / unpin：

```bash
curl -X POST http://localhost:8080/api/v1/memory/pin \
  -H "Content-Type: application/json" \
  -d '{"fragmentId":"<fragmentId>","pinTtlMillis":60000}'

curl -X POST http://localhost:8080/api/v1/memory/unpin \
  -H "Content-Type: application/json" \
  -d '{"fragmentId":"<fragmentId>"}'
```

### Task API

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/api/v1/tasks` | 创建任务 |
| `GET` | `/api/v1/tasks?page=0&size=50` | 分页列出 active tasks |
| `GET` | `/api/v1/tasks/{taskId}` | 查询任务 |
| `POST` | `/api/v1/tasks/{taskId}/complete` | 标记完成 |
| `POST` | `/api/v1/tasks/{taskId}/fail` | 标记失败 |
| `DELETE` | `/api/v1/tasks/{taskId}` | 删除任务及 durable artifacts |
| `POST` | `/api/v1/tasks/{taskId}/nodes` | 追加 DAG 节点 |
| `POST` | `/api/v1/tasks/{taskId}/nodes/complete` | 完成 DAG 节点 |
| `DELETE` | `/api/v1/tasks/{taskId}/nodes/{nodeId}` | 删除 DAG 节点 |
| `POST` | `/api/v1/tasks/{taskId}/nodes/edge` | 添加 DAG 边 |
| `PUT` | `/api/v1/tasks/{taskId}/context` | upsert/delete task context |
| `POST` | `/api/v1/tasks/{taskId}/checkpoint` | 创建 checkpoint |
| `GET` | `/api/v1/tasks/{taskId}/checkpoints` | 列出 checkpoints |
| `POST` | `/api/v1/tasks/{taskId}/recover` | 从 checkpoint 或最新 durable state 恢复 |
| `GET` | `/api/v1/tasks/{taskId}/branches` | 列出 branches |
| `POST` | `/api/v1/tasks/{taskId}/branch` | 创建 branch |
| `POST` | `/api/v1/tasks/{taskId}/branch/switch` | 切换 active branch |
| `POST` | `/api/v1/tasks/{taskId}/merge` | 合并 branch |
| `GET` | `/api/v1/tasks/{taskId}/dag?branchId=...` | 导出 Graphviz DOT |

创建任务、追加节点、checkpoint、恢复：

```bash
curl -X POST http://localhost:8080/api/v1/tasks \
  -H "Content-Type: application/json" \
  -d '{"description":"Analyze repository","namespace":"agent-1"}'

curl -X POST http://localhost:8080/api/v1/tasks/{taskId}/nodes \
  -H "Content-Type: application/json" \
  -d '{"type":"THOUGHT","content":"Read module boundaries first."}'

curl -X POST http://localhost:8080/api/v1/tasks/{taskId}/checkpoint

curl -X POST http://localhost:8080/api/v1/tasks/{taskId}/recover \
  -H "Content-Type: application/json" \
  -d '{"checkpointId":"<checkpointId>"}'
```

branch 与 DOT：

```bash
curl -X POST http://localhost:8080/api/v1/tasks/{taskId}/branch \
  -H "Content-Type: application/json" \
  -d '{"branchName":"experiment-a","sourceNodeId":"<nodeId>"}'

curl -X POST http://localhost:8080/api/v1/tasks/{taskId}/branch/switch \
  -H "Content-Type: application/json" \
  -d '{"branchId":"<branchId>"}'

curl "http://localhost:8080/api/v1/tasks/{taskId}/dag?branchId=<branchId>"
```

## 关键配置

默认配置位于 `vortex-app/src/main/resources/application.yml`。

### 存储

| 配置 / 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `vortex.storage.l1.max-tokens` / `VORTEX_STORAGE_L1_MAX_TOKENS` | `8192` | L1 token capacity |
| `vortex.storage.l2.milvus.host` / `MILVUS_HOST` | `localhost` | Milvus host |
| `vortex.storage.l2.milvus.port` / `MILVUS_PORT` | `19530` | Milvus port |
| `vortex.storage.l2.milvus.collection` / `VORTEX_STORAGE_L2_MILVUS_COLLECTION` | `vortex_memory` | Milvus collection |
| `vortex.storage.l2.embedding-dim` / `VORTEX_L2_EMBEDDING_DIM` | `512` | L2 向量维度 |
| `vortex.storage.l3.minio.endpoint` / `MINIO_ENDPOINT` | `http://localhost:9000` | MinIO endpoint |
| `vortex.storage.l3.minio.bucket` / `MINIO_BUCKET` | `vortex` | MinIO bucket |
| `vortex.storage.l3.minio.key-prefix` / `MINIO_KEY_PREFIX` | 空 | 对象 key 前缀，eval 常用于隔离 |

Milvus collection 维度变更需要显式迁移。只有确认可以删除目标 collection 时才设置：

```powershell
$env:MILVUS_DROP_COLLECTION = "true"
$env:MILVUS_DROP_CONFIRM_TOKEN = "I-KNOW-WHAT-I-AM-DOING"
```

启动一次完成迁移后，应恢复为 false / 空值。

### Embedding

默认使用本地 BGE-Small-ZH：

```text
vortex.kernel.embedding.bge.model-path = models/bge-small-zh
```

该目录必须包含：

```text
model.onnx
tokenizer.json
```

本地排障可以开启 safe-hash mode，但它不是正式语义基线：

```powershell
$env:VORTEX_KERNEL_EMBEDDING_BGE_SAFE_HASH_MODE = "true"
```

可选 DeepSeek cloud embedding：

```powershell
$env:CLOUD_EMBEDDING_ENABLED = "true"
$env:DEEPSEEK_API_KEY = "<api-key>"
$env:VORTEX_L2_EMBEDDING_DIM = "1024"
```

启用 1024 维 cloud embedding 前，Milvus collection 也必须迁移到 1024 维。

### Snapshot / WAL

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `vortex.kernel.snapshot.wal.dir` | `${java.io.tmpdir}/vortex-wal` | WAL 目录 |
| `vortex.kernel.snapshot.checkpoint.format` | `kryo` | checkpoint 格式 |
| `vortex.kernel.snapshot.checkpoint.compression` | `gzip` | checkpoint 压缩 |
| `vortex.kernel.snapshot.checkpoint.max-deltas-before-full` | `10` | delta 链超过该值后创建 FULL checkpoint |
| `vortex.kernel.snapshot.scheduler.enabled` | `true` | 自动 checkpoint 调度 |
| `vortex.kernel.snapshot.scheduler.max-actions-between` | `50` | action 数触发阈值 |
| `vortex.kernel.snapshot.scheduler.max-millis-between` | `60000` | 时间触发阈值 |

### Generation / Eval

应用默认不启用 generation：

```text
vortex.kernel.generation.enabled=false
```

真实 LLM eval 需要显式配置：

```powershell
$env:VORTEX_GENERATION_ENABLED = "true"
$env:VORTEX_GENERATION_BASE_URL = "https://api.openai.com/v1"
$env:VORTEX_GENERATION_API_KEY = "<api-key>"
$env:VORTEX_GENERATION_MODEL = "gpt-5.2"
```

Eval 相关默认项：

```text
vortex.eval.dataset-location=classpath:llm-memory-eval-set.json
vortex.eval.modes=BASELINE_NO_MEMORY,VORTEX_MEMORY,VORTEX_RECOVERED_MEMORY
vortex.eval.report-output-dir=ops/eval-reports
```

正式 baseline/governance 优先使用 `ops/*.ps1` 脚本，不建议手工拼大量环境变量。

## 测试

当前仓库包含 55 个 `*Test.java` / `*IT.java` 测试文件。

单元测试：

```bash
mvn test -pl vortex-common,vortex-kernel,vortex-storage -am
```

应用集成测试：

```bash
mvn verify -pl vortex-app -am
```

`vortex-app` 的 `verify` 会在 `pre-integration-test` 自动执行：

```text
docker compose up -d --wait
```

并在 `post-integration-test` 默认执行：

```text
docker compose down --remove-orphans
```

如果希望 `verify` 后保留 compose 服务：

```bash
mvn verify -pl vortex-app -am -Dvortex.it.skipComposeDown=true
```

`FullLifecycleIT` 默认排除。显式启用：

```bash
mvn verify -pl vortex-app -am -Dvortex.it.fullLifecycleExclude= -Drun.full.lifecycle.it=true
```

CI 当前执行：

```bash
mvn -B test -pl vortex-common,vortex-kernel,vortex-storage -am
mvn -B verify -pl vortex-app -am
```

随后运行 baseline governance 和 learning governance。

## Eval CLI 与治理门禁

`vortex-app` 打包后会生成两个 jar：

```text
vortex-app/target/vortex-app-0.1.0-SNAPSHOT-exec.jar
vortex-app/target/vortex-app-0.1.0-SNAPSHOT-eval-cli.jar
```

Eval CLI 主类是 `LlmMemoryEvalCliApplication`。

打包：

```bash
mvn -pl vortex-app -am -DskipTests package
```

列出 baseline profiles：

```powershell
java -jar .\vortex-app\target\vortex-app-0.1.0-SNAPSHOT-eval-cli.jar verify --list-profiles
```

查看 v3.1 profile：

```powershell
java -jar .\vortex-app\target\vortex-app-0.1.0-SNAPSHOT-eval-cli.jar verify `
  --profile official-v3.1-real-agent-workload-strict `
  --describe
```

注意：`eval-cli verify <report>` 的默认 profile 仍是 `official-v2-strict`，这是为了兼容历史报告。验证 v3.1 报告时必须显式传 `--profile official-v3.1-real-agent-workload-strict`，或使用治理脚本。

### Baseline Governance

本地/CI 默认 baseline 门禁：

```powershell
powershell -ExecutionPolicy Bypass -File .\ops\run-baseline-governance-check.ps1
```

它不调用真实模型，不需要 API key。默认检查：

```text
Profile       = official-v3.1-real-agent-workload-strict
EvidenceStamp = 20260603-v3-1-real-agent-workload-official-strict-audit-003
ReportRoot    = ops/eval-fixtures/baselines
```

快速复验既有 jar 和 fixture：

```powershell
powershell -ExecutionPolicy Bypass -File .\ops\run-baseline-governance-check.ps1 `
  -SkipMavenTest `
  -SkipPackage
```

### Learning Governance

learning-specific workload 用于证明 feedback 后 recall ranking 是否改善。默认 promoted fixture：

```text
Profile       = learning-v1-agent-feedback-audit
EvidenceStamp = 20260609-learning-v1-agent-feedback-hard-governance-001
EvidenceRoot  = ops/eval-fixtures/learning
```

CI 使用 fixture replay：

```powershell
powershell -ExecutionPolicy Bypass -File .\ops\run-learning-governance-check.ps1 `
  -SkipMavenTest `
  -SkipPackage `
  -SkipLearningRun
```

本地完整 deterministic learning workload：

```powershell
powershell -ExecutionPolicy Bypass -File .\ops\run-learning-governance-check.ps1
```

完整 learning run 需要本地 BGE 模型和 Docker compose 依赖，但不调用真实 generation API。

### 真实 LLM Eval / Audit

真实 LLM 单轮 eval：

```powershell
powershell -ExecutionPolicy Bypass -File .\ops\run-real-llm-memory-eval.ps1 `
  -ApiKey "<api-key>" `
  -BaseUrl "https://api.openai.com/v1" `
  -Model "gpt-5.2" `
  -Stamp "manual-v3-1-run" `
  -DatasetLocation "classpath:llm-memory-eval-set-v3-1-real-agent-workload.json" `
  -EvalParallelism 24
```

真实多轮 baseline audit：

```powershell
powershell -ExecutionPolicy Bypass -File .\ops\run-llm-memory-baseline-audit.ps1 `
  -ApiKey "<api-key>" `
  -BaseUrl "https://api.openai.com/v1" `
  -Model "gpt-5.2" `
  -Rounds 3 `
  -DatasetLocation "classpath:llm-memory-eval-set-v3-1-real-agent-workload.json" `
  -AuditStamp "manual-v3-1-audit" `
  -EvalParallelism 24 `
  -FailOnAuditGateFailure
```

脚本会为每次运行隔离 Milvus collection 和 MinIO prefix，并输出 JSON/Markdown 报告到 `ops/eval-reports/<stamp>/`。

## 当前边界与路线

已经实现并有测试/治理覆盖的能力：

- 三层 memory store / recall / feedback 闭环。
- L1 token pressure 下的 L2 recovery。
- 语义淘汰、regret、pin/unpin、namespace quota。
- task DAG、WAL、FULL/DELTA checkpoint、recovery、branch。
- semantic paging / prefetch。
- health catalog、SLO、Prometheus/Grafana/Alertmanager 资产。
- baseline governance、learning governance、真实 LLM eval/audit harness。

尚未完成或尚未充分证明的能力：

- 生产级 auth / RBAC / tenant model。
- namespace ownership、请求限流、审计日志、稳定错误码。
- 长时间高并发、多 namespace、大规模 fragment 的容量压测。
- 真实 Agent runtime 的完整执行器集成。
- 分布式一致性、Raft、多节点调度、跨区域复制。
- provider 成本、配额、模型漂移的完整运营治理。

## 文档地图

核心 runbook：

- `ops/compose-verify.md`
- `ops/runbooks/vortex-project-status.md`
- `ops/runbooks/llm-memory-eval-baseline.md`
- `ops/runbooks/llm-memory-eval-cli-runbook.md`
- `ops/runbooks/llm-memory-eval-evidence-assets.md`
- `ops/runbooks/llm-memory-eval-v3-1-workload-proposal.md`
- `ops/runbooks/vortex-baseline-governance-phase-4-decision.md`
- `ops/runbooks/llm-memory-eval-learning-workload-proposal.md`
- `ops/runbooks/memory-health-signals.md`
- `ops/runbooks/memory-health-migration.md`
- `ops/runbooks/memory-test-plan.md`

监控资产：

- `ops/prometheus/vortex-memory-slo-alerts.yml`
- `ops/grafana/memory-health-queries.md`
- `ops/alertmanager/memory-health-routes.yml`

证据资产：

- `ops/eval-fixtures/baselines/20260603-v3-1-real-agent-workload-official-strict-audit-003`
- `ops/eval-fixtures/learning/20260609-learning-v1-agent-feedback-hard-governance-001`

## License

Vortex 的代码与文档使用 Apache License 2.0，见 `LICENSE`。

仓库中的第三方模型文件、数据集或外部服务名称仍受其各自上游许可和服务条款约束；公开分发前应单独确认这些资产的再分发许可。
