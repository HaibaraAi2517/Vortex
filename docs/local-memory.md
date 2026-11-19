# 本地 Agent 记忆服务

Vortex 在本机保存个人事实和版本，供工作台和 Agent 在不同会话中共用。Windows x64 包含 Java 21、应用和本地中文检索模型。启动工作台无需 Python、Docker 或模型服务密钥。

## 下载并启动

1. 在 GitHub Releases 选择标签以 `local-` 开头的预览版。Windows 用户在 Assets 下载 `vortex-local-windows-x64.zip`。Linux 用户下载 `vortex-local-alpha.zip`，使用已安装的 Java 21 运行 `bash start.sh`。[Releases](https://github.com/HaibaraAi2517/Vortex/releases)
2. 如果尚无预览版，登录 GitHub，进入包含本版启动器的一次成功 Local Memory 构建，在 Artifacts 下载 `vortex-local-windows-release` 或 `vortex-local-ubuntu-latest`。先解压 CI 产物，再解压里面的应用 ZIP。若当前没有相应产物，可按下文从源码启动。[Local Memory 工作流](https://github.com/HaibaraAi2517/Vortex/actions/workflows/local-memory.yml)
3. Windows 上双击解压目录里的 **`Start Vortex.cmd`**。保持窗口打开，工作台就绪后浏览器自动打开。默认地址为 `http://127.0.0.1:8080/`。端口被其他程序占用时，可在解压目录的 PowerShell 运行 `& '.\Start Vortex.cmd' -Port 8081`，并把 Agent 的服务地址改为相同端口。

点击「运行真实演示」，工作台将在独立空间把住所从北京更新到上海。点「当前住所」，只勾选上海这条记忆，检查「待复制的消息」，再点「复制任务和所选记忆」。在豆包或其他 Agent 的新对话里粘贴并发送，查看它是否回答上海。工作台只把你勾选的资料放进这条消息。

工作台可直接使用已打包的本地模型。Python 客户端和 MCP 是可选组件，在解压目录按 `README.txt` 安装 `python/` 中的 wheel。Codex CLI 复用自己的登录与模型配置。Python 或 Spring AI 自动回答和更新事实时，需要配置聊天模型。

## 从源码启动

源码启动需要 JDK 21、Maven 3.9+ 和 Python 3.10+。启动脚本首次下载固定 revision 的
BGE-Small-ZH 模型并校验 SHA-256，已有正确文件会直接复用。模型偏向中文，英文全文检索可用，
英文语义召回质量尚需独立评测。首次 Maven 构建需要下载依赖。
如 Hugging Face 不可达，可设置 `VORTEX_MODEL_BASE_URL=https://hf-mirror.com` 使用镜像，SHA-256 校验仍然必需。
`BGE_MODEL_PATH` 可指定同一模型文件的其他目录。更换 embedding 模型不能原地复用旧向量，
应使用新数据目录重新写入；默认本地索引要求 512 维。

Windows，在仓库根目录：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\ops\run-local.ps1
# 后续启动可加 -SkipBuild；端口冲突时加 -Port 18080。
```

Linux/macOS：

```bash
bash ops/run-local.sh
# 后续：SKIP_BUILD=true VORTEX_HTTP_PORT=18080 bash ops/run-local.sh
```

或用单个容器构建运行当前源码：

```sh
docker compose -f docker-compose.local.yml up --build -d
docker compose -f docker-compose.local.yml logs -f vortex
```

API 默认只在本机可达。浏览器打开 `http://127.0.0.1:8080/` 使用记忆工作台，
打开 `http://127.0.0.1:8080/swagger-ui.html` 查看接口。
容器的 `768m` 是 Java heap 上限，不是进程总内存承诺；ONNX 和 Lucene 另需原生内存/文件缓存。

## 浏览器里直接体验

1. 点击「运行真实演示」。页面创建独立的 `demo-日期-随机值` 空间，先保存并召回北京，
   再更新为上海，检查当前召回包含上海且不含北京。演示调用真实 API，不预设成功结果。
2. 点击住所旁的「编辑 / 历史」，查看 v1 北京和 v2 上海。旧版本只保留在审计历史中。
3. 搜索「用户喜欢什么咖啡？」体验中文关键词召回；也可切换混合检索和向量检索。
4. 左侧切换到 `personal`，新建自己的事实。刷新页面或重启服务后，事实列表从磁盘读取。

列表按 key 稳定分页，默认展示有效事实。「含已失效」显示撤回和到期状态；删除的内容与历史不展示。
如需重新使用已删除的资料，在「新建记忆」中选择相同资料类型。自定义标识可选「其他（自定义标识）」并点击「读取已有记忆」，读取版本后填写新内容保存。
编辑基于打开时读到的版本，发生 HTTP 409 时保留输入并提示重新读取，不自动覆盖他人更新。
关闭编辑框会放弃未保存输入。查询展示真实检索片段，不生成答案或宣称答案正确率。
列表管理通过 facts 接口保存的事实；通过 store 追加的事件/文档仍可检索，不属于可更新事实目录。

启用鉴权时，工作台页面本身可以打开，读取和写入 API 仍检查 token 与 namespace。
点击右上角连接状态输入 Bearer token，它只驻留在页面内存，刷新后重新输入。
若 allowlist 只允许 `personal`，随机演示空间会被拒绝；可直接在允许的空间手动创建事实。
接入指南会按当前服务地址和 namespace 生成 Python/MCP 配置。

## Python：几行代码验证真正的更新

以下是直接操作事实的 API。如果希望 Agent 从正常聊天中自动检索、提取和更新事实，
请使用 [Python/Spring AI 自动记忆接入](automatic-agent-memory.md)，其中包含真实跨进程验证脚本。

源码用户在仓库根目录另开终端，保持服务运行。ZIP 用户可按包内 `README.txt` 安装 wheel，再在解压目录运行 `python demo.py`。

```sh
python -m pip install ./sdk/python
python examples/personal-memory/demo.py
```

这个示例先写“我住北京”，再用同一个事实 key 更新为“我已经搬到上海了”，查询当前住所并验证
过期版本的更新被拒绝。重复运行会从当前 revision 继续。

```python
from vortex_memory import VortexClient

memory = VortexClient(namespace="personal")
old = memory.get_fact("user.coffee")
current = memory.remember("user.coffee", "我喜欢喝美式咖啡，不加糖",
                          expected_revision=old["revision"] if old else 0)
print(memory.context("用户喜欢什么咖啡？"))
```

`namespace` 应稳定对应一个人或 Agent，不要每次会话随机更换。`key` 对应一个可变事实，
例如 `user.home.city`。Agent 负责识别要操作的 key，Vortex 负责持久化、版本冲突和检索。
原有 `POST /memory/store` 仍是追加文本，不会自动猜测“我住北京”和“搬到上海了”是否属于同一个人。

## MCP

安装 `python -m pip install "./sdk/python[mcp]"` 后，按
[Python/MCP 配置](../sdk/python/README.md#mcp) 把 `python -m vortex_memory.mcp` 配置成 stdio server。
Python 与 MCP 使用相同 REST 契约和当前事实。MCP namespace 由服务配置固定，工具调用不能覆盖它。
MCP 返回内容与引用，不把大块 embedding 数组塞进模型上下文。

## 记忆契约

| 操作 | 行为 |
| --- | --- |
| `PUT /api/v1/memory/facts` | `{namespace,key,content,expectedRevision,tags?,expiresAt?}`；0 创建，否则原子替换对应版本 |
| `GET /api/v1/memory/facts?namespace=personal&key=user.home.city` | 当前内容、revision、status、时间；不存在返回 404 |
| `GET /api/v1/memory/facts/list?namespace=personal&limit=30&afterKey=...&includeInactive=false` | `{items,nextCursor}`；按 key 升序，游标排除自身，最多 100 条；默认仅有效事实，删除墓碑始终排除 |
| `GET /api/v1/memory/facts/history?...&limit=20&beforeRevision=10` | revision 降序分页，排除 beforeRevision 本身；仅用于审计 |
| `POST /api/v1/memory/facts/retract` | `{namespace,key,expectedRevision}`；停止召回，保留历史 |
| `DELETE /api/v1/memory/facts?...&expectedRevision=2` | 删除 live store 中的内容与历史，仅留下防止旧请求复活的版本墓碑 |
| `POST /api/v1/memory/store` | 追加事件/文档；local 模式成功返回前已经持久化 |
| `POST /api/v1/memory/store/async` | 显式异步导入；只有 pipeline COMPLETED 才代表最终持久化 |
| `POST /api/v1/memory/context` | 生成当前任务所需的 Agent 上下文，可返回零条。默认个人字段按任务选择 |
| `POST /api/v1/memory/recall` | 默认 HYBRID + RRF；也可选择 KEYWORD_ONLY / VECTOR_ONLY |

冲突返回 HTTP 409 和 `currentRevision`，不会自动执行“最后写入获胜”。断网可能发生在提交后，
应先读取 key 再决定是否重试。一条事实最多 480 个 tokenizer token，避免模型静默截断；
较长文档用追加接口。`expiresAt` 使用带时区的 ISO-8601；过期事实在两路检索和 L1 中都被过滤，
不依赖清理任务。旧版本留在审计历史中，普通召回只检索当前版本。

中文由 CJK bigram 分词，英文使用标准化词项。Lucene BM25 覆盖整个 namespace，topK 限制返回结果，
不会把语料截成任意的 256 条。namespace 和全部 tags 先过滤再选候选。查询最多取 256 个不同词项以限制工作量，
并按字面文本处理，不执行 Lucene 查询语法。全是无关内容时，纯关键词查询返回空集。

## 持久化、边界与备份

Windows ZIP 新安装使用 `%LOCALAPPDATA%\Vortex\data`，数据独立于解压目录。启动窗口会显示实际保存位置。升级前先按 `Ctrl+C` 停止服务，备份这个目录，再把新版解压到新文件夹。若旧包将记忆保存在相邻的 `data\vortex`，可在新包目录打开 PowerShell，直接指定原位置：

```powershell
& '.\Start Vortex.cmd' -DataDir 'D:\旧版Vortex\data\vortex'
```

以后可用同一命令启动，或设置 `VORTEX_DATA_DIR` 指向该目录。启动器依次选择显式 `-DataDir`、`VORTEX_DATA_DIR`、当前包相邻的旧 `data\vortex` 和当前用户的默认位置。请勿将两个已有数据目录直接覆盖或合并。

源码默认数据目录是 `./data/vortex`，可用 `VORTEX_DATA_DIR` 指定绝对目录。
单容器使用命名卷 `vortex-local-data`。一次目录只能由一个进程打开；第二个进程会明确失败。
local 模式普通写入和事实更新在响应前提交到磁盘，历史/current/index 处于同一 Lucene commit。
已替换事实的旧缓存、异步强化或 DLQ 回放不能重新写入旧版本。

备份：停止服务后复制整个数据目录/卷；恢复到独立目录后用相同 embedding 维度启动。
带工作台的版本会在首次打开 schema 1 的本地索引时，将当前事实目录升级为 schema 2，
按页补齐列表索引字段，保留原内容、向量和历史 revision。升级是一次原子提交；旧二进制不再支持
打开升级后的目录，升级前应按上述方法备份，回退时使用备份副本。
不要只复制 WAL 或运行中手动选择 Lucene 文件。`docker compose down` 保留卷；不要加 `-v`，
除非确实要删除所有数据。`forget` 是 live store 逻辑删除，不是磁盘安全擦除；已有备份也应按自己的保留策略处理。

这是本机、单进程、个人使用的 alpha，不提供多租户身份或公网部署保证。
需要共享给可信进程时可启用已有 Bearer token 与 namespace allowlist：

```powershell
$env:VORTEX_SECURITY_ENABLED = "true"
$env:VORTEX_SECURITY_BEARER_TOKEN = "替换为至少32字符的随机token"
$env:VORTEX_SECURITY_NAMESPACE_PATTERNS = "personal"
```

运行时任务恢复接口仍保留，但 local 模式的 Execution ID backend 为内存，不能将其描述成
跨重启的副作用 exactly-once。原 Milvus/MinIO 路径通过 `--spring.profiles.active=distributed`
选择；旧 Quickstart Compose 已显式选择该模式。它尚不支持 facts 原子更新接口；兼容关键词分支
会遍历 namespace 全部页面，成本随数据量增加，主要供旧部署迁移和对照使用。

## 验证范围

`mvn verify` 包含无需 Docker 的 local HTTP 集成测试，覆盖中文召回、历史更新、409、失效和 namespace 授权；
storage 测试覆盖超过 256 条的语料、索引重开、并发 CAS、晚到写入和 checkpoint。
`python -m unittest discover -s sdk/python/tests -v` 覆盖 HTTP 客户端与官方 MCP client 的 stdio 握手/调用。
实际模型与进程重启的 smoke 脚本见 [verify-local-memory.py](../ops/verify-local-memory.py)。

这些是正确性和可启动性验证，不是用户采用率、端到端答案准确率或容量承诺。
历史 LongMemEval oracle 数字只属于其原始版本；当前分词和默认融合行为变化后不能直接继承。

## 构建可分享的试用包

在仓库根目录完成 Java 构建及 `python ops/verify-local-memory.py --mcp` 后，运行：

```sh
python -m pip install build
python -m build sdk/python
python ops/package-local.py
python ops/package-windows-runtime.py
```

`target/vortex-local-alpha.zip` 包含应用、本地模型和 Python wheel，需要系统 Java 21。`target/vortex-local-windows-x64.zip` 额外包含 Windows x64 用的 Temurin 21 运行时。两者均有配套的 `.zip.sha256`。分发 Windows 版本时，将生成的 Temurin 源码压缩包和摘要一起提供。

打包器核对本地模型摘要、wheel 与源码、JAR 与通过测试的版本。验收可从解压后的 ZIP 启动服务并调用其中的 Python/MCP 客户端。构建产物位于 `target/`，`local-` 预览版与旧版 Milvus/MinIO 部署使用不同的下载入口。
