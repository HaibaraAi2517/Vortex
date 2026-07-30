# Vortex Recall Architecture Decision - 2026-07-28

状态：Accepted

完整评测报告：`ops/runbooks/vortex-recall-longmemeval-evaluation-report-20260729.md`

本决策使用 case-isolated LongMemEval oracle 120-case 正式结果，替代同日早先
category-shared namespace 的无效标准集结论。正式运行从 2026-07-28 23:51
Asia/Shanghai 开始，于 2026-07-29 00:04 结束。

## 决策

1. 保留 Vector 路，并将 VectorOnly 作为当前总体质量与延迟的首选参考策略。
   它的 Recall@5 为 `0.8094`、case hit 为 `0.9333`，平均延迟约 `163 ms`。
2. VectorOnly 默认不启用 Rerank。专项审计确认 Rerank 已执行；但当前 Vector-only
   输入关闭 keyword 且所有 fragment importance 恒为 0.5，评分必然退化为 semantic
   score 的单调变换。默认关闭是避免条件性冗余计算，不代表 Rerank 普遍无效。
3. 保留 Hybrid Rerank 作为场景化实验能力，而非统一默认策略。它相对无 Rerank 的
   Hybrid 有显著收益，但相对 Vector+Rerank 的 Recall 差值为 `-0.0231`，
   95% CI `[-0.0818, +0.0353]`，不能证明总体优于 Vector 路。
4. 暂不依据本次留出集直接实现路由规则。分类结果可生成假设，但每类只有 20 case；
   场景路由必须在新的开发集上确定，并由另一个未见验证集确认。
5. KeywordOnly 不作为默认路径。当前实现是 `listByNamespace + 本地 IDF`，
   平均延迟约 `2526 ms`，仍不是真正可扩展的倒排索引。

## 协议问题与纠正

早先的 120-case 数据集只生成了 6 个 namespace，每个类别的 20 个独立 LongMemEval
case 共用一个检索空间。`RecallBenchmarkRunner` 会按 namespace 一次性写入全部
fragment，因此一个问题会检索到其他 19 个 case 的记忆。

污染证据：

| Mode | Top5 returned | 来自其他 case | 污染率 |
| --- | ---: | ---: | ---: |
| KeywordOnly | 600 | 263 | 43.83% |
| VectorOnly | 600 | 325 | 54.17% |
| Vector+Rerank | 600 | 326 | 54.33% |
| Hybrid | 600 | 262 | 43.67% |
| Hybrid+Rerank | 600 | 260 | 43.33% |

这违反了 LongMemEval 每个样本使用自身 `haystack_sessions` 的 oracle 检索边界。
因此旧 Hybrid+Rerank Recall@5 `0.5835` 只能视为跨 case 干扰压力测试结果，
不能作为标准 LongMemEval 成绩，也不能进入简历。

纠正措施：

- 转换器新增 `-NamespacePerCase`，并与 `-NamespacePerCategory` 互斥
- 同一批 120 个 case 从 6 个 namespace 改为 120 个独立 namespace
- case ID、memory fragments、expected fragments 与旧运行完全相同
- 分析器新增 `-RequireCaseIsolatedReturns` 硬门禁
- 修正后五个模式的 Top5 跨 case 返回均为 `0`
- 旧污染报告已验证会被新门禁拒绝

## 数据与隔离

- 上游输入：官方 LongMemEval oracle 文件
  `E:/tmp/longmemeval/longmemeval_oracle.json`
- 上游 SHA-256：
  `821A2034D219AB45846873DD14C14F12CFE7776E73527A483F9DAC095D38620C`
- 正式数据集：
  `ops/datasets/generated/longmemeval-oracle-recall-holdout-120-case-isolated.json`
- 正式数据集 SHA-256：
  `3ABEA0466DA6D5530CE8107B90961DAB5B10C564476F610DB343E09F183B7AA1`
- 采样：六个官方类别各取 20 个 case，共 120 case
- 规模：2447 个 memory fragments，221 个 expected fragments
- 每 case 自有 fragment：平均 20.4，最少 2，最多 60
- 多 expected fragment case：65/120
- namespace：120/120 case 独立
- 排除集：
  `ops/datasets/generated/longmemeval-oracle-vortex-eval-20-tagfix.json`
- 与旧公开 20-case 的 case ID 交集：`0`

