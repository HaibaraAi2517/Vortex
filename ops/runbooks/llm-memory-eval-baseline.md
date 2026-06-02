# LLM Memory Eval Baseline

## 当前正式基线

截至 2026-05-29，当前正式 LLM memory eval 基线定义如下：

- 数据集：`classpath:llm-memory-eval-set-v2.json`
- generation model：`gpt-5.2`
- generation base URL：`https://sub2.congmingai.com/v1`
- BGE：真实 `BGE + ORT`
- L1 max tokens：`96`
- modes：
  - `Baseline-NoMemory`
  - `Vortex-Memory`
  - `Vortex-RecoveredMemory`
- eval system prompt SHA-256：`e61c3d26f927122fc933752ef727847b092c4e556a74047036c30cdbdecdfbe3`
- eval system prompt chars：`833`
- 评测 prompt 源：
  - [LlmMemoryEvalProperties.java](E:/1projects/claude/Vortex/vortex-app/src/main/java/com/vortex/app/eval/LlmMemoryEvalProperties.java:38)

## Prompt Contract

当前正式 prompt contract：

```text
You are running a strict grounded memory QA evaluation.
Use only the provided memory fragments.
When the memory supports an answer, give the final concrete answer, not an intermediate description.
Resolve references all the way to the leaf value, for example role -> person, alias -> canonical value, policy -> concrete setting, region indirection -> final region.
Prefer present-state facts over historical, previous, old, or legacy facts unless the question explicitly asks about history.
Do not treat a historical distractor as a conflict with a current fact unless both fragments explicitly describe the same current state.
If multiple fragments are needed, combine them and answer the question directly in the first sentence.
If the memory is insufficient, say so plainly.
Do not fabricate hidden facts or fragment identifiers.
```

这个 prompt 不是普通描述文案，而是评测标准的一部分。任何变更都意味着基线变更。

## 正式基线报告

当前正式基线报告批次：

- `20260529-real-bge-v2-006`
- [llm-memory-eval-20260529-140002.json](E:/1projects/claude/Vortex/ops/eval-reports/20260529-real-bge-v2-006/llm-memory-eval-20260529-140002.json:1)
- [llm-memory-eval-20260529-140002.md](E:/1projects/claude/Vortex/ops/eval-reports/20260529-real-bge-v2-006/llm-memory-eval-20260529-140002.md:1)

基线结果：

- `Baseline-NoMemory = 0/15`
- `Vortex-Memory = 15/15`
- `Vortex-RecoveredMemory = 15/15`
- `RecoveredAccuracy = 1.0`
- `RecoveredL2HitRate = 1.0`
- 环境区包含 prompt 指纹：
  - [Eval System Prompt SHA-256](E:/1projects/claude/Vortex/ops/eval-reports/20260529-real-bge-v2-006/llm-memory-eval-20260529-140002.md:19)
  - [Eval System Prompt Chars](E:/1projects/claude/Vortex/ops/eval-reports/20260529-real-bge-v2-006/llm-memory-eval-20260529-140002.md:20)

## Baseline Profiles

真实 LLM eval 现在用 profile 机器区分“单轮严格复现”和“多轮稳定性门禁”，避免把候选数据集、audit gate 和正式 strict baseline 混成同一个信号。

当前 profile：

1. `official-v2-strict`
   - 数据集：`classpath:llm-memory-eval-set-v2.json`
   - baseline id：`20260529-real-bge-v2-006`
   - 语义：正式单轮 strict baseline，要求 `0/15, 15/15, 15/15`
2. `audit-v2-stability`
   - 数据集：`classpath:llm-memory-eval-set-v2.json`
   - baseline id：`20260601-mode-scoped-l2-wait-audit-5x-net`
   - 语义：v2 多轮稳定性 audit gate，不用于单个报告 strict verify
3. `official-v2.1-strict`
   - 数据集：`classpath:llm-memory-eval-set-v2-1.json`
   - baseline id：`20260601-v2-009-contract-audit-5x-net`
   - 语义：正式 v2.1 单轮 strict baseline，要求 `0/15, 15/15, 15/15`
4. `contract-v2.1-candidate`
   - 数据集：`classpath:llm-memory-eval-set-v2-1.json`
   - baseline id：`20260601-v2-009-contract-audit-5x-net`
   - 语义：`official-v2.1-strict` 的过渡 alias
5. `official-v2.1-extended-strict`
   - 数据集：`classpath:llm-memory-eval-set-v2-1-extended.json`
   - baseline id：`20260602-v2-1-extended-candidate-audit-generation-retry-001`
   - 语义：正式 v2.1 extended 单轮 strict baseline，要求 `0/30, 30/30, 30/30`
6. `candidate-v2.1-extended`
   - 数据集：`classpath:llm-memory-eval-set-v2-1-extended.json`
   - baseline id：`candidate-v2.1-extended`
   - 语义：v2.1 extended 晋升前的历史 audit-only profile，不用于单个报告 strict verify

