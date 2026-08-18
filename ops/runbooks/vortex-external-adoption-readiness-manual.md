# Vortex 外部使用与发布准备手册

## 1. 目的

本文档把当前影响第三方采用的八类问题转化为可执行的修复与验收手册。适用对象包括：

- 准备对外发布 Vortex 的维护者。
- 需要在本机或可信内网试用 Vortex 的集成方。
- 负责安全、发布、运维和许可证审查的评审者。

本文档不把当前项目描述为生产就绪。完成单项修复也不代表整体可以进入生产；只有
“发布门禁”中的全部必选项通过，才能调整 README 中的使用边界。

## 2. 当前使用边界

| 场景 | 当前状态 | 前提 |
| --- | --- | --- |
| 源码阅读、作品集审阅 | 可用 | 使用明确的 commit 或 tag |
| 本机 Quickstart | 有条件可用 | 可信主机、端口可用、接受开发凭据 |
| 可信隔离网络内 REST 试验 | 有条件可用 | 自行增加访问控制并收紧端口 |
| Maven/Gradle 依赖接入 | 不可直接使用 | 尚未发布 artifacts |
| 公网、多租户、生产部署 | 不支持 | 安全、持久化和运维门禁未完成 |

## 3. 严重度与处理顺序

| 优先级 | 问题 | 发布影响 |
| --- | --- | --- |
| P0 | 无认证且默认公开端口 | 阻止任何公网或共享网络部署 |
| P0 | 当前代码、文档和证据不一致 | 阻止形成可复现发布版本 |
| P0 | `mvn verify` 会操作现有 Compose 环境 | 阻止安全的贡献者验证流程 |
| P0 | 固定宿主机端口导致 Quickstart 不可移植 | 阻止可靠的一键试用 |
| P1 | Execution ID 存在持久化和失败窗口 | 阻止强幂等或关键副作用场景 |
| P1 | API 输入边界不足且异常泄露内部信息 | 阻止不可信调用方接入 |
| P1 | 没有可直接消费的发布产物 | 阻止标准 Java/Docker 采用流程 |
| P1 | 许可证和部署材料不完整 | 阻止合规再分发和可维护部署 |

---

## 4. 问题一：无认证且默认公开端口

### 4.1 当前风险

默认 Compose 配置会发布 Vortex、Redis、Milvus、MinIO 和管理端口，其中 MinIO
使用开发凭据。应用同时暴露 Swagger、Actuator health、metrics 和 Prometheus，REST
接口没有统一认证与授权边界。

在防火墙允许访问时，网络中的其他主体可能：

- 写入、读取或删除 memory fragment。
- 创建、恢复或删除任务状态。
- 访问 MinIO 管理界面或 Redis 数据。
- 获取健康详情、指标和内部容量信息。

关键位置：

- `docker-compose.quickstart.yml`
- `docker-compose.yml`
- `vortex-app/src/main/resources/application.yml`
- `vortex-app/src/main/java/com/vortex/app/controller/MemoryController.java`
- `vortex-app/src/main/java/com/vortex/app/controller/TaskController.java`

### 4.2 试用期临时规则

- 只在可信本机或隔离的开发网络运行。
- 不要把默认端口发布到公网 IP。
- 不要复用 Quickstart 凭据承载真实数据。
- 使用宿主机防火墙限制 `8080`、`6379`、`9000`、`9001`、`19530` 和 `9091`。

临时规则不是生产修复，不能作为生产安全声明的依据。

### 4.3 必须完成的修复

1. 为 REST API 定义认证机制，例如反向代理 OIDC、JWT 或服务间 mTLS。
2. 为 namespace 建立授权规则，不能仅依赖请求体中的 namespace 字符串。
3. 默认只把 Vortex API 绑定到需要的接口；Redis、Milvus 和 MinIO 优先只保留
   Compose 内部网络访问。
4. 将 Swagger 和详细 Actuator 端点限制在管理网络或认证角色内。
5. 删除默认生产凭据，为 MinIO、Redis 和其他后端提供 secret 注入方式。
6. 为写入、召回、恢复和删除接口增加限流、配额和审计事件。

### 4.4 验收标准

