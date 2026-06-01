# LLM Memory Eval CLI Runbook

## 目标

用一次性 CLI 进程执行当前正式 LLM memory 真实评测闭环：

- 启动 Spring 上下文但不启动 Web 服务
- 加载指定评测集
- 执行 `Baseline-NoMemory` / `Vortex-Memory` / `Vortex-RecoveredMemory`
- 写出 JSON + Markdown 报告
- 记录 generation / recall / environment / prompt 指纹
- 进程结束后立即退出

截至 2026-05-29，真实 `BGE + ORT + 真实 generation API` 已经是正式链路，不再是仅验证流程的临时方案。

## 当前正式基线

当前推荐作为正式对外引用的真实评测基线是：

- 数据集：`classpath:llm-memory-eval-set-v2.json`
- 报告批次：`20260529-real-bge-v2-006`
- 报告：
  - [llm-memory-eval-20260529-140002.json](E:/1projects/claude/Vortex/ops/eval-reports/20260529-real-bge-v2-006/llm-memory-eval-20260529-140002.json:1)
  - [llm-memory-eval-20260529-140002.md](E:/1projects/claude/Vortex/ops/eval-reports/20260529-real-bge-v2-006/llm-memory-eval-20260529-140002.md:1)
- 结果：
  - `Baseline-NoMemory = 0/15`
  - `Vortex-Memory = 15/15`
  - `Vortex-RecoveredMemory = 15/15`
  - `RecoveredAccuracy = 1.0`
  - `RecoveredL2HitRate = 1.0`
  - `Eval System Prompt SHA-256 = e61c3d26f927122fc933752ef727847b092c4e556a74047036c30cdbdecdfbe3`

基线说明见：

- [llm-memory-eval-baseline.md](E:/1projects/claude/Vortex/ops/runbooks/llm-memory-eval-baseline.md:1)

当前候选多轮 audit baseline 是：

- 报告批次：`20260601-mode-scoped-l2-wait-audit-5x-net`
- 汇总报告：
  - [baseline-audit-summary.json](E:/1projects/claude/Vortex/ops/eval-reports/20260601-mode-scoped-l2-wait-audit-5x-net/baseline-audit-summary.json:1)
  - [baseline-audit-summary.md](E:/1projects/claude/Vortex/ops/eval-reports/20260601-mode-scoped-l2-wait-audit-5x-net/baseline-audit-summary.md:1)
- 结论：`AuditGate.Passed = true`，`StrictVerifierPassed = false`，`VerifierPassCount = 4/5`

它用于多轮稳定性门禁，不替代 `20260529-real-bge-v2-006` 的单轮 strict baseline。

## 评测 Prompt Contract

当前正式评测 prompt 定义在：

- [LlmMemoryEvalProperties.java](E:/1projects/claude/Vortex/vortex-app/src/main/java/com/vortex/app/eval/LlmMemoryEvalProperties.java:38)

评测 contract 要点：

- 只能使用提供的 memory fragments
- 能回答时必须输出最终叶子答案，不能停在中间角色或中间策略层
- 当前态优先于历史 / old / legacy / previous
- 历史片段默认视为 distractor，除非问题显式问历史
- 需要多跳时必须走完多跳，再在首句直接回答

从现在开始，任何真实评测结论都必须绑定这版 prompt contract；如果 prompt 文本变化，必须刷新基线报告。

## 适用场景

适合现在这类验证：

- 真实模型 generation 已接通
- Milvus / MinIO / etcd 已就绪
- 需要得到可对比、可追溯的真实评测结果
- 需要判断问题出在 memory / recall / generation contract 的哪一层

评测 runner 在 `Vortex-Memory` 和 `Vortex-RecoveredMemory` 中会先等待本 case 的 expected fragments 写入当前 mode namespace 的 L2 记录，再开始 recall。eval fragment id 带有 mode 作用域，避免同一 run 内 `Vortex-Memory` / `Vortex-RecoveredMemory` 的异步 persist 或 cleanup 互相覆盖。这个等待用于消除 L1 容量较低时的写后读竞态；否则刚写入的多跳目标可能已经被 L1 挤出，但异步 L2 持久化还没追上，导致真实 eval 把持久化时序误判为召回退化。

## 当前已知限制

