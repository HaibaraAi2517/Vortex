# Vortex Open Source Readiness Handoff - 2026-07-26

本文档是本轮“让 Vortex 更适合获得 GitHub stars”的开发交接记录。新对话继续开发时，建议先阅读本文档，再阅读根目录的 `执行计划.md`。

## 本轮目标

仓库主人给出的目标是：让 Vortex 从“工程资产”变成“路人 30 秒看懂、5 分钟跑起来”的传播型开源项目，同时严格保持真实性，不编造 benchmark、测试覆盖率或生产能力。

执行优先级来自 `执行计划.md`：

- Phase 1：降低理解门槛，重写 README 首屏、补架构图、补 benchmark 摘要页。
- Phase 2：降低试用门槛，补一键 quickstart、补无 API key 的 killer demo。

硬约束已按以下原则执行：

- README/docs 里的指标只引用仓库内已有 evidence/runbook。
- quickstart 和 demo 命令经过本机实际验证后才写入文档。
- 未验证的宣传口径没有写成正向 claim。
- 没有写诱导 star、刷 star 或虚假营销内容。

## 已完成的文件变更

本轮明确相关的文件如下：

- `README.md`
  - 改为英文主 README。
  - 首屏加入语言切换、CI/license/Java/Spring Boot/Milvus badges。
  - 加入一句话定位、痛点到 runtime primitive 的表格、Mermaid overview、Quick Start / Agent Demo / Architecture / Benchmarks 链接。
  - 加入 benchmark evidence 摘要、架构、quickstart、zero-key agent demo、Memory API、Task API、build/test、eval CLI、配置、项目状态、仓库结构、技术栈和 license。

- `README_zh.md`
  - 新增中文 README，对齐英文主 README 的内容结构。
  - 保留中文读者友好的表达，但不额外夸大能力。

- `docs/architecture.md`
  - 新增架构说明。
  - 包含 3 张 GitHub 原生 Mermaid 图：hybrid retrieval pipeline、three-tier memory storage、runtime recovery flow。

- `docs/benchmark.md`
  - 新增 benchmark evidence 摘要页。
  - 只使用当前仓库内有 evidence 的数据。
  - 明确列出不能 claim 的内容，防止 README/发布文案越界。

- `docs/quickstart.md`
  - 新增容器优先 quickstart。
  - 记录 2026-07-26 已验证的 clean one-command startup、health、memory store/recall、task checkpoint/recover。
  - 明确说明本轮 quickstart 验证没有重跑 Maven 全量测试。

- `.dockerignore`
  - 新增 Docker build context 排除项。
  - 未排除 `models/`，因为 quickstart image 需要复制本地 BGE model。

- `Dockerfile`
  - 新增多阶段构建。
  - build stage 使用 `maven:3.9-eclipse-temurin-21`。
  - runtime stage 使用 `eclipse-temurin:21-jre`。
  - 将 `vortex-app-0.1.0-exec.jar` 和 `models/` 复制进 image。

- `docker-compose.quickstart.yml`
  - 新增 quickstart stack：Vortex app + Milvus + MinIO + Redis + etcd。
  - Vortex 使用 `BGE_MODEL_PATH=/app/models/bge-small-zh`。
  - 默认关闭外部 generation，不需要外部 LLM API key。

- `examples/quickstart-agent/README.md`
  - 新增 killer demo 说明。
  - 说明 memory off/on 对比和 crash recovery 对比。

- `examples/quickstart-agent/run.ps1`
  - 新增 Windows PowerShell demo。
  - 真实调用 Vortex HTTP API。
  - 启动 worker 子进程，worker checkpoint 后被强杀，再由主进程调用 recovery API 恢复。

- `examples/quickstart-agent/run.sh`
  - 新增 Bash demo。
  - 与 PowerShell demo 语义一致。
  - 在本机使用 Git Bash 验证通过。

## 当前已验证状态

