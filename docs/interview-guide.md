# Vortex 面试演示与答辩手册

这份手册用于一次 5 分钟项目演示和后续技术追问。演示只使用本地模型与
Docker 环境，不依赖外部 LLM API Key。

## 会前预热

首次拉取镜像不计入现场演示时间。面试前从仓库根目录创建持久凭据文件，替换其中
所有占位符，并把变量加载到当前 PowerShell 进程：

```powershell
Copy-Item .env.example .env.local
# Replace every placeholder in .env.local first.
Get-Content .env.local | ForEach-Object {
  if ($_ -match '^\s*([^#][^=]*)=(.*)$') {
    [Environment]::SetEnvironmentVariable($matches[1].Trim(), $matches[2], "Process")
  }
}
docker compose --env-file .env.local -f docker-compose.quickstart.yml pull
docker compose --env-file .env.local -f docker-compose.quickstart.yml up --no-build -d --wait
powershell -NoProfile -ExecutionPolicy Bypass -File .\examples\quickstart-agent\run.ps1
```

这里使用 `.env.local` 是因为 `-StartQuickstart` 生成的是子进程内凭据，后续独立的
现场演示进程无法自动取回。确认脚本完成后保留 Quickstart stack。现场演示直接运行：

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
docker compose --env-file .env.local -f docker-compose.quickstart.yml down
```

## 5 分钟讲解顺序

| 时间 | 屏幕内容 | 要说明的工程问题 |
| --- | --- | --- |
| 0:00-0:40 | README 顶部架构图 | Vortex 是 Agent Memory 与任务恢复内核，不是托管 SaaS；写入、召回、恢复是三条独立路径。 |
| 0:40-2:10 | `NO MEMORY` 与 `WITH VORTEX` 输出 | 相同追问在无历史上下文时无法回答；演示显式选择 `VectorOnly` 以保持历史对照稳定，当前公共默认值是 `HYBRID + RRF`。 |
| 2:10-3:40 | Worker 被终止、`nodeCount=1` 恢复 | 进程内状态丢失后，新 Worker 从 Checkpoint 和 WAL 恢复并继续执行，而不是从第一步重跑。 |
| 3:40-4:30 | 三个核心技术决策 | 同步 L1、异步 L2/L3；受门禁保护的 `HYBRID + RRF` 默认值与 VectorOnly 回退；Snapshot + WAL + Execution ID 的恢复边界。 |
| 4:30-5:00 | 发布验证与项目边界 | `v0.2.0` clean Maven verify、Docker integration `13/13`、跨平台 Quickstart 与备份恢复演练；不声称生产级多租户或分布式 exactly-once。 |

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

### 1. 为什么公共默认是 Hybrid + RRF，演示却使用 VectorOnly？

早期 case-isolated LongMemEval 120-case 证据中，VectorOnly fragment Recall@5
为 `0.8094`，相对 KeywordOnly 提升 `+0.1856`，paired 95% CI 为
`[+0.1086, +0.2632]`，所以它仍是历史对照和回退路径。随后冻结的
`HYBRID_RRF` 候选通过 read-only DEV 与 sealed validation 门禁，当前公共 Recall
契约因此默认使用受保护的 `HYBRID + RRF`，并继续关闭额外 Cross-Encoder。
演示显式发送 `VECTOR_ONLY`，只是为了复现稳定、易解释的旧基线，不代表产品默认值。

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

当前可信环境入口已经具备共享 Bearer token、namespace allowlist、请求大小限制、
进程内 rate limit 与审计事件。进入生产前首先要把共享凭据升级为 OIDC/mTLS 的
独立身份，并增加细粒度 RBAC 与强租户隔离；随后补分布式限流和审计汇聚，再做
长时间高并发容量测试与多节点调度。

## 可核验入口

- [README 架构与三个决策](../README.md)
- [Benchmark 范围与证据](benchmark.md)
- [v0.2.0 发布验证](releases/v0.2.0.md)
- [Quickstart 细节](quickstart.md)
- [Recall 架构决策](../ops/runbooks/vortex-recall-architecture-decision-20260728.md)
- [Recall Ranking v2 晋级证据](../ops/runbooks/vortex-recall-ranking-v2-evaluation-20260802.md)

面试时只引用上述文档已经给出范围和复现路径的数字。对于未实现能力，直接说明
边界以及下一步验证方法，不用推测性表述补齐故事。