采样是按上游文件顺序进行的确定性分层切片，并非随机抽样。六类均匀覆盖适合发现类别
差异，但不代表 LongMemEval 原始类别比例或线上流量分布。

## Benchmark 协议

- 5 个模式：KeywordOnly、VectorOnly、Vector+Rerank、Hybrid、
  Hybrid+Rerank
- 严格配对：每个 case 在五种模式下各运行一次，共 600 次
- 错误：`0/600`
- case 隔离校验：五种模式 Top5 跨 case 返回均为 `0`
- 主指标：fragment-level Recall@5
- 辅助指标：case hit、all expected、MRR、NDCG、平均延迟
- TopK：`5`
- token budget：`4096`
- 关键词候选池：`256`，覆盖单 case 最大 60 个 fragment
- 运行环境：Docker Milvus/MinIO、本地 BGE-Small、无外部 LLM generation
- 统计：固定 seed `20260728`，20,000 次 case-level paired bootstrap

正式证据：

- `ops/eval-reports/20260728-recall-decision-longmemeval-case-isolated-120-001/recall-benchmark-20260728-160415.json`
- `ops/eval-reports/20260728-recall-decision-longmemeval-case-isolated-120-001/recall-benchmark-20260728-160415.md`
- `ops/eval-reports/20260728-recall-decision-longmemeval-case-isolated-120-001/recall-decision-analysis.json`
- `ops/eval-reports/20260728-recall-decision-longmemeval-case-isolated-120-001/recall-decision-analysis.md`

## 总体结果

| Mode | Recall@5 | Case hit | NDCG | Avg latency |
| --- | ---: | ---: | ---: | ---: |
| KeywordOnly | 0.6239 | 0.7333 | 0.5065 | 2526.0 ms |
| VectorOnly | 0.8094 | 0.9333 | 0.6493 | 163.1 ms |
| Vector+Rerank | 0.8094 | 0.9333 | 0.6493 | 159.7 ms |
| Hybrid | 0.6317 | 0.7667 | 0.5171 | 210.2 ms |
| Hybrid+Rerank | 0.7864 | 0.9167 | 0.6635 | 210.3 ms |

关键配对比较：

| Comparison | Recall delta | Recall 95% CI | Win/Tie/Loss | NDCG delta |
| --- | ---: | --- | ---: | ---: |
| Hybrid+Rerank vs Hybrid | +0.1547 | [+0.1060, +0.2065] | 34/86/0 | +0.1464 |
| Hybrid+Rerank vs KeywordOnly | +0.1625 | [+0.1106, +0.2179] | 35/84/1 | +0.1570 |
| Hybrid+Rerank vs Vector+Rerank | -0.0231 | [-0.0818, +0.0353] | 12/92/16 | +0.0142 |
| Hybrid vs KeywordOnly | +0.0078 | [-0.0113, +0.0307] | 4/114/2 | +0.0105 |
| Vector+Rerank vs VectorOnly | 0.0000 | [0.0000, 0.0000] | 0/120/0 | 0.0000 |
| VectorOnly vs KeywordOnly | +0.1856 | [+0.1086, +0.2632] | 40/73/7 | +0.1427 |

解释：

- VectorOnly 与 Vector+Rerank 的质量完全相同；当前特征契约使后者只能做单调
  变换，因此首选不执行冗余 Rerank 的 VectorOnly。
- Hybrid 在没有 Rerank 时几乎退化为 KeywordOnly；两者 Recall 差异的 CI 跨 0。
- Hybrid Rerank 能显著修复 Hybrid 的排序，但没有显著超过 VectorOnly。
- Rerank 只能处理已有 Vector 候选，不能补回候选池之外的漏召；本次恒定 importance
  输入也无法评估它在 Vector 候选池内的独立重排价值。
- KeywordOnly 即使候选池只有单 case 的 2～60 个 fragment，仍有明显存储扫描开销。

## Vector-only Rerank 管线审计

正式报告中，Vector+Rerank 的 `rerankEnabled` 在 120/120 case 上均为 true，进入
Rerank 的候选最少 2、最多 40、平均 19.8917；每例均与 vector candidate count
一致。VectorOnly 的 rerank candidate count 则全部为 0。调用链和结构化诊断排除了
开关未透传。

这里的 `256` 是 keyword namespace candidate pool limit，不适用于 Vector 路。
Vector 路按 `max(16, topK * 4)` 取候选；本次为了同时记录 Recall@10，query TopK
为 10，因此上限是 40。