- 未认证请求不能访问业务 API、Swagger 或详细管理端点。
- 用户 A 不能访问用户 B 的 namespace，即使主动构造 namespace 参数。
- 默认生产 Compose 不向宿主机发布 Redis、Milvus 和 MinIO 数据端口。
- 仓库和镜像中不存在可用的生产默认密码。
- 安全测试覆盖读取、写入、删除、恢复和管理端点。

### 4.5 验证命令

```powershell
docker compose -f docker-compose.quickstart.yml config
docker compose -f docker-compose.quickstart.yml ps
Get-NetTCPConnection -LocalPort 8080,6379,9000,9001,19530,9091 -ErrorAction SilentlyContinue
```

验证记录必须列出每个发布端口、访问主体、认证方式和预期响应码。

---

## 5. 问题二：当前代码、文档和发布证据不一致

### 5.1 当前风险

`v0.1.1` 的测试数、覆盖率和 benchmark 对应特定标签。当前代码可能已经修改默认
Recall 策略、并发准入、淘汰和评测路径。如果把旧证据直接描述为当前代码结论，第三方
无法复现，功能默认值也可能与 README 和 OpenAPI 不一致。

典型例子：当前 `RecallQuery` 默认使用 `HYBRID + RRF`，而 `v0.1.1` 的公开召回证据
主要以 `VectorOnly` 为默认或晋级基线。

### 5.2 发布前规则

- 所有公开数字必须绑定 commit SHA 或 tag。
- 工作区存在未提交核心代码时，不得创建发布标签。
- 默认行为发生变化时，必须同步更新 README、OpenAPI、示例、release notes 和测试。
- 旧 benchmark 可以保留，但必须明确其代码版本和适用边界。

### 5.3 必须完成的修复

1. 把当前功能改动拆分成可审查提交。
2. 明确最终默认 Recall 策略，并冻结请求契约。
3. 从干净 clone 或干净 worktree 运行完整验证。
4. 为新默认值重新生成质量、延迟和回归证据。
5. 在 release notes 中记录兼容性变化和回退方式。
6. 确保 README 中的测试数来自该发布 commit，而不是本地累计输出。

### 5.4 验收标准

- `git status --short` 在发布验证开始和结束时均为空。
- `git describe --tags --always` 与证据文件记录一致。
- 默认 DTO、README、OpenAPI 和示例请求一致。
- 所有 benchmark 报告包含 commit、配置、模型 hash、数据集版本和复现命令。
- 发布标签指向已经通过 CI 的 commit。

### 5.5 验证命令

```powershell
git status --short
git rev-parse HEAD
git describe --tags --always
git diff --check
mvn -B clean verify
```

---

## 6. 问题三：`mvn verify` 会操作现有 Compose 环境

### 6.1 当前风险

`vortex-app/pom.xml` 在 `pre-integration-test` 中执行根目录 `docker compose up`，并在
`post-integration-test` 中执行 `docker compose down --remove-orphans`。它没有为测试
设置独立的 Compose project name。

因此，开发者在已有 Vortex 容器运行时执行 `mvn verify`，可能停止并删除现有容器。
命名卷通常仍保留，但运行状态会被破坏，孤儿容器也可能被删除。

### 6.2 未修复前的操作规则

- 在执行 `mvn verify` 前先运行 `docker compose ps`，记录现有状态。
- 不要在承载开发数据或演示环境的 Compose project 上执行完整 verify。
- 不要把 `-Dvortex.it.skipComposeDown=true` 当作完整隔离方案；Compose up 仍可能复用
  相同 project 和端口。

### 6.3 必须完成的修复

推荐顺序：

1. 为集成测试生成唯一的 `COMPOSE_PROJECT_NAME`。
2. 使用独立 Compose 文件、动态端口和独立命名卷。
3. 把测试生命周期封装到脚本中，并使用 `try/finally` 只清理本次创建的资源。
4. Maven 默认只运行单元测试；Docker 集成测试使用显式 profile，例如 `-Pit`。
5. 清理前校验容器 label，禁止对不属于当前测试 run ID 的容器执行 down/remove。

### 6.4 验收标准

- 启动一套开发 Compose 后运行集成测试，开发容器 ID 和状态保持不变。
- 两次并行集成测试可以使用不同 project name 和端口运行。
- 测试失败、超时或被中断后，只清理本次测试资源。
- CI 与本地命令使用同一个受支持入口。

