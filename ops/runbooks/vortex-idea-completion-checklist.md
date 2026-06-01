# Vortex 构思完成度清单

基于项目构思文档与当前仓库代码的逐项盘点。

说明：

- 本清单只统计“可工程落地”的内容，不统计纯背景叙述、面试话术、愿景描述。
- 状态定义：
  - `已实现`：仓库中已有可运行实现，且有接口、主流程或测试支撑
  - `已有雏形`：已有部分实现或替代实现，但和构思中的目标仍有明显差距
  - `未实现`：当前仓库里看不到对应实现
- 本次盘点以当前代码为准，不代表最初构思是否合理。

## 已实现

| 条目 | 现状 | 代码依据 |
| --- | --- | --- |
| HMC 三级记忆主链路 | 已有 `HierarchicalMemoryController` 统一编排 L1/L2/L3 的 store、recall、pin、delete、health | `vortex-kernel/src/main/java/com/vortex/kernel/hmc/HierarchicalMemoryController.java` |
| L1 热存储 | 已使用 Caffeine 作为 L1，按 token 计容量，不按对象数计 | `vortex-storage/src/main/java/com/vortex/storage/l1/CaffeineHotStore.java` |
| L2 语义检索 | 已接入 Milvus 作为向量检索层 | `vortex-storage/src/main/java/com/vortex/storage/l2/MilvusWarmStore.java` |
| L3 冷存档 | 已接入 MinIO 作为冷存和 checkpoint 存储 | `vortex-storage/src/main/java/com/vortex/storage/l3/MinioColdStore.java` |
| 本地 embedding | 已用 DJL + ONNX 跑 BGE-Small，本地分词和向量化可用 | `vortex-kernel/src/main/java/com/vortex/kernel/embedding/BgeSmallEmbeddingService.java` |
| 可选云 embedding | 已有 DeepSeek embedding 服务，可作为 L2 向量路径 | `vortex-kernel/src/main/java/com/vortex/kernel/embedding/DeepSeekEmbeddingService.java` |
| 语义切分 / token 感知缓冲 | 已有 `SemanticTextSplitter`，按段落、句子和 token 上限切分，避免粗暴字符截断 | `vortex-kernel/src/main/java/com/vortex/kernel/hmc/SemanticTextSplitter.java` |
| 语义淘汰评分 | 已有 `alpha + beta + gamma` 评分体系，并将 importance 纳入决策 | `vortex-common/src/main/java/com/vortex/common/model/MemoryFragment.java`、`vortex-kernel/src/main/java/com/vortex/kernel/hmc/SemanticEvictionPolicy.java` |
| 基于反馈的权重学习 | 已有 `AdaptiveWeightLearner`，可根据 recall feedback 更新 active/shadow profile | `vortex-kernel/src/main/java/com/vortex/kernel/hmc/AdaptiveWeightLearner.java` |
| 召回 API 闭环 | 已具备 `/store`、`/recall`、`/feedback`、`/learning` 接口 | `vortex-app/src/main/java/com/vortex/app/controller/MemoryController.java` |
| DAG 任务模型 | 已实现任务、节点、边、分支、上下文、DAG 导出 | `vortex-app/src/main/java/com/vortex/app/controller/TaskController.java`、`vortex-common/src/main/java/com/vortex/common/model/*.java` |
| WAL + Checkpoint 恢复链 | 已有 WAL、FULL/DELTA checkpoint、恢复、自动调度 | `vortex-kernel/src/main/java/com/vortex/kernel/snapshot/ActionLogWriter.java`、`IncrementalCheckpointManager.java`、`RecoveryEngine.java`、`CheckpointScheduler.java` |
| Checkpoint 持久化格式 | 已用 Kryo + gzip，并保留对旧 Jackson 格式的兼容迁移 | `vortex-storage/src/main/java/com/vortex/storage/l3/MinioColdStore.java`、`vortex-common/src/main/java/com/vortex/common/serialization/KryoSerializer.java` |
| 语义分页 | 已有 `SemanticPageTable`、`SemanticPagingManager`、`PageFaultHandler` | `vortex-kernel/src/main/java/com/vortex/kernel/paging/*.java` |
| 预取策略 | 已实现 DAG 拓扑、语义邻域、分支投机三种预取 | `vortex-kernel/src/main/java/com/vortex/kernel/paging/PrefetchEngine.java` |
| 健康度与 SLO 观测 | 已实现 `/api/v1/memory/health`、`/slo`、`/slo/report`、Prometheus 指标、alert/runbook | `vortex-app/src/main/java/com/vortex/app/health/*.java`、`ops/prometheus/vortex-memory-slo-alerts.yml` |
| 集成测试主链路 | 已覆盖 store -> evict -> recall -> feedback -> checkpoint -> recover 的主场景 | `vortex-app/src/test/java/com/vortex/app/integration/FullLifecycleIT.java` |

## 已有雏形

