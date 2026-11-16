# 中文跨会话记忆质量基线

这项评测使用真实回答模型、生产 Python `MemorySession` 和 Vortex REST 服务，回答“哪些跨会话场景会答错”。它与 [Codex 接入验收](codex-memory-verification.md)分开：接入验收证明工具能调用、事实能共享；本评测保留失败案例，比较不同记忆方式对最后一次回答的影响。

数据集在 [`ops/datasets/memory-quality-zh-v1.json`](../ops/datasets/memory-quality-zh-v1.json)。所有对话均为项目自建的合成场景，不包含真实用户资料，尚未经过独立人工标注审查。`dev` 用于分析与迭代；`test` 应冻结，避免根据测试答案修改产品后再把同一批结果称作独立验证。运行 `--split all` 会披露两个集合的结果，报告仍保留各案例 split。

## 三种比较方式

| arm | 跨会话保留什么 | 每轮发生什么 |
| --- | --- | --- |
| `none` | 无长期记忆 | 用当前会话最多 6 轮历史生成回答 |
| `summary` | 最多 2048 字符的自由文本摘要 | 先回答，再用同一模型根据旧摘要、当前消息和本会话最近 6 条用户原话更新摘要 |
| `vortex` | 服务中真实保存的五个 `PERSONAL_FACTS` 字段 | 生产 `MemorySession.chat()`：REST 读取与 HYBRID 召回、回答、抽取、CAS 提交 |

每个场景可包含多个会话。新会话都创建新的 Session，丢弃上一会话的对话历史。最终问题始终位于一个独立新会话；Vortex 仍通过生产 `chat()` 处理问题，因而可以观察“问句错误地写入记忆”，没有为了评测而关闭抽取。

三种方式采用相同的 `CompatibleChatModel.from_env()` 配置、temperature 0、最多 2048 输出 token；每个 case/arm 有独立模型客户端。摘要使用字符预算，Vortex 使用现有检索 token 预算，两者并非经过测量的相同模型上下文成本。摘要不受五字段 schema 限制，未故意弱化为只能追加的文本。

摘要允许用本会话最近的用户原话解决“那里”“换成这个”等指代，不把助手回答当作事实证据。Vortex 按原生产抽取 payload 运行，目前只提供当前用户消息和字段快照。这里保留两种架构的差异，不为了比较而削弱普通摘要，也不在评测脚本中替生产 SDK 修补上下文。

## 运行

先启动使用真实本地 BGE 的 local 服务，参见 [本地服务指南](local-memory.md)。不要用测试用的随机 embedding 代替正式基线。脚本记录工作区模型、SDK、可执行 JAR 和数据集文件的 SHA-256；这些本地文件指纹不能单独证明已运行的服务加载了哪个 JAR，需要运行者核实服务启动方式。

安装 Python SDK，并通过环境配置真实模型。支持 `DEEPSEEK_API_KEY`，或 `MODEL_BASE_URL`、`MODEL_NAME`、`MODEL_API_KEY`。令牌由 SDK 从环境读取，不进入报告。不要把真实令牌写入命令示例或提交到仓库。

在仓库根目录运行（也可使用 `.\sdk\python\.venv\Scripts\python.exe` 替换 `python`）：

```powershell
python -m pip install -e ./sdk/python

# 校验数据并估计模型调用数，不访问模型或 REST，也不创建报告
python ops/eval-memory-quality.py --split all --dry-run

# 先验证少量案例的完整链路
python ops/eval-memory-quality.py --split dev --limit 3 --workers 2 `
  --output target/memory-quality-smoke.json

# 固定版本后生成完整基线；需要真实 API 调用
python ops/eval-memory-quality.py --split all --workers 4 `
  --output target/memory-quality-baseline.json
```

其他选项：`--dataset PATH`、`--url http://127.0.0.1:8080`、`--arms none summary vortex`、`--summary-chars 2048`。默认运行 `dev`，默认并发 2，允许 1–8。降低并发可减少供应商限流。每轮 `none` 使用一次模型调用，`summary` 和 `vortex` 使用两次；每个最终回答再使用一次独立裁判调用。`--dry-run` 会根据数据精确计算无故障时的调用数，不估算金额。

每个 Vortex 案例仅使用新生成的 `eval-quality-<随机值>` namespace。发现生成的空间已有事实或召回内容会拒绝执行；脚本不允许指定现有 namespace，也不会清理用户数据。评测数据会留在服务中用于追查。