验证日期：2026-07-26。

当前 quickstart stack 仍在运行：

```text
vortex-etcd-1     Up, healthy
vortex-milvus-1   Up, healthy, ports 19530 and 9091
vortex-minio-1    Up, healthy, ports 9000 and 9001
vortex-redis-1    Up, healthy, port 6379
vortex-vortex-1   Up, port 8080
```

当前 health：

```text
GET http://localhost:8080/actuator/health -> UP
```

已通过的验证命令或检查：

```powershell
docker compose -f docker-compose.quickstart.yml config --quiet
docker compose -f docker-compose.quickstart.yml up --build -d
Invoke-RestMethod -Uri 'http://localhost:8080/actuator/health' -Method Get
```

HTTP API 验证结果：

- `POST /api/v1/memory/store`：写入 `1` 个 fragment。
- `POST /api/v1/memory/recall`：召回 `1` 个 fragment。
- `POST /api/v1/tasks`：创建 task 成功。
- `POST /api/v1/tasks/{taskId}/nodes`：追加 node 成功。
- `POST /api/v1/tasks/{taskId}/checkpoint`：创建 checkpoint 成功。
- `POST /api/v1/tasks/{taskId}/recover`：恢复成功，`nodeCount=1`。

PowerShell killer demo 已验证：

```powershell
.\examples\quickstart-agent\run.ps1
```

关键输出：

```text
Stored fragments: 1
NO MEMORY: I only see the current question, so I do not know the codename or launch goal.
WITH VORTEX: recalled durable memory:
- Demo session facts: project codename is Aurora Ledger; launch goal is a star-ready GitHub README; preferred stack is Java 21 with Milvus and MinIO.
WITH VORTEX: recovered task ... from checkpoint ...; nodeCount=1.
No external LLM API key was used.
```

Bash killer demo 已验证：

```powershell
& 'E:\git\bin\bash.exe' -lc 'cd /e/1projects/claude/Vortex && bash examples/quickstart-agent/run.sh'
```

说明：

- Windows 默认 `bash` 可能先命中 `C:\Windows\System32\bash.exe`，也就是 WSL launcher。
- 本机 WSL 没有安装发行版，因此直接运行 `bash examples/quickstart-agent/run.sh` 会失败。
- Git Bash 路径 `E:\git\bin\bash.exe` 已验证通过。
- README 主要推荐 PowerShell 入口；Bash 入口适合 Linux/macOS/Git Bash。

文档和格式检查：

```powershell
rg -n "[ \t]+$" README.md README_zh.md docs examples/quickstart-agent .dockerignore Dockerfile docker-compose.quickstart.yml
```

结果：没有行尾空白。

本地 Markdown 链接检查：通过。

Bash 语法检查：

```powershell
& 'E:\git\bin\bash.exe' -n examples/quickstart-agent/run.sh
```

结果：通过。

敏感 claim 扫描：

```powershell
rg -n "499\+|73%|99\.99|LongMemEval|longmemeval|LongMem" README.md README_zh.md docs examples/quickstart-agent
```

结果只命中 `docs/benchmark.md` 中的边界说明：

- `LongMemEval` 出现在“待 public dataset promotion”说明中。
- `99.99%` 出现在 “Do Not Claim” 中。

## 没有完成或没有验证的事项

本轮没有运行 Maven 全量测试：

```powershell
mvn -B test -pl vortex-common,vortex-kernel,vortex-storage -am
mvn -B verify -pl vortex-app -am
```

本轮没有创建 git commit。

本轮没有停止 quickstart stack。若要停止但保留 volume：

```powershell
docker compose -f docker-compose.quickstart.yml down
```

若要删除 quickstart volumes：

```powershell
docker compose -f docker-compose.quickstart.yml down -v
```

不要默认执行 `down -v`，除非明确需要清理数据。

## 当前 git 工作区注意事项