`RecallBenchmarkRunner` 未设置 importance，模型默认值为 0.5。在 keyword 关闭时，
Rerank 分数为 `semanticWeight * normalizedSemantic + importanceWeight * 0.5`，是原
semantic 分数的单调变换。新增单测证明恒定 importance 时顺序保持不变，并证明
importance 变化时确实会改变排序，故不存在 pass-through bug。

因此 120/120 完全相同是按设计可推导的结果，但也暴露出该消融对 Vector-only
Rerank 的识别能力不足。未来只能在新的开发集上定义有依据的非恒定 importance 或
独立 reranker signal，并在新的未见验证集确认；不得复用当前留出集调参。

术语上，现有 `HybridRecallReranker` 保留类名以维持兼容，但 API 与评测报告将其明确
标记为 `rerankerType=LINEAR_SCORE_FUSION`。它不是 Cross-Encoder；未来真正的
query-document 模型必须使用不同 type 并单独评测。

## 分类结果

| Category | 当前 Recall@5 最佳模式 | 最佳值 | Hybrid+Rerank | VectorOnly |
| --- | --- | ---: | ---: | ---: |
| knowledge-update | Hybrid+Rerank | 0.8583 | 0.8583 | 0.8083 |
| multi-session | VectorOnly / Vector+Rerank | 0.5400 | 0.3600 | 0.5400 |
| single-session-assistant | Hybrid / Hybrid+Rerank | 0.9500 | 0.9500 | 0.8500 |
| single-session-preference | VectorOnly / Vector+Rerank | 0.8500 | 0.7167 | 0.8500 |
| single-session-user | VectorOnly / Vector+Rerank / Hybrid+Rerank | 0.9500 | 0.9500 | 0.9500 |
| temporal-reasoning | Hybrid+Rerank | 0.8833 | 0.8833 | 0.8583 |

分类结果说明场景路由可能有价值，但它仍是待验证假设：knowledge-update、
single-session-assistant、temporal-reasoning 倾向组合策略；multi-session 与
preference 倾向 VectorOnly。不得在本留出集上继续调路由权重。

## 关键词候选池

大 namespace 压力测试曾暴露固定小窗口会按存储顺序截断关键词候选。实现现已提供
`vortex.kernel.recall.keyword-candidate-pool-limit`，默认 `256`；回归测试
`RecallOrchestratorTest.keywordRecallScansPastLegacyCandidateWindow` 将目标放在第
101 个位置，验证默认窗口仍可召回。

这个配置只防止任意截断，不解决 O(N) namespace 扫描。正式 LongMemEval case-isolated
运行中最大候选只有 60，因此不需要旧污染运行使用的 1024 上限。

## 限制与可声称边界

- LongMemEval oracle 提供标注证据 fragment，不等于完整 Agent 回答质量。
- 本次没有外部 LLM generation，不能声称答案准确率或端到端 Agent 质量提升。
- 六类各 20 个是均匀分层切片，不是原始类别分布，也不是随机样本。
- 分类结果每类只有 20 case，只适合提出路由假设。
- 平均延迟来自本地 benchmark，不是生产 P95/P99。
- 不把自建 20-case 的 `0.79 -> 0.95` 与公开 120-case 的 `0.81` 拼接成
  同一数据集上的前后提升。
- 不再引用 category-shared 运行的 `0.58` 作为标准 LongMemEval 结果。

## 四个高危追问

### 1. 为什么旧自建 20-case 不足？

旧集只有 20 个自建 case，主要覆盖项目预设的专有名词和配置项；`0.9333` 与
`0.9500` 的差距小于一个完整 case 能提供的分辨率，也没有不确定性区间。因此它只能
说明关键词路在这些场景中贡献较大，不能证明 Hybrid 显著优于 KeywordOnly。新的公开集
覆盖六类、使用五模式严格配对，并对 case-level delta 做 20,000 次 bootstrap。

### 2. 既然做了 Hybrid，为什么最终保留 VectorOnly 主路？

因为评测目标是选择证据最强的策略，不是证明最复杂的设计。标准 case 隔离下
VectorOnly Recall@5 为 `0.8094`，平均约 `163 ms`；Hybrid+Rerank 为 `0.7864`，
约 `210 ms`，两者 Recall 差异的 CI 跨 0。VectorOnly 还在 multi-session 和
preference 上明显领先。因此当前应保留向量主路，把组合策略限制为有新验证集支撑的
场景化实验。