1. OpenAI-compatible 网关 base URL 需要写成带 `/v1` 的形式。
2. `safe-hash` 模式仍可用于本地排障，但它不是正式语义基线。
3. 正式评测必须使用真实 BGE ONNX、真实 generation API、以及正式数据集。
4. 如果更换数据集、prompt contract、L1 token 上限、generation model，历史结论不能直接横向比较。

## 前置条件

确认以下服务或资源可用：

1. Milvus 已启动并可访问。
2. MinIO 已启动并可访问。
3. 本地 BGE tokenizer/model 文件存在。
4. 真实 generation API Key 可用。

本机验证过的关键点：

- generation base URL 应为 `https://sub2.congmingai.com/v1`
- BGE 模型目录应包含：
  - `model.onnx`
  - `tokenizer.json`

## 推荐运行方式

优先使用仓库内脚本，而不是直接调用 `exec:java`。脚本会：

- 规范化 `baseUrl` 为带 `/v1`
- 确认 BGE 模型文件存在
- 启动并等待 `docker compose` 依赖健康
- 执行 `mvn -pl vortex-app -am -DskipTests package`
- 运行独立的 `eval-cli` Spring Boot jar
- 为本次运行生成独立 report / Milvus collection / MinIO prefix

在仓库根目录执行：

```powershell
powershell -ExecutionPolicy Bypass -File .\ops\run-real-llm-memory-eval.ps1 `
  -ApiKey '...' `
  -BaseUrl 'https://sub2.congmingai.com' `
  -Model 'gpt-5.2' `
  -Stamp '20260529-real-bge-014'
```

当前正式推荐运行方式是直接跑 `v2`：

```powershell
powershell -ExecutionPolicy Bypass -File .\ops\run-real-llm-memory-eval.ps1 `
  -ApiKey '...' `
  -BaseUrl 'https://sub2.congmingai.com' `
  -Model 'gpt-5.2' `
  -Stamp '20260529-real-bge-v2-006' `
  -DatasetLocation 'classpath:llm-memory-eval-set-v2.json'
```

如果要切换到其它评测集，只改 `-Stamp` 和 `-DatasetLocation`，其它环境保持不变。

## 手动运行方式

如果需要手动排障，再使用下面这组显式命令：

```powershell
$stamp='20260529-real-bge-v2-manual'
$env:BGE_MODEL_PATH='E:/1projects/claude/Vortex/models/bge-small-zh'
Remove-Item Env:VORTEX_KERNEL_EMBEDDING_BGE_SAFE_HASH_MODE -ErrorAction SilentlyContinue
$env:VORTEX_GENERATION_ENABLED='true'
$env:VORTEX_GENERATION_BASE_URL='https://sub2.congmingai.com/v1'
$env:VORTEX_GENERATION_API_KEY='...'
$env:VORTEX_GENERATION_MODEL='gpt-5.2'
$env:VORTEX_EVAL_MODES='BASELINE_NO_MEMORY,VORTEX_MEMORY,VORTEX_RECOVERED_MEMORY'
$env:VORTEX_STORAGE_L1_MAX_TOKENS='96'
$env:VORTEX_EVAL_DATASET_LOCATION='classpath:llm-memory-eval-set-v2.json'
$env:VORTEX_EVAL_REPORT_OUTPUT_DIR=("ops/eval-reports/" + $stamp)
$env:VORTEX_STORAGE_L2_MILVUS_COLLECTION=("vortex_memory_eval_" + $stamp.Replace('-','_'))
$env:MINIO_KEY_PREFIX=("eval/" + $stamp + "/")
mvn -pl vortex-app -am -DskipTests package
java -jar vortex-app/target/vortex-app-0.1.0-SNAPSHOT-eval-cli.jar
```

## 参数说明

`BGE_MODEL_PATH`

- 指向本地 BGE 模型目录。
- `spring-boot:run` 或 `exec:java` 容易带入 Maven 本身的类路径与生命周期噪音。
- 真实评测优先使用打包后的 `eval-cli` jar，模型路径仍建议用绝对路径。

`VORTEX_KERNEL_EMBEDDING_BGE_SAFE_HASH_MODE`

- 正式真实评测不要设置它。
- 只有在本地排障、并且明确只看流程不看语义时，才临时打开。

`VORTEX_GENERATION_BASE_URL`

- 必须带 `/v1`。
- 已验证根路径 `https://sub2.congmingai.com` 返回 HTML，不是 JSON API。