当前工作区有许多既有脏文件，其中很多不是本轮修改产生的。继续开发时不要误删或回滚。

本轮相关文件：

```text
M  README.md
?? .dockerignore
?? Dockerfile
?? README_zh.md
?? docker-compose.quickstart.yml
?? docs/architecture.md
?? docs/benchmark.md
?? docs/quickstart.md
?? examples/quickstart-agent/
?? ops/runbooks/vortex-open-source-readiness-handoff-20260726.md
```

已有但本轮不应随意处理的脏文件包括：

```text
M  .gitignore
M  ops/run-real-llm-memory-eval.ps1
M  pom.xml
M  vortex-app/pom.xml
M  vortex-app/src/main/java/com/vortex/app/eval/*
M  vortex-app/src/test/java/com/vortex/app/eval/LlmMemoryEvalReportWriterTest.java
M  vortex-kernel/src/main/java/com/vortex/kernel/hmc/HybridRecallReranker.java
M  vortex-kernel/src/main/java/com/vortex/kernel/hmc/RecallOrchestrator.java
?? ops/*
?? readme-history/
?? vortex-kernel/src/test/java/com/vortex/kernel/hmc/HybridRecallRerankerTest.java
```

建议提交时只 stage 本轮相关文件，避免混入不相关改动。可用显式文件列表：

```powershell
git add README.md README_zh.md .dockerignore Dockerfile docker-compose.quickstart.yml docs/architecture.md docs/benchmark.md docs/quickstart.md examples/quickstart-agent/README.md examples/quickstart-agent/run.ps1 examples/quickstart-agent/run.sh ops/runbooks/vortex-open-source-readiness-handoff-20260726.md
```

建议 commit message：

```text
docs: improve open source quickstart and agent demo
```

## 当前可安全公开的 benchmark 口径

README 和 benchmark docs 当前只使用以下已提交 evidence 支撑的指标：

- Hybrid recall：`Hybrid+Rerank` 相比 `Vector+Rerank` 将 Recall@5 从 `0.7917` 提升到 `0.9500`，relative lift `+20.00%`，五种 retrieval mode 共 `0/100` run errors。
- Main-path latency：P99 从 `1172.50 ms` 降到 `220.34 ms`，average latency 从 `829.40 ms` 降到 `186.64 ms`。
- Runtime recovery：deterministic fault-injection matrix 通过 `32/32` covered cases，覆盖 service restart、tool failure、LLM exception、state integrity、concurrency 五类场景。

这些数字来自：

- `ops/runbooks/vortex-recall-ablation-benchmark-evidence-20260630.md`
- `ops/runbooks/vortex-main-path-latency-benchmark-evidence-20260629.md`
- `ops/runbooks/vortex-runtime-recovery-benchmark-evidence-20260627.md`

不能公开 claim：

- 不要 claim `99.99%` main-path latency reduction。
- 不要 claim `20.00%` LLM answer accuracy improvement。
- 不要 claim online production recall improvement。
- 不要 claim complete production recovery coverage。
- 不要 claim LongMemEval/public dataset 结果，除非 dataset conversion、report artifacts、model/base URL disclosure 和边界说明都已提交并复核。
- 不要在 README 中引用 `499+ tests` 或 `73% coverage`，除非最新报告可复现且已提交。

## 对“是否具备获得 stars 能力”的当前判断

当前项目已经具备获得 stars 的基础展示能力：

- 路人可以在 README 首屏理解项目定位。
- 有清晰的 pain -> runtime primitive 映射。
- 有可运行 quickstart。
- 有无 API key 的 agent demo，可以看到 memory on/off 和 crash recovery 的可见差异。
- 有 benchmark evidence 和边界说明，可信度比纯营销文案高。

但要真正更容易获得 stars，还缺少几个传播资产：

