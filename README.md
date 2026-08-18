# Vortex

<p align="center">
  简体中文 | <a href="README_en.md">English</a>
</p>

[![CI](https://github.com/HaibaraAi2517/Vortex/actions/workflows/ci.yml/badge.svg)](https://github.com/HaibaraAi2517/Vortex/actions/workflows/ci.yml)
[![Release: v0.1.1](https://img.shields.io/badge/release-v0.1.1-2EA44F.svg)](docs/releases/v0.1.1.md)
[![License: Apache-2.0](https://img.shields.io/badge/License-Apache--2.0-blue.svg)](LICENSE)
[![Java 21](https://img.shields.io/badge/Java-21-orange.svg)](pom.xml)
[![Spring Boot 3.3](https://img.shields.io/badge/Spring%20Boot-3.3-6DB33F.svg)](vortex-app/pom.xml)
[![Milvus](https://img.shields.io/badge/Milvus-2.4-00A1EA.svg)](docker-compose.yml)

**面向长时运行 AI Agent 的 Memory 与 RAG runtime：跨会话记住上下文，召回正确事实，并在崩溃后恢复任务。基于 Java 21、Spring Boot、Milvus、MinIO、Redis 和 Caffeine 构建。**

`v0.1.1` 是最近一个带完整发布证据的标签版本。Vortex 不是托管 SaaS，而是一个
Agent Memory 与任务恢复内核；仓库把实现代码、确定性基准、故障注入证据和
复现路径放在一起，便于直接核验。当前分支或本地工作区可能已经包含标签之后的
行为变更，不能自动继承 `v0.1.1` 的测试、覆盖率和 benchmark 结论。

`v0.1.1` 标签验证：`548` 个测试零失败、Docker integration `13/13` 通过、五个
代码模块聚合行覆盖率 `74.26%`。这些数字只描述该标签，完整记录见
[v0.1.1 发布说明](docs/releases/v0.1.1.md)。

<p align="center">
  <a href="#演示"><b>演示</b></a> ·
  <a href="#快速开始"><b>快速开始</b></a> ·
  <a href="#三个核心技术决策及取舍"><b>技术决策</b></a> ·
  <a href="docs/benchmark.md"><b>基准证据</b></a> ·
  <a href="docs/architecture.md"><b>详细架构</b></a>
</p>

## 使用前须知

- Vortex 当前适合源码审阅、本机 Demo 和可信隔离网络内的集成试验，不是可直接
  暴露到公网的生产服务。
- 直接在宿主机启动应用时默认仅监听 `127.0.0.1`，安全过滤器保持关闭以兼容本地开发。
  Quickstart 则强制使用 32 字符以上的 Bearer token、namespace allowlist、API 限流和
  审计事件；这是一条可信环境试用边界，不是 OIDC、RBAC 或多租户生产认证。
- 当前仓库只提供源码构建和 Docker Compose 路径，尚未发布可直接依赖的 Maven
  artifacts 或预构建 Docker image。
- Quickstart 只把 Vortex API 发布到 `127.0.0.1:${VORTEX_HTTP_PORT:-8080}`；Redis、
  Milvus、MinIO 及管理端口仅存在于 Compose 网络。启动脚本会生成本次进程使用的随机
  MinIO、Redis 和 Bearer 凭据。

## 演示

<p align="center">
  <img src="docs/assets/quickstart-agent-demo.gif" alt="Vortex 记忆召回与任务恢复演示" width="1000">
</p>

该演示无需外部 LLM API Key：它会写入并召回持久记忆，在任务 Checkpoint 后
停止 Worker，再从恢复出的运行时状态继续执行任务。

## 系统架构

```mermaid
flowchart TB
    A[Agent / Spring AI / LangChain4j] --> API[Vortex REST 与 Java 契约]
    API --> K[Memory 与 Task Kernel]

    subgraph W[写入链路]
        direction LR
        K --> E[切分与本地 Embedding]
        E --> L1[L1 Caffeine write-through]
        L1 --> ACK[返回并保证 read-your-own-write]
        L1 --> P[有界异步 Pipeline]
        P --> L2[L2 Milvus 向量索引]
        P --> L3[L3 MinIO 冷归档]
    end

    subgraph R[召回链路]
        direction LR
        K --> VC[向量候选]
        K --> KW[关键词候选]
        KW --> H[默认 RRF 融合与排序]
        VC --> H
        H --> B[可选线性融合或门禁 Cross-Encoder]
        B --> T[token budget]
        T --> CTX[上下文返回 Agent]
    end

    subgraph S[恢复链路]
        direction LR
        K --> CP[Runtime Snapshot 与 Checkpoint]
        CP --> WAL[WAL 去重回放]
        WAL --> ID[Execution ID 幂等]
        ID --> RES[恢复 Task DAG]
    end
```

同步边界止于 L1 可见；最终索引和归档进入有界后台 Pipeline，召回与恢复保持为
独立内核路径。这个边界是项目在延迟、一致性和故障恢复之间最核心的设计选择。

当前公共 Recall 契约默认使用 `HYBRID + RRF`，并保持额外 reranker 关闭。
`VECTOR_ONLY`、`KEYWORD_ONLY`、MMR、线性分数融合和受门禁控制的 Cross-Encoder
仍可通过请求参数显式选择。`v0.1.1` 的公开证据以 `VectorOnly` 为基线，不能直接
用来证明当前默认策略的质量或延迟。

## 快速开始

前置条件：

- Docker Desktop，或支持 Compose v2 的 Docker Engine
- 至少 6 GB 可用内存
- Windows 命令需要 Windows PowerShell 5.1 或更高版本
- Linux/macOS 命令需要 `bash`、`curl` 和 `python3`
- 仅在可信本机环境运行 Quickstart；默认需要宿主机回环端口 `8080`

Windows 可先检查端口占用和系统保留范围：

```powershell
Get-NetTCPConnection -LocalPort 8080 -ErrorAction SilentlyContinue
netsh interface ipv4 show excludedportrange protocol=tcp
```

如果 `8080` 冲突，先设置 `VORTEX_HTTP_PORT` 为其他可用端口；这只改变宿主机映射，
不会改变容器内 Vortex 的 `8080` 或任何存储服务端口。Redis、Milvus 和 MinIO 无需
宿主机端口，因此宿主机 `6379`、`19530`、`9000`、`9001` 和 `9091` 被占用时仍可运行。
并行运行第二套 Quickstart 时，同时设置唯一的 `COMPOSE_PROJECT_NAME`。

下面一条命令会构建并启动完整环境，等待健康检查，完成记忆写入与召回，然后在
Checkpoint 后强杀 Worker 并恢复任务：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\examples\quickstart-agent\run.ps1 -StartQuickstart
```

Linux/macOS：

```bash
START_QUICKSTART=true bash examples/quickstart-agent/run.sh
```

成功输出会先出现 `WITH VORTEX: recalled durable memory`，再出现
`WITH VORTEX: recovered task ...`，最后打印
`No external LLM API key was used.`。停止环境：

```bash
docker compose -f docker-compose.quickstart.yml down
```

录制输出和完整 HTTP 操作见
[examples/quickstart-agent](examples/quickstart-agent) 与
[docs/quickstart.md](docs/quickstart.md)。

## 基准测试证据

下面的核心数据来自 deterministic benchmark，并链接了证据文件和复现命令。完整范围、边界和复现说明见 [docs/benchmark.md](docs/benchmark.md)。这些数据不是生产环境保证。

| 方向 | 结果 | 证据 |
| --- | --- | --- |
| LongMemEval recall | 官方 LongMemEval oracle 的 120-case case-isolated 评测完成五种模式 `600` 次配对运行，错误数 `0`。`VectorOnly` fragment Recall@5 为 `0.8094`，相对 `KeywordOnly` 提升 `+0.1856`，paired 95% CI `[+0.1086, +0.2632]`。 | [LongMemEval 评测报告](ops/runbooks/vortex-recall-longmemeval-evaluation-report-20260729.md) |
| Cross-Encoder 门禁 | 锁定的 ONNX Cross-Encoder DEV 候选在 `120/120` case 改变排序，但未通过五项冻结的质量与延迟规则。`VectorOnly` 是该证据版本的模型晋级基线；validation 和 reserve 均未运行。 | [Cross-Encoder DEV 决策](ops/runbooks/vortex-cross-encoder-dev-decision-20260729.md) |
| Main-path latency | 返回前同步完成预抽取记忆切分、本地 Embedding 与 L1 write-through，最终处理进入有界后台 Pipeline；100 case/mode 下 P99 从 `818.82 ms` 降至 `268.65 ms`（`-67.19%`），返回时 L1 可见率和最终 L2/L3 readiness 均为 `100%`。 | [Write-through 延迟证据](ops/runbooks/vortex-main-path-latency-write-through-evidence-20260728.md) |
| Runtime recovery | deterministic fault-injection matrix 在 service restart、tool failure、LLM exception、state integrity 和 concurrency 五类场景中通过 `32/32` covered cases。 | [Runtime recovery evidence](ops/runbooks/vortex-runtime-recovery-benchmark-evidence-20260627.md) |

Recall 是 oracle fragment 检索指标，不是答案准确率。延迟来自排除外部 LLM generation
的本地确定性 benchmark，不是 production P99 或完整 Agent latency。Cross-Encoder
被拒绝的结果也不能表述为模型收益；具体边界以链接的 evidence 文件为准。

## 三个核心技术决策及取舍

### 1. L1 write-through 后返回，最终持久化异步化

**问题。** 如果每次写入都同步完成记忆抽取、摘要、向量索引和冷归档，请求主链路
会为调用方返回前并不需要的工作付出延迟。

**决策。** Vortex 在同步边界内完成预抽取记忆的切分、本地 Embedding 和 L1
admission；最终抽取、L2 索引和 L3 归档进入带重试与 backpressure 的有界
Pipeline。调用方获得 read-your-own-write，同时不等待所有持久层完成。

**取舍。** 系统接受 L2/L3 最终一致，并必须暴露 Pipeline 状态与失败处理。换来的
结果是：100 case/mode 的确定性基准中，主链路 P99 从 `818.82 ms` 降至
`268.65 ms`，返回时 L1 可见率和最终 L2/L3 readiness 均为 `100%`。证据见
[write-through 延迟报告](ops/runbooks/vortex-main-path-latency-write-through-evidence-20260728.md)。

**实现入口。** [AsyncMemoryPipeline](vortex-kernel/src/main/java/com/vortex/kernel/hmc/AsyncMemoryPipeline.java)
负责有界异步交接；[HierarchicalMemoryController](vortex-kernel/src/main/java/com/vortex/kernel/hmc/HierarchicalMemoryController.java)
负责切分、本地 Embedding 和 L1 admission。

### 2. 选择可审计的召回基线，不上线未被证据支持的重排模型

**问题。** 共享评测命名空间会造成跨用例数据泄漏，而重排模型仅仅“改变排序”并不
等于提升召回质量。

**决策。** `v0.1.1` 废弃污染结果，按 case 隔离重跑 LongMemEval，并用五项冻结的
质量与延迟规则控制模型晋级。锁定的 ONNX Cross-Encoder 虽改变 `120/120` 个
排序，但未通过门禁，因此没有晋级为默认 reranker。当前代码进一步引入
`HYBRID + RRF` 默认候选融合，但仍关闭额外 reranker；该默认值需要使用与当前
代码一致的新证据单独验证。

**取舍。** 项目暂时放弃推测性的 Cross-Encoder 收益，并保留 `VectorOnly` 作为
可回退、可对照的基线。当前 Hybrid/RRF 默认值扩大了关键词与向量候选覆盖面，
也增加了排序复杂度和重新验证责任。`v0.1.1` 隔离后的
120-case 评测中 fragment Recall@5 为 `0.8094`，相对 `KeywordOnly`
提升 `+0.1856`，paired 95% CI 为 `[+0.1086, +0.2632]`。证据见
[LongMemEval 报告](ops/runbooks/vortex-recall-longmemeval-evaluation-report-20260729.md)
和 [Cross-Encoder 决策](ops/runbooks/vortex-cross-encoder-dev-decision-20260729.md)。

**实现入口。** [RecallQuery](vortex-common/src/main/java/com/vortex/common/dto/RecallQuery.java)
定义证据支持的默认值；[RecallOrchestrator](vortex-kernel/src/main/java/com/vortex/kernel/hmc/RecallOrchestrator.java)
实现显式选择的 retrieval 与 reranker 分支。

### 3. Snapshot + WAL + Execution ID，不声称分布式 exactly-once

**问题。** 仅有 Checkpoint 无法区分重启前已经完成和仍在执行的 Tool/LLM 调用，
直接 replay 可能重复产生副作用。

**决策。** Runtime Snapshot 持久化 Task DAG、Conversation、Memory 引用和
Tool/LLM 状态；恢复时加载 Checkpoint、去重回放 WAL、重建状态，再以
Execution ID 请求哈希、原子占位与响应重放保证幂等。

**取舍。** 方案增加序列化成本、WAL 写放大和状态迁移约束，提供的是确定性的单运行时
恢复，而不是分布式一致性或跨区域 exactly-once。默认 Execution ID backend 为
进程内存；Quickstart 显式切换到 Redis。执行中的 Execution ID 占位不会按业务 TTL
自动过期，进入 `COMPLETED` 或 `UNKNOWN` 后才开始保留期，避免长动作跨 TTL 后重放
副作用；异常遗留的 `IN_PROGRESS` 需要人工核对。Quickstart 将 WAL、DLQ、处理记录和
应用状态统一挂载到 `/var/lib/vortex` 持久卷。故障注入矩阵在
五类场景中通过 `32/32` covered cases。证据见
[runtime recovery 报告](ops/runbooks/vortex-runtime-recovery-benchmark-evidence-20260627.md)。

**实现入口。** [SnapshotService](vortex-kernel/src/main/java/com/vortex/kernel/snapshot/SnapshotService.java)
持久化 Checkpoint，[RecoveryEngine](vortex-kernel/src/main/java/com/vortex/kernel/snapshot/RecoveryEngine.java)
负责状态回放，[ExecutionIdService](vortex-app/src/main/java/com/vortex/app/runtime/ExecutionIdService.java)
保护对外可见执行的幂等性。

## 已实现范围

| 方向 | 已实现能力 |
| --- | --- |
| Memory | store、recall、feedback、pin/unpin、eviction、异步 ingest 状态、namespace/tag 过滤与 token budget |
| Retrieval | keyword、vector、hybrid candidate merge、可选重排门禁与 context assembly |
| Runtime state | Task DAG 修改、Checkpoint、WAL replay、branch/switch/merge 与 Execution ID 幂等 |
| Storage | L1 Caffeine、L2 Milvus、L3 MinIO，以及可选 Redis Execution ID backend |
| Model integration | Vortex generation/embedding 契约、Spring AI 示例和 LangChain4j adapter |
| Operations | Health catalog、SLO snapshot、Prometheus metrics、确定性 benchmark 与 governance check |

Quickstart 后可通过 `http://localhost:8080/swagger-ui.html` 查看完整 REST
接口，并在 Swagger 的 `Authorize` 对话框中输入已配置的 Bearer token。详细 endpoint 和配置继续由 [docs/quickstart.md](docs/quickstart.md) 与
[docs/architecture.md](docs/architecture.md) 承接。CI 与 benchmark 复现命令见
[docs/benchmark.md](docs/benchmark.md)。

## 项目边界

Vortex 与纯向量 RAG、手写 memory layer 的定位差异见
[docs/comparison.md](docs/comparison.md)。面向项目审阅的稳定版本为
[`v0.1.1`](docs/releases/v0.1.1.md)，早期 release note 继续保留归档。
第三方采用风险、修复步骤和发布验收标准见
[外部使用与发布准备手册](ops/runbooks/vortex-external-adoption-readiness-manual.md)。

Vortex 暂不声称已经具备：

- 生产级 OIDC/mTLS、细粒度 RBAC、独立租户身份与分布式限流/审计汇聚。Quickstart
  当前提供的是单个共享 Bearer token、namespace allowlist、进程内限流和结构化审计事件。
- 长时间高并发生产容量结果。
- 分布式一致性、多节点调度或跨区域复制。
- 完整外部 process-manager crash-loop 编排。
- 在 latency benchmark 内集成真实 LLM generation 的完整 Agent runtime。

因此当前适用范围是：

| 场景 | 状态 |
| --- | --- |
| 源码阅读、作品集审阅、本机 Demo | 支持 |
| 可信隔离网络内通过 REST 试验 | 有条件支持；使用 Quickstart 的 token、namespace 与回环端口边界 |
| 作为 Maven/Gradle 库直接依赖 | 尚未发布 artifacts |
| 公网、多租户或生产部署 | 不支持 |

## 许可证

Vortex 代码与文档使用 [Apache License 2.0](LICENSE)。模型与依赖归属记录见
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。部署、备份恢复与迁移步骤见
[部署运维手册](ops/runbooks/vortex-deployment-operations.md)，候选版本验收见
[发布清单](ops/runbooks/vortex-release-checklist.md)。

第三方模型文件、数据集和外部服务名称仍受各自上游许可与服务条款约束；仓库根目录的
Apache-2.0 许可证不自动覆盖这些第三方资产。重新分发或商业使用前，请核验对应模型和
数据集的来源、许可证与 attribution 要求。
