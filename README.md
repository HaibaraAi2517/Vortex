# Vortex

简体中文 · [English](README_en.md)

[![CI](https://github.com/HaibaraAi2517/Vortex/actions/workflows/ci.yml/badge.svg)](https://github.com/HaibaraAi2517/Vortex/actions/workflows/ci.yml)
[![Java 21](https://img.shields.io/badge/Java-21-orange.svg)](pom.xml)
[![License](https://img.shields.io/badge/License-Apache--2.0-blue.svg)](LICENSE)

**给个人 AI Agent 的本地持久记忆。记住偏好和项目事实，在事实变化时更新它，下次对话只召回当前版本。**

一个 Java 进程，一个本地数据目录；通过 Python、Spring AI、MCP 或 REST 接入。
默认模式内嵌 Lucene BM25、中文 bigram 与 HNSW 向量检索，使用本地 BGE 模型，
存储和检索不需要外部数据库、Docker 或聊天模型 API Key。
自动对话和事实抽取需要另外配置聊天模型；Codex 接入复用客户端已有的模型和登录配置。

当前源码的本地模式是 **alpha**，面向个人开发者本机使用。
已发布的 [v0.2.0](docs/releases/v0.2.0.md) 使用原 Milvus/MinIO 架构，**不包含下面的新功能**。
Python 包目前从源码安装，尚未发布到 PyPI；Java Maven 构件也尚未发布。

| 想做什么 | 从这里开始 |
| --- | --- |
| 先看到记忆更新与召回效果 | 启动服务，打开下方的记忆工作台 |
| 让 Python Agent 正常聊天时自动记忆 | [MemorySession 与模型配置](docs/automatic-agent-memory.md#python正常聊天即可) |
| 在 Codex 与 Python 间共享事实 | [Codex CLI 接入指南](docs/codex-memory.md) |
| 给 Spring AI 应用加入自动记忆 | [ChatClient Advisor 示例](examples/spring-ai-integration/README.md) |
| 接入其他 MCP 客户端或自定义 Agent | [Python SDK、MCP 工具与配置](sdk/python/README.md) |

## 快速接入

源码启动需要 JDK 21、Maven 3.9+ 和 Python 3.10+。
首次启动会下载依赖和固定版本的本地模型，并校验 SHA-256；后续启动复用本地文件。
从源码构建的试用 ZIP 包包含模型，运行记忆服务只需 Java 21；Python/MCP 接入另需 Python。
见 [打包步骤](docs/local-memory.md#构建可分享的试用包)，目前没有新的公开 release 下载。

在仓库根目录启动服务：

```powershell
# Windows
powershell -NoProfile -ExecutionPolicy Bypass -File .\ops\run-local.ps1
```

```bash
# Linux / macOS
bash ops/run-local.sh
```

启动后打开 **[记忆工作台](http://127.0.0.1:8080/)**，点击「运行真实演示」。
工作台会实际写入北京、更新为上海，再验证普通召回排除了旧版本。
也可以直接新增自己的记忆，查看版本历史，撤回事实，或复制检索片段给 Agent。
整个流程不需要 Python 客户端、外部数据库或聊天模型 API Key。
这里展示真实的存储和检索结果，不生成 Agent 聊天答案。

需要接入代码时，另开终端：

```bash
python -m pip install ./sdk/python
python examples/personal-memory/demo.py
```

示例会写入“我住北京”，更新为“我已经搬到上海了”，召回当前住所，并验证旧版本更新被拒绝。
服务默认监听 `127.0.0.1:8080`，数据位于 `./data/vortex`，关闭服务后仍保留。
后续 Windows 启动可加 `-SkipBuild`；端口冲突可加 `-Port 18080`。

也可以只运行一个容器：

```bash
docker compose -f docker-compose.local.yml up --build -d
```

[完整本地指南：配置、鉴权、备份与接口](docs/local-memory.md)

## 在你的 Agent 中使用

Python Agent 可以通过 `MemorySession` 自动检索和学习，不必在每一轮手动调用写入接口。
安装 `python -m pip install ./sdk/python` 后，配置 `DEEPSEEK_API_KEY`，或
`MODEL_BASE_URL`、`MODEL_NAME`、`MODEL_API_KEY` 三项兼容模型配置，再运行：

```python
from vortex_memory import CompatibleChatModel, MemorySession, VortexClient

memory = VortexClient(namespace="personal")
agent = MemorySession(memory, CompatibleChatModel.from_env())
turn = agent.chat("我已经搬到上海长期居住了。")
print(turn.answer)
print(turn.updates)  # 服务已确认提交的事实及版本
print(turn.errors)   # 提取失败、版本冲突或写入结果未知
```

新建 `MemorySession` 或重启客户端会清空对话历史；连接同一 namespace 后仍可读取长期记忆。
默认自动抽取管理居住城市、咖啡偏好、姓名、职业和回复语言五个字段，可按业务配置字段。
完整交互示例：`python examples/personal-memory/agent.py --namespace personal`。

需要自行控制写入时机时，使用底层客户端：

```python
from vortex_memory import VortexClient

memory = VortexClient(namespace="personal")
key = "user.home.city"
old = memory.get_fact(key)
memory.remember(key, "我已经搬到上海了",
                expected_revision=old["revision"] if old else 0)
context = memory.context("用户现在住在哪里？")
# 把 context 作为参考数据加入 Agent 上下文。
```

用稳定的 namespace 对应一个人/Agent，用稳定的 key 对应一个可变事实。
`remember` 是显式事实更新，`store` 是事件/文档追加；原始文本追加不会自动判断矛盾。
并发更新同一版本时返回 HTTP 409，调用方重新读取后决定如何处理。

MCP 客户端安装 `python -m pip install "./sdk/python[mcp]"`，配置 stdio 命令
`python -m vortex_memory.mcp`，即可使用 `remember`、`recall`、`get_memory`、
`memory_history`、`retract_memory` 和 `forget_memory`。
[Python 文档与完整 MCP 配置](sdk/python/README.md)

使用 Codex CLI 时，可以保留现有模型和登录配置，在独立目录直接启动带 Vortex 的会话：

```bash
python -m pip install "./sdk/python[mcp]"
python ops/run-codex-memory.py --namespace personal
```

启动脚本为本次运行配置 MCP，并加载示例事实管理规则；同一 namespace 下的 Python Agent 可共享更新。
见 [Codex 接入指南](docs/codex-memory.md)和 [Codex ↔ Python 九轮实测记录](docs/codex-memory-verification.md)。

需要正常聊天时自动检索和更新事实，可使用 Python `MemorySession` 或 Spring AI `VortexMemoryAdvisor`。
两者在回答前检索，回答后通过真实模型提取配置字段，并按版本提交；MCP 继续共享同一记忆库。
见 [自动 Agent 接入与跨会话验证](docs/automatic-agent-memory.md)。自动抽取需要额外配置聊天模型。

## 解决哪些问题

| 需求 | 当前行为 |
| --- | --- |
| 中文自然语言查询 | CJK bigram + BM25 与本地向量融合；“用户喜欢什么咖啡”能匹配“我喜欢喝美式咖啡，不加糖” |
| 几百条之后仍能检索 | 全文索引覆盖完整 namespace；topK 只限制结果，语料不截断为任意 256 条 |
| 北京搬到上海 | 相同 key 原子更新；旧版本保留在审计历史中，普通召回排除它 |
| 临时偏好/错误事实 | `expiresAt` 到期失效、`retract` 撤回、`forget` 删除 live store 内容和历史 |
| 重启后继续使用 | 本地普通写入在成功响应前提交到磁盘；重启无需重建语料 |
| 防止旧写入复活 | revision CAS、持久版本墓碑、缓存与后台重试的生命周期过滤 |
| 与其他 Agent 隔离 | namespace 和 tags 过滤；可启用 Bearer token + namespace allowlist |

当前定位是**个人、单进程、本机持久记忆**。支持配置字段内的模型辅助事实更新，不提供公网、多租户身份、任意领域事实归并或容量 SLA。
BGE-Small-ZH 偏向中文，英文语义质量需另行评测。`forget` 是逻辑删除，已有备份与磁盘安全擦除需另行管理。

## 验证与质量边界

已经完成以下本机验证，记录包含具体场景与未覆盖范围：

| 验证 | 实际结果 | 证据 |
| --- | --- | --- |
| 本地服务与持久化 | 中文检索、300 条干扰记忆、版本冲突、强制重启和官方 MCP Client 调用通过 | [本地服务验收](docs/local-memory-verification.md) |
| Python ↔ Spring AI | 7 轮真实模型对话，新进程共享并更新城市与咖啡偏好 | [自动记忆验收](docs/automatic-agent-memory-verification.md) |
| Codex CLI ↔ Python | 9 轮真实模型对话，保存、更新、撤回后读取通过；6 个独立 Codex 会话、12 次 MCP 调用 | [Codex 共享记忆验收](docs/codex-memory-verification.md) |
| Python 客户端与协议 | 2026-11-17 完整测试 81/81 通过，包含 MCP stdio、版本冲突、未知写入结果和评测有效性 | [测试代码](sdk/python/tests) |
| 中文跨会话质量 | 100 个自建合成案例，对比无记忆、普通摘要与当前 Vortex；保留执行错误和逐例原始证据 | [质量基线与失败分析](docs/memory-quality-baseline-20261117.md) |

Codex 这次按已知 key 读取事实，配合提供的项目规则运行；这些固定场景不是语义检索或普遍答案正确率评测。
中文质量基线使用同模型自动裁判，重点检查归属、指代、更新、撤回和拒答；它不是人工准确率或竞品排名。复现方法见[评测指南](docs/memory-quality.md)。
首轮判对数为无记忆 23/100、普通摘要 94/100、Vortex 86/100，有效裁判数分别为 96、95、95；三组均有运行异常。第二轮因模型服务 HTTP 402 中断，尚无完整无异常的确认成绩。开发集暴露的主要问题是引用归属、指代抽取与已召回事实的回答错误，后续改进以这些失败证据为依据。

复现基础验证：

```bash
python ops/download-model.py --all
mvn verify
python -m pip install "./sdk/python[mcp]"
python -m unittest discover -s sdk/python/tests -v
python ops/verify-local-memory.py --mcp
```

最后一条命令使用真实本地 BGE 模型，启动独立数据目录和端口，写入 300 条干扰记忆，
检查中文召回、版本更新、强制进程重启和 MCP 调用，不访问你的日常记忆目录。
[本轮验证记录与未验证边界](docs/local-memory-verification.md)。

历史 [LongMemEval oracle 证据](docs/benchmark.md) 测量的是片段召回，不能证明答案正确率。
本轮分词与默认融合行为已变化，不能继承旧版评测结论。非 oracle 的端到端答案质量和第一批
真实用户的持续使用情况，仍是下一阶段的发布门槛，见 [ROADMAP](ROADMAP.md)。
[非 oracle LongMemEval-S 生成与官方评分接入](docs/non-oracle-evaluation.md) 已提供，尚未宣称答案准确率。

## Java 集成与已有内核

现有的 Task DAG、Checkpoint、WAL 和 Execution ID 内核保留为进阶能力；个人记忆接入无需学习它们。
local 模式的 Execution ID 记录在内存，不能用它声称跨重启副作用 exactly-once。

- [Spring AI 示例](examples/spring-ai-integration/README.md)
- [LangChain4j 示例](examples/langchain4j-integration/README.md)
- [原分布式 Quickstart](docs/quickstart.md)：显式使用 `distributed` profile 和 Milvus/MinIO
- [架构与恢复机制](docs/architecture.md)

分布式兼容模式暂不支持新的 facts 原子更新接口；其关键词分支遍历全部 namespace 页面，成本随数据量增长。
新用户优先使用本地模式。

## 许可证

[Apache-2.0](LICENSE)。模型和依赖保留各自上游许可，见 [THIRD_PARTY_NOTICES](THIRD_PARTY_NOTICES.md)。