- demo GIF 或短视频。建议录制 `examples/quickstart-agent/run.ps1` 输出，放到 README 的 Agent Demo 区域。
- GitHub repo description 和 topics。
- `v0.1.0-alpha` release notes。
- `CONTRIBUTING.md`、`ROADMAP.md`、`CODE_OF_CONDUCT.md`、issue/PR templates。
- Vortex vs plain vector RAG / hand-rolled memory 的客观 comparison page。
- Spring AI 或 LangChain4j/MCP integration example。

## 下一步建议

优先级从高到低：

1. 打开 GitHub 网页端检查 README Mermaid 是否正常渲染，尤其是首屏体验。
2. 录制 `examples/quickstart-agent/run.ps1` 的 GIF 或短视频，并在 README 中加入占位或实际媒体。
3. 运行 Maven test/verify，确认这轮 Docker/docs/example 变更没有影响构建。
4. 创建 Phase 1 + Phase 2 的独立 commit 或 PR。
5. 执行 Phase 3：repo description/topics、release notes、社区文件。
6. 执行 comparison page，帮助路人理解 Vortex 与普通 RAG 或手写 memory 的差别。
7. 执行 Spring AI 集成示例，吸引 Java/Spring 生态用户。

## 新对话启动建议

新对话可以直接这样开始：

```text
请先阅读 E:\1projects\claude\Vortex\ops\runbooks\vortex-open-source-readiness-handoff-20260726.md 和 E:\1projects\claude\Vortex\执行计划.md，然后继续执行下一阶段。不要回滚已有脏文件，只处理本轮相关改动。
```

如果新对话要先提交本轮结果，建议要求：

```text
请检查本轮 open-source readiness 相关文件，运行必要验证，然后只 stage 这些文件并创建 Conventional Commit。
```


## Phase 3 续作记录（2026-07-26）

本轮继续执行 `执行计划.md` 的 Phase 3，仍遵守“不编造 benchmark、不夸大生产能力、不回滚既有脏文件”的约束。

新增或更新文件：

- `docs/repo-settings.md`
  - 写入 GitHub repo description、topics 和公开定位 guardrails。
  - 当前环境未检测到 `gh` CLI，因此没有尝试远程设置 GitHub metadata。
- `docs/releases/v0.1.0-alpha.md`
  - 新增首个 alpha release notes 草稿。
  - 包含能力清单、quickstart、benchmark 摘要、known limits 和发布前 maintainer checklist。
- `docs/comparison.md`
  - 新增 Vortex vs plain vector RAG vs hand-rolled memory 的客观定位表。
  - 明确说明 Vortex 适合/不适合的场景，以及调用方仍需负责的集成边界。
- `CONTRIBUTING.md`
  - 新增贡献指南、开发命令、benchmark/claim policy 和 PR 前检查项。
- `ROADMAP.md`
  - 新增 problem-first roadmap，覆盖试用门槛、retrieval evaluation、runtime recovery、deployment hardening、contributor experience。
- `CODE_OF_CONDUCT.md`
  - 新增简洁 code of conduct，并把伪造 benchmark/test/production evidence 作为明确不可接受行为。
- `.github/pull_request_template.md`
  - 新增 PR 模板，要求列明 validation、命令验证和 benchmark evidence。
- `.github/ISSUE_TEMPLATE/bug_report.yml`
- `.github/ISSUE_TEMPLATE/feature_request.yml`
- `.github/ISSUE_TEMPLATE/docs.yml`
- `.github/ISSUE_TEMPLATE/config.yml`
  - 新增 bug、feature、docs issue 模板和 quickstart/benchmark evidence contact links。
- `README.md`
  - 导航新增 `Comparison`。
  - `Project Status` 区域新增 comparison 和 alpha release notes 链接。
  - 新增 `Community` 区域链接贡献指南、roadmap、code of conduct 和 repo settings。
- `README_zh.md`
  - 对齐英文 README 的 Phase 3 入口。

已运行验证：

