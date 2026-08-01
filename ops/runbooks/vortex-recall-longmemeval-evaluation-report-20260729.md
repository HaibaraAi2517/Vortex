# Vortex Recall LongMemEval Evaluation Report - 2026-07-29

## 报告元数据

- 状态：Final
- 正式运行 ID：`1c157f26`
- 运行时间：2026-07-28 23:51 至 2026-07-29 00:04，Asia/Shanghai
- 数据集：LongMemEval oracle，120-case case-isolated 分层留出集
- 主指标：case-level fragment Recall@5 宏平均
- 运行规模：120 cases，5 modes，600 paired runs
- 错误：`0/600`
- 统计：20,000 次 paired bootstrap，seed `20260728`
- 架构决策：
  `ops/runbooks/vortex-recall-architecture-decision-20260728.md`

## 一、执行摘要

第一轮 120-case 评测使用了错误的 category-shared namespace：每类 20 个独立
LongMemEval case 共用一个检索空间，导致 Hybrid+Rerank Top5 中 43.33% 的 fragment
来自其他 case。旧 Hybrid+Rerank Recall@5 `0.5835` 因此不能作为标准 LongMemEval
结果。

修正为每 case 独立 namespace 后，使用完全相同的 120 个 case、memory fragment 和
expected fragment 重新完成 600 次严格配对运行。正式结果为：

- VectorOnly Recall@5：`0.8094`
- VectorOnly case hit：`0.9333`
- Hybrid+Rerank Recall@5：`0.7864`
- Hybrid+Rerank NDCG：`0.6635`
- 五种模式 Top5 跨 case 返回：`0`

最终结论：保留 Vector 主路；VectorOnly 默认不启用在当前输入特征下数学冗余的
Rerank；Hybrid+Rerank 保留为待新数据验证的场景化能力；KeywordOnly 不作为默认
策略。专项审计已确认 Rerank 管线实际执行，不存在开关未接通或 pass-through bug。

## 二、问题背景

旧自建 20-case benchmark 中，Hybrid Recall@5 为 `0.9500`，KeywordOnly 为
`0.9333`，VectorOnly 为 `0.7917`。该数据集规模小、场景偏向项目预设的专有名词和
配置项，Hybrid 与 KeywordOnly 的差异不足以支持架构取舍。

为提高外部有效性，本次从官方 LongMemEval oracle 文件构建六类别 120-case 留出集，
对 KeywordOnly、VectorOnly、Vector+Rerank、Hybrid、Hybrid+Rerank 进行严格配对
消融，并使用 case-level bootstrap 估计差值的不确定性。

## 三、Namespace 协议事故

### 3.1 错误协议

第一版转换器按类别生成 namespace，共 6 个 namespace。`RecallBenchmarkRunner`
按 namespace 将所有 case 的 fragment 一次性写入，因此每个 query 会面对同类别其他
19 个 case 的无关记忆。

错误运行的 Top5 污染：

| Mode | Top5 returned | Foreign case fragments | Foreign rate |
| --- | ---: | ---: | ---: |
| KeywordOnly | 600 | 263 | 43.83% |
| VectorOnly | 600 | 325 | 54.17% |
| Vector+Rerank | 600 | 326 | 54.33% |
| Hybrid | 600 | 262 | 43.67% |
| Hybrid+Rerank | 600 | 260 | 43.33% |

LongMemEval 每个样本带有自己的 `haystack_sessions` 与 `answer_session_ids`。将不同
样本的 haystack 合并，不符合标准 oracle 检索边界。

### 3.2 纠正措施

- 转换器新增 `-NamespacePerCase`
- `-NamespacePerCase` 与 `-NamespacePerCategory` 强制互斥
- 同一批 120 case 从 6 个 namespace 改为 120 个 namespace
- case ID、memory fragments、expected fragments 保持完全一致
- 分析器新增 `-RequireCaseIsolatedReturns`
- 正式分析记录 `ForeignReturnedFragmentCount: 0`
- 旧污染报告已验证会被隔离门禁拒绝

### 3.3 协议修正影响

