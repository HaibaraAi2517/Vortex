# Vortex

简体中文 · [English](README_en.md)

[![CI](https://github.com/HaibaraAi2517/Vortex/actions/workflows/ci.yml/badge.svg)](https://github.com/HaibaraAi2517/Vortex/actions/workflows/ci.yml)
[![Java 21](https://img.shields.io/badge/Java-21-orange.svg)](pom.xml)
[![License](https://img.shields.io/badge/License-Apache--2.0-blue.svg)](LICENSE)

**让不同的 Agent 共用一份个人记忆。** 在 Codex 里记下住所，搬家后更新一次，下一次换到豆包时就能把新的住所带进对话。Vortex 在本机保存事实、版本和检索索引。

## Windows 上开始使用

1. 在 Releases 找标签以 `local-` 开头的预览版，从 Assets 下载 `vortex-local-windows-x64.zip`。Windows x64 包内含 Java 21 和中文检索模型。[GitHub Releases](https://github.com/HaibaraAi2517/Vortex/releases)
2. 将整个 ZIP 解压到新文件夹，双击 **`Start Vortex.cmd`**。工作台就绪后浏览器会自动打开。使用期间保持启动窗口打开。[默认工作台地址](http://127.0.0.1:8080/)
3. 点击「运行真实演示」。它会在单独的空间记下北京，再把住所更新为上海。点「当前住所」，只勾选上海这条事实，检查预览，点「复制任务和所选记忆」。粘贴到豆包的新对话并发送，查看它能否答出现在的住所。以后可用「新建记忆」保存自己的资料。

工作台的存储、检索和复制都无需 API Key。你决定把哪些资料发送给哪个 Agent。Codex CLI 可以通过 MCP 直接访问同一空间，复用现有登录。Python 或 Spring AI 自动从对话中提取事实时，需要另行配置聊天模型。

如果 Releases 暂无 `local-` 预览版，可登录 GitHub，在包含本版启动器且构建成功的 Local Memory 工作流中下载 `vortex-local-windows-release`。解压这层 CI 产物，再解压里面的 Windows ZIP。旧版 `v0.2.0` 对应 Milvus/MinIO 部署。[Local Memory 工作流](https://github.com/HaibaraAi2517/Vortex/actions/workflows/local-memory.yml)

Windows 新安装默认将数据保存在 `%LOCALAPPDATA%\Vortex\data`。升级时先按 `Ctrl+C` 停止旧服务、备份数据，再将新版解压到另一目录。旧包的数据可能在 `data/vortex`，启动窗口会显示实际路径。Linux 可下载通用包 `vortex-local-alpha.zip`，安装 Java 21 后运行 `bash start.sh`。[数据与升级指南](docs/local-memory.md#持久化边界与备份)

## 接入方式

| 想做什么 | 从这里开始 |
| --- | --- |
| 在 Codex 与 Python 间共享事实 | [Codex CLI 接入指南](docs/codex-memory.md) |
| 让 Python Agent 聊天时自动检索和更新事实 | [MemorySession 与模型配置](docs/automatic-agent-memory.md) |
| 给 Spring AI 应用加入自动记忆 | [ChatClient Advisor 示例](examples/spring-ai-integration/README.md) |
| 接入其他 MCP 客户端或自定义 Agent | [Python SDK、MCP 工具与配置](sdk/python/README.md) |
| 从源码或容器启动 | [本地运行与配置](docs/local-memory.md#从源码启动) |

下面的命令以源码仓库为例。使用 ZIP 时，按包内 `README.txt` 的命令从 `python/` 安装随包 wheel，再运行示例。

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
| Python 客户端、协议与工具 | 2026-11-17 完整测试 92/92 通过，包含 MCP stdio、版本冲突、未知写入结果、评测有效性和诊断脱敏 | [测试代码](sdk/python/tests) |
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