`eval-cli verify` 默认使用 `official-v2-strict`。其它 strict profile 需要显式传入：

```powershell
java -jar .\vortex-app\target\vortex-app-0.1.0-SNAPSHOT-eval-cli.jar verify --list-profiles

java -jar .\vortex-app\target\vortex-app-0.1.0-SNAPSHOT-eval-cli.jar verify `
  --profile official-v2.1-strict `
  --describe
```

```powershell
java -jar .\vortex-app\target\vortex-app-0.1.0-SNAPSHOT-eval-cli.jar verify `
  --profile official-v2.1-strict `
  .\ops\eval-reports\20260601-v2-009-contract-audit-5x-net\runs\20260601-v2-009-contract-audit-5x-net-run01\llm-memory-eval-*.json
```

```powershell
java -jar .\vortex-app\target\vortex-app-0.1.0-SNAPSHOT-eval-cli.jar verify `
  --profile official-v2.1-extended-strict `
  --describe
```

Phase 2 extended baseline 决策见：

- [vortex-baseline-governance-phase-2-decision.md](E:/1projects/claude/Vortex/ops/runbooks/vortex-baseline-governance-phase-2-decision.md:1)

## 候选多轮审计基线

当前推荐作为多轮稳定性门禁参考的候选 audit baseline 是：

- 报告批次：`20260601-mode-scoped-l2-wait-audit-5x-net`
- baseline profile：`audit-v2-stability`
- strict verifier profile：`official-v2-strict`
- 汇总报告：
  - [baseline-audit-summary.json](E:/1projects/claude/Vortex/ops/eval-reports/20260601-mode-scoped-l2-wait-audit-5x-net/baseline-audit-summary.json:1)
  - [baseline-audit-summary.md](E:/1projects/claude/Vortex/ops/eval-reports/20260601-mode-scoped-l2-wait-audit-5x-net/baseline-audit-summary.md:1)
- 结果：
  - `OverallPassed = true`
  - `AuditGate.Passed = true`
  - `StrictVerifierPassed = false`
  - `VerifierPassCount = 4/5`
  - `Baseline-NoMemory correct values = 0, 0, 0, 0, 0`
  - `Vortex-Memory accuracy values = 1.0000, 1.0000, 0.9333, 1.0000, 1.0000`
  - `Vortex-RecoveredMemory recoveredAccuracy values = 1.0000, 1.0000, 1.0000, 1.0000, 1.0000`
  - `Vortex-RecoveredMemory recoveredL2HitRate values = 1.0000, 1.0000, 1.0000, 1.0000, 1.0000`
  - `CaseFailureCount = 1`
  - `CaseFailureGroupCount = 1`

这个候选 audit baseline 不能替换 `20260529-real-bge-v2-006` 的单轮正式基线。它的角色是多轮真实 LLM 稳定性门禁：允许单轮生成波动，但要求环境不漂移、NoMemory 保持 0、Memory / Recovered 的多轮均值达到 audit gate 阈值。

该批次验证了 eval runner 的写后读一致性修复和 mode-scoped L2 隔离修复：在 `Vortex-Memory` 和 `Vortex-RecoveredMemory` 中，runner 会等待本 case 的 expected fragments 写入当前 mode namespace 的 L2 后再开始 recall；eval fragment id 也带有 mode 作用域，避免同一 run 内不同 mode 的异步 persist / cleanup 通过相同 Milvus 主键互相覆盖。

剩余失败分层：

1. `v2-009`：片段齐全但模型偶发拒绝从 `Thursday 08:00 UTC` + `one hour after` 推出 `Thursday`。这是 generation contract / 样本文案问题，不是召回问题。
2. `v2-007`：本批次未再出现 recovered miss；`Vortex-RecoveredMemory` 的 `RecoveredAccuracy` 和 `RecoveredL2HitRate` 均为 5/5。

## 正式 Eval Contract v2.1

`v2-009` 的剩余波动来自样本文案没有显式说明 `one hour after` 是从 localization freeze start 起算且不跨 weekday。为避免把隐含时间推导 contract 混入 memory/recovery 能力评测，新增独立数据集：

- 数据集：`classpath:llm-memory-eval-set-v2-1.json`
- 变更：仅调整 `v2-009::mobile-cutoff`
  - v2：`The mobile release happens one hour after the localization freeze.`
  - v2.1：`The mobile release happens one hour after the localization freeze starts, on the same weekday.`

v2.1 正式 strict profile：

- 报告批次：`20260601-v2-009-contract-audit-5x-net`
- baseline profile：`official-v2.1-strict`
- strict verifier profile：`official-v2.1-strict`
- 汇总报告：
  - [baseline-audit-summary.json](E:/1projects/claude/Vortex/ops/eval-reports/20260601-v2-009-contract-audit-5x-net/baseline-audit-summary.json:1)
  - [baseline-audit-summary.md](E:/1projects/claude/Vortex/ops/eval-reports/20260601-v2-009-contract-audit-5x-net/baseline-audit-summary.md:1)