| 条目 | 当前状态 | 差距 |
| --- | --- | --- |
| Semantic LRU / 语义分页 | 已有语义评分淘汰和语义分页，但不是严格按 `Score = α * Recency + β * CosineSimilarity` 的单一经典版本 | 已经扩展到 importance、redundancy、prefetch、paging 多维机制，和构思中的“简洁 Semantic LRU 原型”不完全同构 |
| reasoning chain 级上下文保护 | 已有 `reasoningChainId`、推理链分组淘汰思路 | 还不是完整的“保持完整句群/思维块不可拆散”的强约束系统 |
| L3 全量归档 | 已有 MinIO 冷存 | 构思里提过 RocksDB/HBase/S3/HDFS，多种持久化形态目前只落了 MinIO 路径 |
| 异步持久化主流程 | 已有 `FragmentPersistenceManager`、DLQ、processed key store | 还没有构思中提到的 Disruptor 环形队列那种专门高吞吐写扩散架构 |
| 状态同步器的日志复制思想 | 已有本地 WAL、sequence number、recover replay | 这是单节点 durable log，不是多节点 Raft log replication |
| “可撤销/可回溯”的任务恢复 | 已支持从 checkpoint 或最新 durable state recover | 还没有“恢复到任意 DAG 节点并重新触发推理”的显式 rewind API |
| 多 branch 任务协作 | 已有 create/switch/merge branch | 还没有多 Agent 同步写同一任务时的冲突仲裁、锁管理、共识提交 |
| Java 21 虚拟线程使用 | 已在 page fault / prefetch 中使用虚拟线程 | 还没有“一个 Agent 一个虚拟线程”的完整调度引擎 |
| HuggingFace tokenizer 集成 | 已通过 DJL 的 `HuggingFaceTokenizer` 做 token 计数 | 还不是构思里说的独立 tokenizer 缓冲区子系统，也不是对多模型 tokenizer 的统一抽象 |
| 预取与性能优化 | 已有 speculative prefetch 和异步 recall 后预取 | 还未形成完整的“推理前预测下一步所需记忆并主动回填”的可证明收益方案 |
| durability / health 统一观测 | 已有较完整的 runbook、日志、Prometheus、REST 对齐 | 观测面较完善，但尚未和分布式一致性链路联动，因为那部分尚不存在 |

## 未实现

| 条目 | 说明 |
| --- | --- |
| Netty 接入层 / Vortex-P 自定义长连接协议 | 当前对外接口是 Spring Boot REST Controller，没有 Netty 接入层 |
| SSE 流式过滤器 | 没有看到 `ChannelInboundHandler`、SSE token 流拦截、边转发边存记忆的实现 |
| Raft 共识协议 | 当前仓库没有 Raft、JRaft、Copycat 或等价实现 |
| Leader 选举 | 没有 Redis 锁、ZooKeeper 临时节点或其他 leader election 机制 |
| 多节点日志复制 | WAL 是本地文件写前日志，不是多数派复制日志 |
| 多 Agent 分布式状态同步 | 没有 Agent 间状态广播总线，也没有共享状态协议层 |
| 分布式锁 / 冲突合并器 | 当前 branch/merge 是单进程任务模型，不是跨节点冲突解决 |
| RocksDB 冷存 | 当前 L3 不是 RocksDB，而是 MinIO |
| Redis 作为 L1 | 当前 L1 是本地 Caffeine，不是 Redis |
| HDFS / S3 多后端抽象 | 当前只有 MinIO 这一条对象存储实现 |
| Protobuf VortexPacket | 没有 Protobuf 协议对象，也没有自定义二进制消息层 |
| 堆外内存缓存 | 没有 `ByteBuffer.allocateDirect()` 或直接内存版 L1 管理器 |
| Disruptor 高性能环形队列 | 当前没有 LMAX Disruptor 依赖或实现 |
| “一个 Agent 一个线程”的调度器 | 没有独立的 Agent runtime、挂起/唤醒、反向路由框架 |
| 大规模并发 Agent Scheduler | 当前任务系统更像 DAG 状态管理，不是完整 Agent 调度内核 |
| 模型返回结果后的反向路由 | 没有构思中说的模型结果回流到具体挂起 Agent 的调度通路 |
| Raft/共识级 checkpoint 跨机恢复 | 恢复是单系统内 durable recovery，不是任意机器上的共识恢复 |
| 任意 DAG 节点级 rewind | 当前以 checkpoint 为主，不支持显式“回到某个节点重新执行” |
| 面向多 Agent 的实时消息总线 | 没有 Netty bus、broker 或 event mesh 设计 |
| 以 Redis/ZooKeeper 为基础的轻量协调器 | 仓库里没有这类协调层实现 |
| 统一插件式多存储后端 | 当前 L1/L2/L3 已抽象成接口，但构思中的多实现生态还没有成型 |

## 不纳入完成度统计的内容

这些内容在 `构思.txt` 里出现过，但它们是叙事、定位或面试表达，不属于“代码是否完成”的判定对象：

- “Agent 时代的操作系统内核”
- “未来 AI 社会的基础设施”
- “NexusAI vs Vortex”的项目定位描述
- 面试表达中的收益数字和话术
- 行业痛点、理论意义、价值判断

## 总结

当前仓库最接近 `构思.txt` 的部分，是：

1. HMC 三级记忆
2. DAG 快照与恢复
3. 语义分页与预取
4. 基于 feedback 的在线学习
5. 健康度 / durability / SLO 观测

当前与 `构思.txt` 差距最大的部分，是：

1. Netty 接入层
2. Raft / 多节点共识
3. 多 Agent 实时状态同步
4. RocksDB / Redis / 协调器这类基础设施路线
5. 完整的并发 Agent 调度内核

如果按“构思落地率”粗略判断：

- HMC / Snapshot / Observability 方向：已经进入可运行系统阶段
- Distributed State Synchronizer / Agent Kernel 方向：还基本停留在设想阶段