### 6.5 建议命令接口

```powershell
./ops/run-integration-tests.ps1 -ProjectName "vortex-it-<run-id>"
```

该脚本尚未实现；实现后应替换 README 和 CONTRIBUTING 中直接调用 Compose 生命周期的
命令。

---

## 7. 问题四：固定宿主机端口导致 Quickstart 不可移植

### 7.1 当前风险

Compose 固定使用 `8080`、`6379`、`9000`、`9001`、`19530` 和 `9091`。端口可能被
其他程序占用，也可能被 Windows Docker Desktop、Hyper-V 或系统动态端口范围保留。

特别是 `19530` 无法绑定时，Milvus 启动失败，Vortex 主链路和 Docker 集成测试会级联
失败。

### 7.2 启动前检查

```powershell
Get-NetTCPConnection -LocalPort 8080,6379,9000,9001,19530,9091 -ErrorAction SilentlyContinue
netsh interface ipv4 show excludedportrange protocol=tcp
```

Linux/macOS：

```bash
ss -ltn
```

### 7.3 必须完成的修复

1. Quickstart 中不向宿主机发布不需要直接访问的 Redis 和 Milvus 端口。
2. 对需要发布的端口使用环境变量，例如 `${VORTEX_HTTP_PORT:-8080}:8080`。
3. 集成测试使用动态宿主机端口，并把实际端口注入 Spring 配置。
4. Quickstart 脚本在构建前执行端口预检并给出明确错误。
5. 文档说明修改宿主机端口不会改变 Compose 内部服务端口。

### 7.4 验收标准

- 宿主机 `19530` 被占用或保留时，容器化 Quickstart 仍可运行。
- 两套 Quickstart 可以通过不同宿主机端口并存。
- 端口冲突在构建镜像前被检测并报告。
- Windows、Linux 和 macOS 的启动命令都进入 CI 或定期验证矩阵。

---

## 8. 问题五：Execution ID 存在持久化和失败窗口

### 8.1 当前风险

默认 Execution ID backend 为进程内存，应用重启后记录丢失。Quickstart 显式使用
Redis，但仍存在以下失败窗口：

1. 成功占位 Execution ID。
2. 业务副作用已经完成。
3. 把 COMPLETED 响应写回 Redis 时失败。
4. 当前实现删除 reservation 并向调用方返回错误。
5. 调用方重试后，业务副作用可能再次执行。

因此当前能力是请求级尽力幂等，不应描述为跨故障边界的 exactly-once。

### 8.2 使用规则

- 有副作用的外部 Tool 必须自己支持幂等 key 或状态查询。
- 生产样式试验必须使用 Redis backend，不能使用默认 MEMORY backend。
- Execution ID TTL 必须覆盖调用方可能发生重试的最长时间。
- 不要把 HTTP 5xx 简单解释为业务操作一定未发生。

### 8.3 必须完成的修复

可选设计包括：

- 把业务状态和 Execution ID completion 放入同一事务存储。
- 使用 outbox/inbox 状态机，保留 UNKNOWN/COMMIT_PENDING 状态而不是删除 reservation。
- 业务动作完成但响应持久化失败时，禁止直接重新执行；先执行结果恢复或人工仲裁。
- 为 Redis Lua/CAS 更新增加状态前置条件，避免覆盖冲突记录。

### 8.4 验收标准

- 注入 `store.complete()` 失败后，相同 Execution ID 不会再次执行副作用。
- 应用重启后可以重放已完成响应或明确返回可恢复状态。
- 同一 Execution ID 的不同 payload 始终返回冲突。
- 并发相同请求只允许一个执行者进入业务动作。
- 文档不使用“分布式 exactly-once”表述。

### 8.5 必须增加的测试

- Redis completion 写失败。
- Redis reserve 成功后连接中断。
- 业务成功、响应序列化失败。
- 应用在 IN_PROGRESS 和 COMPLETED 之间重启。
- TTL 到期与长时间业务执行竞争。

---

## 9. 问题六：API 输入边界不足且异常泄露内部信息

### 9.1 当前风险

以下接口或 DTO 缺少完整边界：