### 3. 为什么旧 LongMemEval 20-case hit rate 是 0.95，而新 Recall@5 是 0.81？

旧数字是 temporal-reasoning 20 case 的 case hit rate：TopK 中命中任意一个 expected
fragment 就算成功。新 `0.8094` 是六类 120 case 的 fragment-level Recall@5，会计算
多个 expected fragment 的覆盖比例；对应 VectorOnly case hit 其实是 `0.9333`，
与旧 `0.95` 接近。两者指标粒度和类别覆盖不同，不能直接做升降比较。

### 4. 为什么曾经得到 0.58？评测集选错了吗？

官方数据文件没有选错，错误发生在 namespace 包装协议。120 个独立 case 被按类别合并
成 6 个检索池，导致 Hybrid+Rerank Top5 中 43.33% 的 fragment 来自其他 case。
paired bootstrap 只能度量这个错误协议内部的差异，无法修复系统性污染。改为 120 个
case-isolated namespace 后，同一批样本、同一份标注的 Hybrid+Rerank 从 `0.5835`
变为 `0.7864`，VectorOnly 从 `0.4853` 变为 `0.8094`，且跨 case 返回归零。

## 简历安全表述

> 官方 LongMemEval 120-case 配对消融，VectorOnly Recall@5 0.81：完成五种检索模式
> 600 次运行，并以返回 ID 审计定位 category-shared namespace 导致的
> Hybrid+Rerank Top5 43% 跨 case 污染；修正为 case-isolated 协议后，
> VectorOnly 较 KeywordOnly +0.19
>（paired bootstrap 95% CI +0.11～+0.26），据此保留向量主路并将组合策略转为
> 场景化实验。

## 复现

生成 case-isolated 数据集：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ./ops/datasets/convert-longmemeval.ps1 `
  -InputPath 'E:/tmp/longmemeval/longmemeval_oracle.json' `
  -OutputPath 'ops/datasets/generated/longmemeval-oracle-recall-holdout-120-case-isolated.json' `
  -PerCategoryLimit 20 `
  -ExcludeCaseIdsFrom 'ops/datasets/generated/longmemeval-oracle-vortex-eval-20-tagfix.json' `
  -Namespace 'longmemeval-recall-holdout-v2' `
  -NamespacePerCase
```

构建并运行五模式 benchmark：

```powershell
mvn -q -pl vortex-app -am -DskipTests package
$env:VORTEX_EVAL_REPORT_OUTPUT_DIR='ops/eval-reports/20260728-recall-decision-longmemeval-case-isolated-120-001'
$env:VORTEX_EVAL_DATASET_LOCATION='file:E:/1projects/claude/Vortex/ops/datasets/generated/longmemeval-oracle-recall-holdout-120-case-isolated.json'
$env:VORTEX_EVAL_RECALL_TOP_K='5'
$env:VORTEX_EVAL_RECALL_TOKEN_BUDGET='4096'
$env:VORTEX_KERNEL_RECALL_KEYWORD_CANDIDATE_POOL_LIMIT='256'
$env:VORTEX_STORAGE_L2_MILVUS_COLLECTION='vortex_memory_recall_case_isolated_20260728_001'
$env:MINIO_KEY_PREFIX='recall-case-isolated/20260728-001/'
$env:VORTEX_WAL_DIR='E:/tmp/vortex-recall-case-isolated-20260728-001/wal'
$env:BGE_MODEL_PATH='models/bge-small-zh'
$env:VORTEX_SCHEDULER_ENABLED='false'
$env:VORTEX_PAGING_ENABLED='false'
java -jar ./vortex-app/target/vortex-app-0.1.0-SNAPSHOT-eval-cli.jar recall-benchmark
```

生成配对统计并强制 case 隔离：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ./ops/analyze-recall-decision.ps1 `
  -ReportPath 'ops/eval-reports/20260728-recall-decision-longmemeval-case-isolated-120-001/recall-benchmark-20260728-160415.json' `
  -DatasetPath 'ops/datasets/generated/longmemeval-oracle-recall-holdout-120-case-isolated.json' `
  -OutputDirectory 'ops/eval-reports/20260728-recall-decision-longmemeval-case-isolated-120-001' `
  -BootstrapIterations 20000 `
  -RandomSeed 20260728 `
  -KeywordCandidatePoolLimit 256 `
  -RequireCaseIsolatedReturns
```
