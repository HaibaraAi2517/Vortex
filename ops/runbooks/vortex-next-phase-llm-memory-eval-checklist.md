# Vortex 下一阶段 LLM 记忆评测清单（历史参考）

> Status as of 2026-06-01: 本文保留为下一阶段真实 LLM 记忆评测路线的历史参考。
> 其中 P0 的 GenerationService、PromptAssembler、真实 eval runner、NoMemory/Memory/RecoveredMemory baseline、JSON/Markdown 报告、规则判分和 30-case v2.1 extended candidate audit 已经在后续实现中推进完成或部分超越。
> 当前不要把本文当作待办清单逐条照做；新的优先级应以最新 baseline/audit runbook 和代码状态为准。

目标：只保留和“真实测试大模型金鱼记忆”直接相关的内容，形成下一阶段最小可行闭环。

不纳入本阶段：

- Netty 接入层
- Raft / 多节点共识
- Redis / RocksDB 路线切换
- 多 Agent 总线
- 自定义二进制协议

这些都重要，但它们不影响你先回答最关键的问题：

`Vortex 现在到底能不能让真实大模型更不容易忘？`

## 阶段目标

下一阶段只做一件事：

建立“真实大模型 + Vortex 记忆系统 + 可量化评测”的端到端闭环。

最终要能自动比较至少两种模式：

1. 无记忆
2. 有记忆

最好能比较四种模式：

1. 无记忆
2. 仅 L1
3. L1 被淘汰后依赖 L2/L3 恢复
4. 连续 feedback 后的学习增强

## P0

P0 的目标不是做漂亮架构，而是尽快拿到第一批真实结果。

### 1. 接入真实大模型生成接口

历史状态：当时项目只有 embedding，没有真实回答生成。当前已经有 OpenAI-compatible generation 接入；本节保留为实现目标来源。

需要新增：

- `GenerationService` 接口
- 一个 OpenAI-compatible chat client 实现
- 基础配置项：
  - `baseUrl`
  - `apiKey`
  - `model`
  - `temperature`
  - `maxTokens`
  - `timeout`

要求：

- 默认 `temperature=0`
- 支持记录 request / response 元数据
- 调用失败时能落日志并保留评测上下文

建议新增文件：

- `vortex-kernel/src/main/java/com/vortex/kernel/generation/GenerationService.java`
- `vortex-kernel/src/main/java/com/vortex/kernel/generation/OpenAiCompatibleGenerationService.java`
- `vortex-common/src/main/java/com/vortex/common/dto/GenerationRequest.java`
- `vortex-common/src/main/java/com/vortex/common/dto/GenerationResult.java`

### 2. 建立 Prompt 组装层

历史状态：当时 recall 只是返回 fragment，还没有真正给模型喂上下文。当前已经有 PromptAssembler；本节保留为模板设计目标来源。

需要新增一个 `PromptAssembler`，负责把下面几类信息拼起来：

- system prompt
- 用户问题
- 从 `/api/v1/memory/recall` 拿到的 fragment
- 可选 task context

要求：

- 输出固定模板，便于重复评测
- 明确标识每段记忆的 `fragmentId`
- 控制 prompt token 预算，避免 recall 回来太多但 prompt 塞不下

建议新增文件：

- `vortex-kernel/src/main/java/com/vortex/kernel/generation/PromptAssembler.java`

### 3. 建立最小问答评测集

如果没有金标集，就没法判断“记忆有没有真的提升”。当前已有 v2/v2.1 以及 v2.1 extended candidate 数据集，本节保留为数据集设计原则。

先做一个小而硬的评测集，不追求大，追求可控。

第一版建议：

- 20 到 50 条事实型记忆
- 20 到 50 个对应问题
- 每题有标准答案或关键事实点

数据字段建议：

- `caseId`
- `namespace`
- `memoryFragments`
- `question`
- `expectedAnswer`
- `expectedFragments`
- `tags`
- `difficulty`

建议新增文件：

- `vortex-app/src/test/resources/llm-memory-eval-set.json`

### 4. 建立端到端评测 Runner

这是本阶段最核心的东西。当前 runner 已经能跑 Baseline-NoMemory、Vortex-Memory 和 Vortex-RecoveredMemory，本节保留为流程定义依据。

Runner 的流程应固定为：

1. 写入 memory fragments
2. 发起 recall
3. 组装 prompt
4. 调真实模型生成答案
5. 记录召回内容、最终答案、耗时、token 用量
6. 和金标比对

最低输出字段：

- `caseId`
- `mode`
- `question`
- `recallSessionId`
- `returnedFragmentIds`
- `generatedAnswer`
- `isCorrect`
- `latencyMs`
- `promptTokens`
- `completionTokens`

建议新增：

- `vortex-app/src/test/java/com/vortex/app/integration/LlmMemoryEvalIT.java`
- 或单独的 `EvalRunner` 可执行类