- 结果：
  - `OverallPassed = true`
  - `AuditGate.Passed = true`
  - `ProfileGate.Passed = true`
  - `StrictVerifierPassed = true`
  - `VerifierPassCount = 5/5`
  - `Baseline-NoMemory correct values = 0, 0, 0, 0, 0`
  - `Vortex-Memory accuracy values = 1.0000, 1.0000, 1.0000, 1.0000, 1.0000`
  - `Vortex-RecoveredMemory recoveredAccuracy values = 1.0000, 1.0000, 1.0000, 1.0000, 1.0000`
  - `Vortex-RecoveredMemory recoveredL2HitRate values = 1.0000, 1.0000, 1.0000, 1.0000, 1.0000`
  - `CaseFailureCount = 0`
  - `CaseFailureGroupCount = 0`

`official-v2.1-strict` 已是独立 strict profile，因此该批次可以用 v2.1 数据集执行单轮 strict verify。`contract-v2.1-candidate` 保留为过渡 alias；默认 `verify <report>` 仍然使用 `official-v2-strict`，不自动迁移到 v2.1。

升级提案见：

- [llm-memory-eval-v2-1-upgrade-proposal.md](E:/1projects/claude/Vortex/ops/runbooks/llm-memory-eval-v2-1-upgrade-proposal.md:1)

## 判定标准

只有同时满足以下条件，新的真实报告才可以替换当前正式基线：

1. 使用真实 `BGE + ORT + generation API`
2. 使用相同数据集，或明确宣布数据集升级
3. 使用相同 prompt contract，或明确宣布 prompt contract 升级
4. `Baseline-NoMemory = 0`
5. `Vortex-Memory = 100%`
6. `Vortex-RecoveredMemory = 100%`
7. `RecoveredAccuracy = 1.0`
8. `RecoveredL2HitRate = 1.0`

## 可执行门禁

当前推荐的真实 LLM 稳定性门禁是多轮 audit gate。它复用已有单轮报告，只补缺失轮次；加上 `-FailOnAuditGateFailure` 后，`AuditGate.Passed = false` 或 `ProfileGate.Passed = false` 都会让命令失败，单轮 strict verifier 漂移不会直接失败。

`ProfileGate` 会检查：

1. `DatasetLocation` 推断出的 `DatasetVersion`、`BaselineProfile`、`StrictVerifierProfile`
2. 显式传入的 profile 是否属于该数据集
3. 每轮 report environment 中的 `datasetVersion`、`baselineProfileId`、`strictVerifierProfileId`
4. 每轮 verifier 实际使用的 profile

```powershell
powershell -ExecutionPolicy Bypass -File .\ops\run-llm-memory-baseline-audit.ps1 `
  -ApiKey '<api-key>' `
  -BaseUrl 'https://sub2.congmingai.com' `
  -Model 'gpt-5.2' `
  -Rounds 5 `
  -DatasetLocation 'classpath:llm-memory-eval-set-v2.json' `
  -AuditStamp '<new-audit-stamp>' `
  -EvalParallelism 32 `
  -SkipComposeUp `
  -SkipPackage `
  -FailOnAuditGateFailure
```

`-EvalParallelism` controls bounded in-process eval concurrency. Default is `1`; for real LLM audit start with `24` or `32`, then raise only if the provider remains stable.

单轮 strict baseline verifier 仍保留，用于确认某个 `llm-memory-eval-*.json` 是否完全复现正式 15/15 基线。

先打包 `eval-cli`：

```powershell
mvn -pl vortex-app -am -DskipTests package
```

再校验报告：

```powershell
java -jar vortex-app/target/vortex-app-0.1.0-SNAPSHOT-eval-cli.jar verify `
  ops/eval-reports/20260529-real-bge-v2-006/llm-memory-eval-20260529-140002.json
```

判定规则：

1. 成功时返回退出码 `0`，并输出该报告仍符合当前正式基线。
2. 漂移时返回退出码 `2`，并逐项输出 `field / expected / actual`。
3. 报告无法读取或命令参数错误时返回非零失败码。

## 基线失效条件

出现以下任一情况，就不能再把旧报告当作同一基线：

1. `systemPrompt` 文本变化
2. 数据集变化
3. generation model 变化
4. base URL / provider 变化
5. L1 max tokens 变化
6. recall / runner 语义变化
7. judge 语义变化

## 追溯要求

从当前版本开始，正式报告必须可直接追溯这些关键信息：

1. dataset location
2. generation base URL / model / timeout
3. BGE model path
4. L1 max tokens
5. eval system prompt SHA-256
6. modes

如果报告缺少上述信息，就不能作为正式基线报告。