输出路径或其 `*-evidence` 目录已经存在时拒绝覆盖。每完成一个 case/arm，就先保存独立证据文件，再原子更新汇总报告。运行中不要修改 SDK、runner、数据集、本地模型或 JAR；结束时指纹变化会标记 `SOURCE_CHANGED_DURING_RUN`。中断后的已完成证据保留，但当前版本没有自动续跑功能，重跑需选择新的输出路径。

## 如何读结果

报告包含最终答案、分类汇总、执行错误和每个证据文件的路径与 SHA-256。`*-evidence/<case>--<arm>.json` 保存：

- 每轮的原始用户输入、模型回答、自动更新、抽取/提交错误、召回结果；
- Vortex 每轮前后的五字段快照，最终问题的前后差异，以及最后的版本历史；
- 摘要每轮前后文本、预算截断标记；
- 所有模型请求消息、原始输出、调用类型及耗时，包括独立裁判请求和回答。

回答、抽取、摘要模型只接收场景原始对话、问题和自身记忆，不接收 expected answer、rubric、expected facts 或 forbidden claims。只有最后的独立裁判请求能看到原始用户会话、问题、参考答案与评分规则；它看不到 arm、namespace、写入状态或检索轨迹。

裁判使用同一配置的模型，输出 `answer_correct`、`abstention_correct`、`stale_claim` 和理由。布尔值类型错误、格式错误或逻辑矛盾都标记为裁判失败，保存原文，不自动重试或偷偷修复。旧信息仅被明确肯定为“当前事实”才算 `stale_claim`；“以前住北京，现在住上海”中的北京不是泄漏。

这些是**同模型自动裁判结果，不是人工准确率或普遍记忆能力排名**。同源模型可能偏爱自己的答案，合成场景也不代表真实用户分布。发布结论前应人工复核代表性成功与失败，并按需要使用独立模型或人工标注复核。

关键指标解释：

| 指标 | 分母与含义 |
| --- | --- |
| `answer_correct_rate_all_requested` | 正确数 / 所有请求案例数。没有有效裁判的失败案例留在分母中 |
| `answer_correct_rate_judged_only` | 正确数 / 有效裁判数。必须连同 `not_judged` 与执行错误一起看 |
| `stale_claims` | 有效裁判确认的过时或禁止的当前断言数；未裁判案例不视为“没有泄漏” |
| `required_abstention_correct` | 应拒答案例中正确承认不知道的数量，配合 `required_abstention_cases` |
| `fact_constraint_checks` | 仅 Vortex：最终提问前的事实状态与区分大小写的子串包含/排除检查。它是结构诊断，不是语义误写率 |
| `question_mutation_cases` | 最终问题让事实 revision/status/content/expiry/tags 变化的案例数，配合实际观测数 |
| `summary_question_text_changed_cases` | 摘要在问题后文本发生变化的数量；重述也可能变化，不能直接当作事实误写 |

`none` 和 `summary` 的字段检查记为 `null`，不会因为没有结构化事实就获得“完美写入准确率”。状态/子串检查和语义裁判分开，例如“现在住上海，不再住北京”可能触发严格 `none_of: ["北京"]`，并不表示回答肯定用户仍住北京。

每个 Vortex 结果同时保留 `fact_checks_before_question` 和 `fact_checks_after_question`，汇总以提问前为主。这样提问本身导致的误写不会与此前的学习质量混淆；提问后问题由 `question_mutation_cases` 和后置检查单独呈现。

抽取、提交、连接或裁判失败时，场景仍保留已经发生的事实与回答，总执行状态为错误，进程退出码为 2。正常完成但答案答错属于质量失败，进程退出码仍为 0。因此应同时查看运行状态和质量指标，不能把退出码 0 当成记忆质量全部通过。

报告记录模型调用数和时延。现有兼容适配器只返回文本，未暴露供应商 usage，因此 token 用量与金额明确为 `null`，没有虚构费用。并行执行时案例耗时之和不等于真实总墙钟时间，后者位于 `wall_seconds`。

## 下一轮怎样使用基线

先按失败原因分类：未抽取、错误覆盖、未召回、召回正确但回答错误、问句误写、错误拒答。只针对 dev 的主要错误调整产品，再用相同脚本、固定模型配置和新输出路径复测。比较时核对数据集 SHA、SDK/服务版本、运行错误和有效裁判数；将质量变化与部署/连接故障分开解释。

不要把此基线称作 LongMemEval 成绩、Codex/Claude 原生记忆对比或真人试用结果。它用于决定下一项改动是否值得做，并留下可审查的证据。