### 5. 定义第一版核心指标

下一阶段先只盯 4 个结果指标：

1. `Answer Accuracy`
2. `Recall Hit@K`
3. `Recovered Recall Accuracy`
4. `End-to-End Latency`

最小判断口径：

- 无记忆 vs 有记忆，正确率是否显著提升
- 目标记忆从 L1 淘汰后，回答是否仍保持正确
- recall 命中但答案仍错的比例是多少

### 6. 建立两组 baseline

没有 baseline，就不知道 Vortex 带来了什么。

至少评测两组：

1. `Baseline-NoMemory`
   - 不调用 recall
   - 只把用户问题直接发给模型
2. `Vortex-Memory`
   - recall 后拼接记忆再发给模型

如果还有余力，再补：

3. `Vortex-RecoveredMemory`
   - 刻意触发 L1 eviction，再走 L2/L3 恢复

## P1

P1 的目标是把“能跑”提升到“能解释”。

### 7. 引入答案判定器

第一版可以先用规则判分，不要一开始就用 LLM-as-a-judge。当前已经实现结构化规则判分，本节保留为后续细化 failure reason 的参考。

优先级：

1. 精确匹配
2. 关键事实点匹配
3. 人工复核字段

建议：

- 每题提供 `mustContain` / `mustNotContain`
- 输出失败原因，比如：
  - `recall_miss`
  - `answer_hallucinated`
  - `partial_correct`

### 8. 自动回写 feedback

如果答案正确，并且能定位用到的 fragment，就自动调用：

- `/api/v1/memory/feedback`

这样可以真正测：

- 有真实模型参与时，learning 是否还能提升结果

最低要求：

- 记录 `usedFragmentIds`
- `answerAccepted=true/false`
- 每轮结束后抓取 `/api/v1/memory/learning`

### 9. 做多轮对话遗忘测试

“金鱼记忆”不只是单轮问答，它更常见于多轮长对话。

建议新增一组多轮 case：

1. 第 1 轮写入事实
2. 中间插入多轮无关对话
3. 最后再问早先事实

比较：

- 无记忆模式是否忘
- Vortex 模式是否还能找回

### 10. 建立强制 eviction 场景

为了证明“淘汰后可恢复”，必须人为制造压力。

做法：

- 把 L1 token 配额调小
- 写入 filler memory
- 确认目标 fragment 被挤出
- 再发问题，看答案是否依然正确

这部分输出要单独统计：

- `evictedBeforeAnswer=true/false`
- `recalledFromTier=L1/L2/L3`

## P2

P2 的目标是让这套评测能持续跑、能看趋势。

### 11. 生成评测报告

每次运行后输出结构化报告：

- 总正确率
- 各模式正确率
- 按难度分组正确率
- recall 命中率
- L2/L3 恢复成功率
- 平均/TP95 延迟

建议产物：

- JSON 报告
- Markdown 汇总

建议新增目录：

- `ops/eval-reports/`

### 12. 接入 health / slo 观测

真实 LLM 评测时，不仅看答案对不对，也看记忆系统状态。

每轮评测同时抓：

- `/api/v1/memory/health`
- `/api/v1/memory/slo/report`
- `/actuator/prometheus`

重点看：

- `recallLatencyP99Ms`
- `checkpointRecoverySuccessRate`
- `persistenceSuccessRate`
- `baselineRelativeLift`
- `shadowRelativeLift`

### 13. 做 nightly 回归

把评测分成两层：

- PR 冒烟：小数据集，少量 case
- Nightly：完整数据集，含 eviction / learning / 多轮对话

目标：

- 发现 recall 排序回退
- 发现 learning 回退
- 发现真实模型接入后端到端效果回退

## 交付顺序

按这个顺序做，最稳：

1. `GenerationService`
2. `PromptAssembler`
3. 小型 `llm-memory-eval-set.json`
4. `Baseline-NoMemory` vs `Vortex-Memory`
5. 强制 eviction case
6. 自动 feedback + learning 观测
7. 多轮遗忘测试
8. 报告和 nightly

## 暂不做

这阶段明确先不做：

- Netty 化
- Raft 化
- Redis L1
- RocksDB L3
- 多 Agent 调度器
- 自定义协议
- 分布式锁协调层

原因很简单：

这些都不会比“先证明真实模型是否真的更不容易忘”更优先。

## 最小验收标准

下一阶段完成的最低标准是：

1. 能调用一个真实大模型完成回答生成
2. 能比较 `NoMemory` 和 `Vortex-Memory` 两种模式
3. 能输出至少 20 个 case 的自动评测结果
4. 能证明至少 1 组 eviction 后恢复仍然保持正确回答
5. 能在真实模型参与下回写 feedback，并观察 `/api/v1/memory/learning` 变化

如果这 5 条做到了，你这个项目就从“记忆内核演示”进入“真实大模型记忆效果验证系统”阶段了。
