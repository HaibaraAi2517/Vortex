# Vortex 面试演示与答辩手册

这份手册用于一次 5 分钟项目演示和后续技术追问。演示只使用本地模型与
Docker 环境，不依赖外部 LLM API Key。

## 会前预热

首次构建和拉取镜像不计入现场演示时间。面试前从仓库根目录运行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\examples\quickstart-agent\run.ps1 -StartQuickstart
```

确认脚本完成后保留 Quickstart stack。现场演示直接运行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\demo\run-live-demo.ps1
```

正式使用前连续验证两轮：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\demo\run-live-demo.ps1 -Runs 2
```

2026-08-02 在预热后的 Windows Quickstart 环境完成重复性验证：PowerShell
连续 `2/2` 通过，单轮耗时分别为 `1.96s` 和 `1.38s`；Git Bash 入口也完成
同一链路验证。耗时只代表本机预热环境，脚本本身以每轮 `300s` 为硬上限。

结束后清理环境：

```powershell
docker compose -f docker-compose.quickstart.yml down
```

## 5 分钟讲解顺序

| 时间 | 屏幕内容 | 要说明的工程问题 |
| --- | --- | --- |
| 0:00-0:40 | README 顶部架构图 | Vortex 是 Agent Memory 与任务恢复内核，不是托管 SaaS；写入、召回、恢复是三条独立路径。 |
| 0:40-2:10 | `NO MEMORY` 与 `WITH VORTEX` 输出 | 相同追问在无历史上下文时无法回答；默认 `VectorOnly` 路径从持久记忆中召回事实，且默认关闭重排。 |
| 2:10-3:40 | Worker 被终止、`nodeCount=1` 恢复 | 进程内状态丢失后，新 Worker 从 Checkpoint 和 WAL 恢复并继续执行，而不是从第一步重跑。 |
| 3:40-4:30 | 三个核心技术决策 | 同步 L1、异步 L2/L3；证据支持的 VectorOnly 默认值；Snapshot + WAL + Execution ID 的恢复边界。 |
| 4:30-5:00 | 发布验证与项目边界 | `548` 个测试、Docker integration `13/13`、行覆盖率 `74.26%`；不声称生产级多租户或分布式 exactly-once。 |

现场必须出现的关键输出：

```text
NO MEMORY: I only see the current question ...
Recall diagnostics: retrievalMode=VECTOR_ONLY; rerankEnabled=False.
WITH VORTEX: recalled durable memory ...
WITH VORTEX: recovered task ... nodeCount=1 ...
No external LLM API key was used.
LIVE DEMO PASS: 1/1 runs completed within 300 seconds each.
```

## 五个高概率追问

### 1. 为什么默认 VectorOnly，而不是 Hybrid 或 Cross-Encoder？

case-isolated LongMemEval 的 120-case 评测中，VectorOnly fragment Recall@5
为 `0.8094`，相对 KeywordOnly 提升 `+0.1856`，paired 95% CI 为
`[+0.1086, +0.2632]`。当前 Keyword 路径仍是 namespace 扫描加本地 IDF，
不是可扩展倒排索引。Cross-Encoder 虽改变 `120/120` 个排序，但未通过冻结的
质量与延迟门禁。因此默认选择证据最充分、延迟更低的 VectorOnly，其他路径保留
为显式选项。

### 2. 为什么同步写 L1，异步写 L2/L3？

返回前完成预抽取记忆切分、本地 Embedding 和 L1 admission，提供
read-your-own-write；最终抽取、L2 索引和 L3 归档进入带重试与 backpressure
的有界 Pipeline。代价是 L2/L3 最终一致，收益是 100 case/mode 基准中 P99
从 `818.82 ms` 降至 `268.65 ms`，同时保持返回时 L1 可见率和最终 L2/L3
readiness 均为 `100%`。

### 3. 为什么 Snapshot + WAL + Execution ID 仍不是分布式 exactly-once？

Snapshot 保存恢复点，WAL 记录可重放的状态变化，Execution ID 对单次对外执行做
请求哈希、原子占位和响应重放。这能提供应用层恢复与幂等，但没有分布式共识、
跨服务事务或跨区域复制，所以不能把它描述为端到端 exactly-once。

### 4. `74.26%` 覆盖率还留下什么风险？

覆盖率证明主要代码路径被执行过，不代表生产边界已经成立。剩余风险主要在外部
依赖的长时间故障、真实高并发、进程管理器 crash-loop、鉴权与租户隔离，以及
真实 LLM generation 没有进入主链路延迟基准。回答时应说明风险和验证计划，
不要把覆盖率等同于正确性。

### 5. 如果进入生产，最先补什么？

先补认证、授权和 tenant namespace 强隔离，因为记忆系统首先要保证不同主体之间
不会越权读写；随后增加 audit log、rate limit 与配额，再做长时间高并发容量测试
和多节点调度。这个顺序先解决数据边界，再解决资源边界和规模问题。

## 可核验入口

- [README 架构与三个决策](../README_zh.md)
- [Benchmark 范围与证据](benchmark.md)
- [v0.1.1 发布验证](releases/v0.1.1.md)
- [Quickstart 细节](quickstart.md)
- [Recall 架构决策](../ops/runbooks/vortex-recall-architecture-decision-20260728.md)

面试时只引用上述文档已经给出范围和复现路径的数字。对于未实现能力，直接说明
边界以及下一步验证方法，不用推测性表述补齐故事。