| Mode | Category-shared Recall@5 | Case-isolated Recall@5 | Difference |
| --- | ---: | ---: | ---: |
| KeywordOnly | 0.5314 | 0.6239 | +0.0925 |
| VectorOnly | 0.4853 | 0.8094 | +0.3241 |
| Vector+Rerank | 0.4853 | 0.8094 | +0.3241 |
| Hybrid | 0.5251 | 0.6317 | +0.1066 |
| Hybrid+Rerank | 0.5835 | 0.7864 | +0.2029 |

这些差值是评测协议变化造成的，不能表述为系统优化后的性能提升。

## 四、数据集

### 4.1 数据来源

- 上游文件：`E:/tmp/longmemeval/longmemeval_oracle.json`
- 上游 case：500
- 上游 SHA-256：
  `821A2034D219AB45846873DD14C14F12CFE7776E73527A483F9DAC095D38620C`
- 正式数据集：
  `ops/datasets/generated/longmemeval-oracle-recall-holdout-120-case-isolated.json`
- 正式数据集 SHA-256：
  `3ABEA0466DA6D5530CE8107B90961DAB5B10C564476F610DB343E09F183B7AA1`

### 4.2 数据规模

- 六个官方类别各 20 case，共 120 case
- Memory fragments：2447
- Expected fragments：221
- 多 expected fragment case：65/120
- 每 case fragment：平均 20.4，最少 2，最多 60
- Namespace：120/120 case 独立
- 与旧公开 20-case 的 case ID 交集：0

| Category | Cases | Fragments | Expected fragments |
| --- | ---: | ---: | ---: |
| knowledge-update | 20 | 480 | 41 |
| multi-session | 20 | 774 | 71 |
| single-session-assistant | 20 | 171 | 20 |
| single-session-preference | 20 | 298 | 27 |
| single-session-user | 20 | 234 | 21 |
| temporal-reasoning | 20 | 490 | 41 |

采样是按上游文件顺序进行的确定性分层切片，不是随机抽样。均匀类别覆盖适合比较类别
差异，但不代表 LongMemEval 原始类别比例或线上流量分布。

## 五、评测方法

### 5.1 检索模式

| Report mode | Retrieval mode | Rerank |
| --- | --- | --- |
| KeywordOnly | KEYWORD_ONLY | false |
| VectorOnly | VECTOR_ONLY | false |
| Vector+Rerank | VECTOR_ONLY | true |
| Hybrid | HYBRID | false |
| Hybrid+Rerank | HYBRID | true |

### 5.2 运行配置

- TopK：5
- 同时记录 K：1、3、5、10
- Token budget：4096
- Keyword candidate pool limit：256
- 单 case 最大 fragment：60
- Embedding：本地 BGE-Small，512 维
- L2：Docker-backed Milvus
- L3：Docker-backed MinIO
- Semantic paging：关闭
- 外部 LLM generation：关闭
- Scheduler：关闭

### 5.3 指标定义

- Recall@5：单 case Top5 返回的 expected fragment 数除以该 case expected fragment
  总数，再对 120 case 做宏平均
- Case hit：Top5 至少包含一个 expected fragment 的 case 比例
- All expected：Top5 包含该 case 全部 expected fragment 的比例
- MRR：第一个 expected fragment 的 reciprocal rank
- NDCG：考虑 expected fragment 排名位置的 normalized discounted cumulative gain
- Latency：本地单次 recall 调用耗时，不包含外部 LLM generation

### 5.4 统计方法

所有模式共享相同 case，比较采用 case-level paired delta。对每组 delta 使用固定 seed
进行 20,000 次 bootstrap，报告均值差和 percentile 95% CI。CI 排除 0 才视为当前
样本上可辨识的差异。

## 六、总体结果

| Mode | Recall@5 | Case hit | All expected | MRR | NDCG | Avg latency |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| KeywordOnly | 0.6239 | 0.7333 | 0.5333 | 0.5138 | 0.5065 | 2526.0 ms |
| VectorOnly | 0.8094 | 0.9333 | 0.6833 | 0.6554 | 0.6493 | 163.1 ms |
| Vector+Rerank | 0.8094 | 0.9333 | 0.6833 | 0.6554 | 0.6493 | 159.7 ms |
| Hybrid | 0.6317 | 0.7667 | 0.5333 | 0.5349 | 0.5171 | 210.2 ms |
| Hybrid+Rerank | 0.7864 | 0.9167 | 0.6833 | 0.6753 | 0.6635 | 210.3 ms |

