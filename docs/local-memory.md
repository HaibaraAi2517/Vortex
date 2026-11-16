# 本地 Agent 记忆服务

目标用户是运行个人 Agent 的开发者：用 Python 或 MCP 记录偏好、项目事实和跨会话上下文。
默认 `local` 模式只有一个 Java 进程，使用 Lucene 持久化全文索引、HNSW 向量和归档。
不需要 Milvus、MinIO、etcd、Redis 或外部模型 API Key。此功能属于当前源码的 alpha，
**已发布的 0.2.0 镜像不包含它**。

## 启动

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
如需再次使用已删除的 key，在「新建记忆」中填入 key 并点击「读取已有记忆」，读取版本标记后填写新内容保存。
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

另开一个终端，保持服务运行：

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
| `POST /api/v1/memory/recall` | 默认 HYBRID + RRF；也可选择 KEYWORD_ONLY / VECTOR_ONLY |

冲突返回 HTTP 409 和 `currentRevision`，不会自动执行“最后写入获胜”。断网可能发生在提交后，
应先读取 key 再决定是否重试。一条事实最多 480 个 tokenizer token，避免模型静默截断；
较长文档用追加接口。`expiresAt` 使用带时区的 ISO-8601；过期事实在两路检索和 L1 中都被过滤，
不依赖清理任务。旧版本留在审计历史中，普通召回只检索当前版本。

中文由 CJK bigram 分词，英文使用标准化词项。Lucene BM25 覆盖整个 namespace，topK 限制返回结果，
不会把语料截成任意的 256 条。namespace 和全部 tags 先过滤再选候选。查询最多取 256 个不同词项以限制工作量，
并按字面文本处理，不执行 Lucene 查询语法。全是无关内容时，纯关键词查询返回空集。

## 持久化、边界与备份

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

在上述构建与 smoke 通过后运行：

```sh
python -m pip install build
python -m build sdk/python
python ops/package-local.py
```

生成 `target/vortex-local-alpha.zip` 及 SHA-256 文件。ZIP 内含应用 JAR、本地模型、Python wheel、
启动脚本和验证记录；打包器会检查 smoke 报告对应的 JAR 哈希，拒绝把旧验证套到新二进制上。
收件人只需安装 Java 21，解压后执行 `start.ps1` 或 `bash start.sh`。Python/MCP 客户端可按包内 README 安装。
这只是可分享的源码试用快照，没有自动发布到 GitHub、PyPI 或 Maven Central。

模型 ONNX 与面试准备文档已从当前 Git 跟踪范围移出，本地副本保留。旧 Git 历史未重写；
新用户可用浅克隆避开旧大文件历史。历史评测证据仍保留原链接，避免破坏可追溯性。