`VORTEX_STORAGE_L2_MILVUS_COLLECTION`

- 每次运行使用独立 collection，避免污染默认 `vortex_memory`。
- 同时规避历史测试留下的 4 维 collection 冲突。

`MINIO_KEY_PREFIX`

- 每次运行使用独立前缀，避免报告期写入与已有对象互相干扰。

`VORTEX_STORAGE_L1_MAX_TOKENS=96`

- 压低 L1 容量，帮助 `Vortex-RecoveredMemory` 稳定触发 recovery 场景。

`VORTEX_EVAL_DATASET_LOCATION`

- 推荐正式基线使用 `classpath:llm-memory-eval-set-v2.json`
- 如果切换数据集，必须在报告结论中明确写出新数据集位置

## 报告追溯要求

正式报告至少要记录并核对以下环境项：

1. generation base URL / model / timeout
2. BGE model path
3. L1 max tokens
4. dataset location
5. eval system prompt SHA-256
6. modes

从 2026-05-29 起，prompt 指纹会直接写入报告环境区；如果 prompt SHA-256 不同，就不是同一条评测基线。

## 报告位置

报告会写到：

```text
ops/eval-reports/<stamp>/
```

产物包括：

- `llm-memory-eval-*.json`
- `llm-memory-eval-*.md`

## 基线校验

`eval-cli` 现在支持 `verify` 子命令，可把正式基线变成自动门禁，而不是人工目检。

使用方式：

```powershell
java -jar vortex-app/target/vortex-app-0.1.0-SNAPSHOT-eval-cli.jar verify `
  ops/eval-reports/20260529-real-bge-v2-006/llm-memory-eval-20260529-140002.json
```

当前 verifier 会校验：

1. `datasetLocation`
2. `generationBaseUrl`
3. `generationModel`
4. `l1MaxTokens`
5. `evalSystemPromptSha256`
6. `modes`
7. `Baseline-NoMemory = 0/15`
8. `Vortex-Memory = 15/15, accuracy = 1.0`
9. `Vortex-RecoveredMemory = 15/15, accuracy = 1.0`
10. `RecoveredAccuracy = 1.0`
11. `RecoveredL2HitRate = 1.0`

输出约定：

1. 通过时输出清晰 PASS 结论，并返回退出码 `0`
2. 漂移时输出逐项 drift 明细，并返回退出码 `2`

## 基线审计

`ops/run-llm-memory-baseline-audit.ps1` 会连续跑多轮真实 eval，并在每轮结束后自动调用 `verify`，最后生成稳定性汇总报告。

审计脚本有两层结论：

1. `StrictVerifierPassed`：每一轮都必须完全匹配正式单轮基线，也就是 `Vortex-Memory = 15/15` 且 `Vortex-RecoveredMemory = 15/15`。这个信号用于发现相对 `20260529-real-bge-v2-006` 的严格漂移。
2. `AuditGate.Passed` / `OverallPassed`：多轮真实 LLM 稳定性 gate。默认允许真实模型输出波动，但要求环境不漂移、NoMemory 保持 0、Memory/Recovered 的多轮均值达到阈值。

默认 audit gate 阈值：

1. `Baseline-NoMemory` 每轮最大 correct 不超过 `0`
2. `Vortex-Memory` 多轮平均 accuracy 不低于 `0.85`
3. `Vortex-RecoveredMemory` 多轮平均 recoveredAccuracy 不低于 `0.95`
4. `Vortex-RecoveredMemory` 多轮平均 recoveredL2HitRate 不低于 `0.95`
5. 所有轮次的 dataset / baseUrl / model / L1 token 上限 / prompt SHA / modes 必须一致

推荐命令：

```powershell
powershell -ExecutionPolicy Bypass -File .\ops\run-llm-memory-baseline-audit.ps1 `
  -ApiKey '...' `
  -BaseUrl 'https://sub2.congmingai.com' `
  -Model 'gpt-5.2' `
  -Rounds 5 `
  -DatasetLocation 'classpath:llm-memory-eval-set-v2.json' `
  -AuditStamp '20260601-baseline-audit'