## 七、配对比较

| Comparison | Recall delta | Recall 95% CI | W/T/L | Hit delta | NDCG delta |
| --- | ---: | --- | ---: | ---: | ---: |
| Hybrid+Rerank vs Hybrid | +0.1547 | [+0.1060,+0.2065] | 34/86/0 | +0.1500 | +0.1464 |
| Hybrid+Rerank vs KeywordOnly | +0.1625 | [+0.1106,+0.2179] | 35/84/1 | +0.1833 | +0.1570 |
| Hybrid+Rerank vs Vector+Rerank | -0.0231 | [-0.0818,+0.0353] | 12/92/16 | -0.0167 | +0.0142 |
| Hybrid vs KeywordOnly | +0.0078 | [-0.0113,+0.0307] | 4/114/2 | +0.0333 | +0.0105 |
| Vector+Rerank vs VectorOnly | 0.0000 | [0.0000,0.0000] | 0/120/0 | 0.0000 | 0.0000 |
| VectorOnly vs KeywordOnly | +0.1856 | [+0.1086,+0.2632] | 40/73/7 | +0.2000 | +0.1427 |

结论：

- VectorOnly 显著优于 KeywordOnly
- Hybrid 在没有 Rerank 时与 KeywordOnly 基本等价
- Hybrid Rerank 显著修复 Hybrid
- Hybrid+Rerank 没有显著超过 VectorOnly
- Rerank 对本次 VectorOnly 输入的所有质量指标均无变化，但原因是评分退化为单调
  变换，不代表 Rerank 管线未执行或对其他输入普遍无效

### 7.1 Vector-only Rerank 管线专项审计

针对 VectorOnly 与 Vector+Rerank 在 120 case 上逐位相同的异常信号，已完成调用链、
正式报告、评分公式和受控测试四层审计。结论是：**不存在 plumbing bug；Rerank 已经
执行，但本 benchmark 的 Vector-only 输入使其按数学定义退化为顺序不变的变换。**

正式 JSON 的结构化诊断证据：

| Audit signal | VectorOnly | Vector+Rerank |
| --- | ---: | ---: |
| Cases | 120 | 120 |
| `rerankEnabled` | false | true |
| Rerank candidates | 全部 0 | 最少 2，最多 40，平均 19.8917 |
| Rerank/vector candidate count 不一致 | - | 0/120 |
| 两模式返回 ID 顺序不一致 | - | 0/120 |

调用链为 `RecallAblationMode.VECTOR_RERANK` → `RecallQuery.rerankEnabled(true)` →
`RecallOrchestrator.rerankCandidates(...)` → `HybridRecallReranker.rerank(...)`。
`HybridRecallReranker` 现有 DEBUG 日志会记录调用时的 candidate count、semantic/keyword
score count、keyword 开关和实际权重 profile。

`256` 不是 Vector-only 的候选池。它是 keyword namespace scan 的 candidate pool
limit；Vector 路使用 `max(16, query.topK * 4)`。本次同时评测到 K=10，因此实际
Vector 候选上限为 40，与报告中的最大值一致。

退化原因如下：

1. Vector-only 关闭 keyword，因此 keyword score 恒为 0。
2. `RecallBenchmarkRunner` 构造 fragment 时没有设置 importance；
   `MemoryFragment` 的 builder 默认值为 `0.5`，所以同一 case 的候选 importance
   全部相同。
3. 不启用 Rerank 时按 `max(0, semanticScore)` 排序；启用后分数为
   `semanticWeight * normalizedSemantic + importanceWeight * 0.5`。
4. 对同一 case，权重和 importance 常数相同，semantic weight 始终大于 0；归一化
   只是除以该 case 的最大值。因此新分数是旧分数的单调变换，稳定排序会保留原有
   顺序和 tie 顺序，TopK、MRR、NDCG 必然逐位一致。