- `/api/v1/memory/store/fragment` 接受客户端提供的完整 `MemoryFragment`。
- Recall `topK` 只有最小值，没有最大值。
- Recall query、token budget、tags 和部分 pin/unpin 请求缺少严格上限。
- 分页 size 没有统一最大值。
- 全局异常处理会把 `Exception.getMessage()` 返回给客户端。

攻击者或错误调用方可能造成大对象分配、昂贵检索、非法 embedding、状态污染，或者获取
内部路径、后端连接错误和实现细节。

### 9.2 必须完成的修复

1. 不直接把内部领域模型作为公开写入 DTO。
2. 服务端生成 fragment ID、embedding、tokenCount、createdAt 和内部评分字段。
3. 为所有字符串、集合、分页、topK、token budget 和 TTL 设置上下限。
4. 校验 tags 数量及单个 tag 长度。
5. 对枚举和 ranking 参数提供稳定的 400 ProblemDetail。
6. 5xx 响应只返回稳定错误码和 correlation ID，详细异常只进入受控日志。
7. 对请求体大小设置 Web Server 和反向代理双重限制。

### 9.3 建议初始限制

这些值必须结合性能测试冻结，不应直接视为最终生产配置：

| 字段 | 建议初始上限 |
| --- | --- |
| memory content | 20,000 chars |
| recall query | 4,000 chars |
| namespace | 128 chars |
| tags | 32 items |
| single tag | 128 chars |
| topK | 100 |
| token budget | 32,768 |
| page size | 200 |
| request body | 1 MiB |

### 9.4 验收标准

- 超限请求稳定返回 400 或 413，不进入 embedding/Milvus 路径。
- 客户端不能提交或覆盖内部 embedding 和系统时间字段。
- 5xx 响应不包含文件路径、主机名、堆栈、SQL/Redis/MinIO/Milvus 原始错误。
- API 边界测试覆盖空值、极值、超大集合和非法枚举。

---

## 10. 问题七：没有可直接消费的发布产物

### 10.1 当前风险

当前 Release workflow 只创建 GitHub Release 文本，不上传：

- Maven artifacts。
- 可执行应用 JAR。
- Docker/OCI image。
- source/javadoc JAR。
- SBOM、签名或 checksum。

第三方必须克隆整仓并从源码构建；单独声明 `com.vortex:*:0.1.1` 会发生依赖解析失败。

### 10.2 发布模型决策

维护者必须明确至少一种正式交付方式：

1. REST 服务优先：发布 `vortex-app` OCI image 和 Compose 示例。
2. Java SDK 优先：发布稳定 DTO/client 模块到 Maven Central 或 GitHub Packages。
3. 内核嵌入：发布 `vortex-common`、`vortex-kernel`、`vortex-storage`，并定义兼容策略。

不建议在 API 未稳定时一次性承诺所有内部模块的公共兼容性。

### 10.3 必须完成的修复

- 为公开 artifacts 增加 name、description、URL、SCM、developer 和 license 元数据。
- 配置 source/javadoc、签名和 deploy。
- 发布固定 tag 的 OCI image，禁止只使用可漂移的 `latest`。
- 生成 CycloneDX 或 SPDX SBOM。
- 生成 SHA-256 checksum，并记录基础镜像 digest。
- Release workflow 上传产物并执行安装/拉取 smoke test。

### 10.4 验收标准

- 全新环境可以从远程仓库解析公开 Java artifact。
- `docker pull` 后无需本地源码即可运行应用。
- Git tag、POM version、image tag、OpenAPI version 和 release notes 一致。
- 发布产物包含 checksum、SBOM 和许可证材料。
- 发布流程在已有相同版本时失败，禁止静默覆盖不可变版本。

---

## 11. 问题八：许可证和部署材料不完整

### 11.1 当前风险

- OpenAPI 当前声明 MIT，而仓库代码和文档使用 Apache-2.0。
- 仓库包含第三方 ONNX model 和 tokenizer，但没有完整的模型来源、许可证副本和
  attribution 清单。
- 根目录没有 `.env.example`，而应用包含大量环境变量。
- Milvus、MinIO、Redis、WAL 和 checkpoint 的备份、恢复、迁移与升级流程不完整。
- Docker image 没有明确的非 root 用户、只读文件系统策略和持久卷契约。