```

如果容器已经启动、`eval-cli` jar 也已经打好，可以跳过这两步：

```powershell
powershell -ExecutionPolicy Bypass -File .\ops\run-llm-memory-baseline-audit.ps1 `
  -ApiKey '...' `
  -BaseUrl 'https://sub2.congmingai.com' `
  -Model 'gpt-5.2' `
  -Rounds 5 `
  -DatasetLocation 'classpath:llm-memory-eval-set-v2.json' `
  -AuditStamp '20260601-baseline-audit' `
  -SkipComposeUp `
  -SkipPackage
```

恢复语义：

1. 默认会复用 `ops/eval-reports/<audit-stamp>/runs/<audit-stamp>-runNN/` 下已存在的报告
2. 对已存在报告不会重跑 eval，只会重新执行 verifier 并重建汇总
3. 如需强制重跑已有轮次，显式加 `-ForceRerunExisting`

如果要把 audit gate 用作 CI/脚本门禁，追加 `-FailOnAuditGateFailure`；这只按 `AuditGate.Passed` 退出，不会因为严格 verifier 漂移自动失败。

产物位置：

1. 每轮真实报告：`ops/eval-reports/<audit-stamp>/runs/<audit-stamp>-runNN/`
2. 汇总 JSON：`ops/eval-reports/<audit-stamp>/baseline-audit-summary.json`
3. 汇总 Markdown：`ops/eval-reports/<audit-stamp>/baseline-audit-summary.md`

汇总内容包括：

1. 每轮报告路径
2. 每轮 verifier 结论与输出
3. 每轮 3 个 mode 的关键结果
4. 总体通过率和关键基线指标序列
5. `AuditGate`：多轮统计 gate 的阈值、实际均值和逐项 check
6. `CaseFailureSummary`：按 `caseId + mode` 聚合的失败次数、失败轮次、召回命中/未命中次数、缺失的 expected fragments
7. `CaseFailureDetails`：逐轮失败明细，包括 returned fragments、missing expected fragments、召回 tiers、生成答案和对应报告路径

排查漂移时先看 `CaseFailureSummary`：

1. `MissingExpectedFragments` 非空：优先查召回排序、L1 token budget、L2 enrichment 和 tag 过滤。
2. `MissingExpectedFragments` 为空但答案错误：优先查 generation prompt 是否停在中间描述、被 distractor 带偏，或判分规则是否过窄。
3. `RecallMissFailureCount` 非零：优先查召回链路或底层存储；`RecallHitFailureCount` 非零：优先查多跳推理和生成稳定性。

`20260601-mode-scoped-l2-wait-audit-5x-net` 的剩余失败分层：

1. `v2-009`：returned fragments 包含 `old-release-window,mobile-cutoff,localization-freeze`，expected fragments 不缺；失败来自模型过度谨慎，没有把 `Thursday 08:00 UTC` 加一小时仍在 Thursday 作为最终答案。
2. `v2-007`：本批次未再出现 recovered miss；`Vortex-RecoveredMemory` 的 `RecoveredAccuracy` 和 `RecoveredL2HitRate` 均为 5/5。

## 成功判定

单轮真实 eval 的正式成功标准：

1. CLI 命令能完成并退出。
2. 目录下生成 `.json` 和 `.md` 报告。
3. 报告中至少包含 3 个 mode：
   - `Baseline-NoMemory`
   - `Vortex-Memory`
   - `Vortex-RecoveredMemory`
4. `Baseline-NoMemory = 0`
5. `Vortex-Memory = 100%`
6. `Vortex-RecoveredMemory = 100%`
7. `RecoveredAccuracy = 1.0`
8. `RecoveredL2HitRate = 1.0`

如果没有达到上述标准，不要覆盖当前正式基线，只能作为候选报告继续分析。

多轮 baseline audit 的正式成功标准：

1. 脚本完成并生成 `baseline-audit-summary.json` / `.md`
2. `AuditGate.Passed = true`
3. `OverallPassed = true`
4. `StrictVerifierPassed` 可以为 `false`；这表示单轮严格 15/15 基线有漂移，但多轮稳定性 gate 仍然通过
5. 如果 `AuditGate.Passed = false`，优先看 `AuditGate.Checks`，再看 `CaseFailureSummary`