新增的聚焦测试同时证明：恒定 importance 且 keyword 关闭时顺序保持不变；只改变
importance 后，Rerank 可以把 semantic 分更低的候选提升到第一名。因此它不是
pass-through。Vector+Rerank 平均快 `3.4 ms` 不能作为跳过阶段的证据：模式按固定顺序
运行，且没有针对该微小延迟差做统计检验，2～40 个候选的本地排序成本也远小于
embedding 和存储检索噪声。

这项审计修正的是**解释边界**：本次 Vector-only 对照只能证明“在 keyword 关闭且
importance 恒定时，Rerank 是冗余计算”，不能证明“Rerank 普遍无效”。如果要评估
Vector-only Rerank 的独立贡献，需要在新的开发集/验证集中提供有语义依据的非恒定
importance，或引入独立 cross-encoder 分数；不得在当前已使用的留出集上调参。

术语决策：保留 `HybridRecallReranker` 类名以兼容现有调用方，但结构化 diagnostics
与 benchmark JSON/Markdown 明确输出 `rerankerType=LINEAR_SCORE_FUSION`。现有组件
只融合 semantic、keyword 与 importance signal，不得描述为 Cross-Encoder。

## 八、分类结果

| Category | Keyword | Vector | Hybrid | Hybrid+Rerank | Best |
| --- | ---: | ---: | ---: | ---: | --- |
| knowledge-update | 0.7250 | 0.8083 | 0.7000 | 0.8583 | Hybrid+Rerank |
| multi-session | 0.2267 | 0.5400 | 0.2317 | 0.3600 | VectorOnly |
| single-session-assistant | 0.9000 | 0.8500 | 0.9500 | 0.9500 | Hybrid / Hybrid+Rerank |
| single-session-preference | 0.4917 | 0.8500 | 0.5083 | 0.7167 | VectorOnly |
| single-session-user | 0.8000 | 0.9500 | 0.8000 | 0.9500 | VectorOnly / Hybrid+Rerank |
| temporal-reasoning | 0.6000 | 0.8583 | 0.6000 | 0.8833 | Hybrid+Rerank |

类别异质性表明场景路由可能有价值，但每类只有 20 case。本留出集不得继续用于选择
路由阈值或调权重。

## 九、架构决策

1. 保留 Vector 路，并将 VectorOnly 作为当前 Recall/延迟首选参考策略。
2. VectorOnly 默认不启用 Rerank，因为当前输入契约中 keyword 关闭且 importance
   恒定，Rerank 数学上只能保持原顺序；这是一项避免冗余计算的条件化决策，不是
   “Rerank 普遍无效”的结论。
3. 保留 Hybrid+Rerank 作为场景化实验能力，不作为统一默认策略。
4. 在新开发集与新验证集建立前，不实现基于本留出集的固定路由规则。
5. KeywordOnly 不作为默认路径；当前 namespace 扫描不是可扩展倒排索引。
6. 不再引用 category-shared 运行的 `0.58` 作为 LongMemEval 标准结果。

## 十、工程改动

- `ops/datasets/convert-longmemeval.ps1`
  - 新增 `-NamespacePerCase`
  - namespace 模式互斥校验
- `ops/analyze-recall-decision.ps1`
  - 新增 `-RequireCaseIsolatedReturns`
  - 输出 `ForeignReturnedFragmentCount`
  - 汇总 Rerank 可识别性；`NON_IDENTIFIABLE` 时禁止效果结论
- `vortex-common/src/main/java/com/vortex/common/dto/RecallDiagnostics.java`
  - 输出 Rerank 四态、排序变化计数和 `rerankerType`
- `vortex-kernel/src/main/java/com/vortex/kernel/hmc/RecallOrchestrator.java`
  - 新增可配置 keyword candidate pool limit，默认 256
- `vortex-kernel/src/main/java/com/vortex/kernel/hmc/HybridRecallReranker.java`
  - 新增 DEBUG 调用诊断，记录输入候选数、score 数、keyword 开关和权重 profile
  - 使用同候选 no-rerank 基线输出可识别性与排序变化，类型为 Linear Score Fusion