### 11.2 必须完成的许可证工作

1. 把 OpenAPI license 修正为 Apache-2.0，并提供许可证 URL。
2. 创建 `THIRD_PARTY_NOTICES.md` 或 `licenses/` 目录。
3. 对每个模型记录：
   - 上游项目和下载 URL。
   - 精确 revision/commit。
   - 文件 SHA-256。
   - 上游许可证和 attribution 要求。
   - 是否允许仓库再分发和商业使用。
4. 对 benchmark 数据集执行同样的来源与许可证记录。
5. 在 release checklist 中加入许可证扫描和人工复核。

### 11.3 必须完成的部署文档

- 提供 `.env.example`，按“必填、可选、危险、仅评测”分类配置。
- 明确哪些目录必须挂载持久卷，包括 WAL、DLQ 和 processed keys。
- 提供 Milvus/MinIO/Redis 的备份和恢复步骤。
- 提供 embedding dimension、collection schema 和 checkpoint format 的迁移流程。
- 说明回滚兼容范围和不可逆操作。
- 使用非 root 用户运行应用镜像，并记录所需文件权限。

### 11.4 验收标准

- OpenAPI、POM、README 和发布产物使用一致的 Apache-2.0 声明。
- 每个随仓库分发的模型都有可审计的 provenance 和 license 记录。
- 新用户只参考 `.env.example` 和部署文档即可启动非默认凭据环境。
- 执行一次备份、删除测试数据、恢复并验证 recall/checkpoint 的演练。
- 容器替换后 WAL、DLQ、checkpoint 和 Execution ID 的预期状态与文档一致。

---

## 12. 推荐实施顺序

### 阶段 A：形成可复现版本

1. 清理并提交当前工作区。
2. 冻结 Recall 默认契约。
3. 隔离 Maven 集成测试 Compose 生命周期。
4. 修复 Quickstart 固定端口问题。
5. 从干净 commit 重新执行完整验证。

### 阶段 B：建立可信试用边界

1. 收紧 Compose 端口和开发凭据。
2. 增加 API 输入限制和异常脱敏。
3. 默认使用持久化 Execution ID backend。
4. 挂载 WAL、DLQ 和 processed-key 持久卷。

### 阶段 C：准备正式分发

1. 决定 REST image 或 Java SDK 的首要交付方式。
2. 发布 artifacts、image、SBOM、checksum 和签名。
3. 补齐第三方许可证、`.env.example`、备份恢复和迁移手册。
4. 完成安全测试和发布 smoke test。

## 13. 发布门禁清单

以下项目全部通过后，才可以把 README 中的状态提升为“可供第三方部署”：

- [ ] 发布 commit 工作区干净，tag 与证据一致。
- [ ] 完整单元测试和 Docker 集成测试通过。
- [ ] Quickstart 在 Windows、Linux 和 macOS 验证通过。
- [ ] 集成测试不会修改已有开发 Compose 环境。
- [ ] 默认配置不公开未认证的存储和管理端口。
- [ ] API 认证、namespace 授权、限流和审计边界已验证。
- [ ] 所有公共 DTO 具备输入上限，5xx 响应已脱敏。
- [ ] Execution ID 故障窗口有明确状态机和测试。
- [ ] WAL、DLQ、processed keys 和 checkpoint 持久化契约已验证。
- [ ] Maven artifact 或 OCI image 可以从全新环境直接消费。
- [ ] SBOM、checksum、签名和第三方许可证材料完整。
- [ ] 备份、恢复、迁移和回滚演练通过。
- [ ] README、OpenAPI、release notes 和实际默认行为一致。

## 14. 验证记录模板

每次候选发布应创建独立记录，不要直接覆盖本手册：

```text
Release candidate:
Commit SHA:
Tag:
Date:
Operator:
OS / Docker / Java / Maven:

Unit test result:
Integration test result:
Quickstart result:
Security test result:
Backup/restore result:
Artifact install/pull result:
License review result:

Known exceptions:
Evidence paths:
Final decision: GO / NO-GO
```

候选发布存在任何 P0 未关闭项时，最终决定必须为 `NO-GO`。