```powershell
rg -n "[ \t]+$" README.md README_zh.md docs CONTRIBUTING.md ROADMAP.md CODE_OF_CONDUCT.md .github
rg -n "499\+|73%|99\.99|20\.00% LLM|online production recall|complete production recovery coverage|LongMemEval|longmemeval" README.md README_zh.md docs CONTRIBUTING.md ROADMAP.md CODE_OF_CONDUCT.md .github
```

结果：

- 行尾空白检查无输出。
- 敏感 claim 扫描只命中 `docs/benchmark.md` 和 `docs/releases/v0.1.0-alpha.md` 中的明确边界/不推广说明。

本地 Markdown 链接检查通过：

```text
All local markdown links resolved.
```

本轮仍未运行 Maven 全量测试。Phase 3 是 docs/community/template 变更；若提交前需要总验证，建议运行：

```powershell
mvn -B test -pl vortex-common,vortex-kernel,vortex-storage -am
mvn -B verify -pl vortex-app -am
```

## Phase 4 续作记录（2026-07-27）

本轮继续执行 Phase 4.1，并补齐 demo media：

- 已推送 `docs: improve open source readiness` 到 GitHub main。
- GitHub SSH push 需要显式使用 Windows OpenSSH：

```powershell
git -c core.sshCommand="C:/Windows/System32/OpenSSH/ssh.exe" push origin main
```

- 当前环境没有 `gh`、`GH_TOKEN` 或 `GITHUB_TOKEN`，所以 repo description/topics 仍需仓库主人在 GitHub 网页端手动设置。
- 当前环境 HTTP 拉取 GitHub 网页会失败，README Mermaid/GIF 网页渲染仍需仓库主人浏览器目检。
- 新增真实 demo media：
  - `docs/assets/quickstart-agent-demo.gif`
  - `docs/assets/quickstart-agent-demo.txt`
- 录制素材来自本机真实执行：

```powershell
.\examples\quickstart-agent\run.ps1
```

关键输出包含：`Stored fragments: 1`、memory off/on 对比、checkpoint recovery、`No external LLM API key was used.`。

- 生成 GIF 期间发现 quickstart restart bug：旧 `system/active-task-index.bin` 存在时，fat jar 反序列化 private nested `TaskListingSnapshot` 会触发 ReflectASM `IllegalAccessError`。
- 已修复：`TaskLifecycleManager.TaskListingEntry` 和 `TaskLifecycleManager.TaskListingSnapshot` 改为 `public static`，逻辑不变。
- 已新增 Spring AI integration example：`examples/spring-ai-integration/`。
  - 使用 Spring AI `spring-ai-client-chat:2.0.0`。
  - `VortexMemoryAdvisor` 实现 `BaseAdvisor`，在 `before` 中调用 Vortex recall 并注入 system prompt。
  - demo 使用 fake advisor chain，不调用外部 LLM provider，不需要 API key。

新增验证：

```powershell
mvn -B test -pl vortex-kernel -am -Dtest=TaskLifecycleManagerTest "-Dsurefire.failIfNoSpecifiedTests=false"
mvn -q -f examples/spring-ai-integration/pom.xml package
mvn -q -f examples/spring-ai-integration/pom.xml exec:java
mvn -B test -pl vortex-common,vortex-kernel,vortex-storage -am
mvn -B verify -pl vortex-app -am
```

结果：

- `TaskLifecycleManagerTest` 通过 `36` 个测试。
- Spring AI example 编译通过，demo 输出 `Advisor recall count: 1`，并注入包含 `Aurora Ledger` 和 `Spring AI ChatClient advisor` 的 Vortex memory。
- common/storage/kernel 全量测试通过：`302` tests，`BUILD SUCCESS`。
- `vortex-app verify` 通过：app integration verification `13` tests，`BUILD SUCCESS`。
- `mvn verify` 按 lifecycle 停止了 Docker Compose 项目；当前 quickstart stack 不再运行。