- `vortex-kernel/src/test/java/com/vortex/kernel/hmc/RecallOrchestratorTest.java`
  - 新增目标位于第 101 条时仍可召回的回归测试
- `vortex-kernel/src/test/java/com/vortex/kernel/hmc/HybridRecallRerankerTest.java`
  - 新增恒定 importance 单调退化与非恒定 importance 实际重排测试
- `E:/1projects/claude/简历最新版.md`
  - 撤回无效 `0.58`
  - 更新为 VectorOnly Recall@5 `0.81`、case hit `0.93`

## 十一、限制与有效性威胁

- 120-case 是确定性分层切片，不是随机样本
- 六类等权，不代表原始类别比例或线上流量
- 每类仅 20 case，分类结论统计效力有限
- Oracle fragment recall 不等于 Agent 最终回答准确率
- 未运行外部 LLM generation
- 延迟来自本地环境，不是生产 P95/P99
- KeywordOnly 的实现是扫描原型，不代表成熟倒排索引性能
- Vector-only benchmark 的 fragment importance 恒为 0.5，无法识别 Rerank 在非恒定
  importance 或独立 reranker signal 下的贡献
- 本留出集已参与架构决策，不能继续用于调参

## 十二、可声称边界

允许：

- 报告 case-isolated 120-case、600 runs、0 errors
- 报告 VectorOnly Recall@5 0.81、case hit 0.93
- 报告 VectorOnly 相对 KeywordOnly +0.19 及其 bootstrap CI
- 报告发现并修正 Hybrid+Rerank Top5 43% 跨 case 污染
- 报告 Hybrid Rerank 对 Hybrid 有显著收益，但没有显著超过 VectorOnly

禁止：

- 将旧 `0.58` 表述为标准 LongMemEval 成绩
- 将 `0.58 -> 0.79` 表述为系统优化提升
- 将自建 `0.79 -> 0.95` 与公开 `0.81` 串成同一个前后实验
- 声称端到端 Agent 回答准确率提升
- 声称 Hybrid+Rerank 总体显著优于 VectorOnly
- 声称当前已经完成并验证场景路由
- 将 VectorOnly 与 Vector+Rerank 完全相同解释为 Rerank 未执行或普遍无效

## 十三、简历安全表述

> 官方 LongMemEval 120-case 配对消融，VectorOnly Recall@5 0.81：完成五种检索模式
> 600 次运行，并以返回 ID 审计定位 category-shared namespace 导致的
> Hybrid+Rerank Top5 43% 跨 case 污染；修正为 case-isolated 协议后，
> VectorOnly 较 KeywordOnly +0.19（paired bootstrap 95% CI +0.11～+0.26），
> 据此保留向量主路并将组合策略转为场景化实验。

## 十四、验证结果

- 120 cases：通过
- 600 paired runs：通过
- 0 benchmark errors：通过
- 五模式严格配对：通过
- Case-isolated return gate：通过
- Foreign returned fragments：0
- 旧污染报告拒绝测试：通过
- PowerShell AST syntax：通过
- `vortex-kernel + vortex-app` 完整测试：通过
- `HybridRecallRerankerTest`（7 tests）：通过
- Maven package：通过
- `git diff --check`：通过

## 十五、证据索引

正式原始报告：

- `ops/eval-reports/20260728-recall-decision-longmemeval-case-isolated-120-001/recall-benchmark-20260728-160415.json`
- `ops/eval-reports/20260728-recall-decision-longmemeval-case-isolated-120-001/recall-benchmark-20260728-160415.md`

正式分析：

- `ops/eval-reports/20260728-recall-decision-longmemeval-case-isolated-120-001/recall-decision-analysis.json`
- `ops/eval-reports/20260728-recall-decision-longmemeval-case-isolated-120-001/recall-decision-analysis.md`

持久化文档：

- `ops/runbooks/vortex-recall-longmemeval-evaluation-report-20260729.md`
- `ops/runbooks/vortex-recall-architecture-decision-20260728.md`

## 十六、复现命令

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

运行正式 benchmark：

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
java -jar ./vortex-app/target/vortex-app-0.1.0-eval-cli.jar recall-benchmark
```

生成统计并强制 case 隔离：

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
