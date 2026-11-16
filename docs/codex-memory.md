# Codex 与 Python 共享记忆

这条接入路径把 Codex 的 MCP 工具和 Python Agent 接到同一个 Vortex 服务、同一个 namespace。记忆由应用保存，切换客户端或新开会话后仍可读取。它不替换 Codex 的项目指令和内置记忆。

MCP 是工具入口，不会监听全部聊天。Codex 是否主动调用取决于客户端可用工具、项目指令和模型行为；Python `MemorySession` 则由代码确定检索与写回的时机。

已经完成一次真实 Codex CLI 与 Python 的九轮验收，逐轮输入、答案、提交版本及限制见[验收记录](codex-memory-verification.md)。

## 最短体验路径

以下命令在源码仓库根目录执行，需要 Java 21、Maven、Python，以及已经能正常回答的 Codex CLI。

1. 安装 Python/MCP 客户端：

   ```powershell
   python -m venv sdk/python/.venv
   sdk/python/.venv/Scripts/python.exe -m pip install -e "./sdk/python[mcp]"
   ```

2. 启动服务并保持这个终端运行：

   ```powershell
   powershell -NoProfile -ExecutionPolicy Bypass -File ./ops/run-local.ps1
   ```

   浏览器打开 `http://127.0.0.1:8080/`。Linux/macOS 用 `bash ops/run-local.sh`，完整说明见[本地模式指南](local-memory.md)。

3. 新开终端，在仓库根目录进入带 Vortex 记忆的 Codex：

   ```powershell
   sdk/python/.venv/Scripts/python.exe ops/run-codex-memory.py --namespace personal
   ```

4. Python 侧配置好 `MODEL_*` 或 `DEEPSEEK_API_KEY` 后，在另一个终端使用相同 namespace：

   ```powershell
   sdk/python/.venv/Scripts/python.exe examples/personal-memory/agent.py --namespace personal
   ```

   模型配置见[自动 Agent 接入指南](automatic-agent-memory.md)。先向 Codex 告知一条事实，再向 Python 询问；在 Python 中更正后，重新打开 Codex 询问。确认最终回答和工作台里的实际版本一致。

脚本在一个新的临时目录放入操作规则，打开交互式 Codex，通过本次运行的 `-c` 参数连接 `vortex-memory`。它保留用户已有的模型、provider 和认证配置，不写全局 `config.toml`，也不更换 `CODEX_HOME`。退出后可用相同命令重新开始；namespace 不变，记忆仍在。

默认服务地址可用 `--url` 修改。启用服务鉴权时，先设置 `VORTEX_SECURITY_BEARER_TOKEN`，脚本只把环境变量名传给 Codex，不把 token 值写入命令参数或配置文件。不同客户端使用相同 namespace 才共享事实；用户身份到 namespace 的映射由应用负责。

MCP 适配器实际运行的是当前 Python 的 `-m vortex_memory.mcp`，通过 stdio 连接；8080 是 REST 服务地址，不是 HTTP MCP 端点。Windows 的 npm 启动脚本会解析成 Node + Codex JS 入口，以 `shell=False` 启动；无法解析时可通过 `--codex` 指定原生 `codex.exe`。

## 如果使用解压后的试用 ZIP

ZIP 已包含 JAR、模型、Python wheel 和 Codex 启动器，无需 Maven 或源码中的 `sdk/python` 目录。在解压根目录安装包内 wheel：

```powershell
python -m venv .venv
.venv/Scripts/python.exe -m pip install "python/vortex_agent_memory-0.3.0a1-py3-none-any.whl[mcp]"
```

然后在一个终端运行 `powershell -NoProfile -ExecutionPolicy Bypass -File ./start.ps1`，保持服务运行。在另一个终端使用 `.venv/Scripts/python.exe ops/run-codex-memory.py --namespace personal` 进入 Codex；Python Agent 的入口是 `.venv/Scripts/python.exe agent.py --namespace personal`。Linux/macOS 使用 `bash start.sh` 和 `.venv/bin/python`。

后文的源码示例需相应替换 Python 路径及 Agent 入口。九轮自动验收脚本目前为源码仓库专用，不包含在试用 ZIP 中。

## 给独立试用目录配置操作规则

接入脚本自动复制 [AGENTS.md 示例](../examples/codex-memory/AGENTS.md)。示例只管理居住地、咖啡偏好、姓名、职业和回复语言五个字段，和 Python 默认 schema 一致。仅在独立的新试用目录启用这套完整政策；可用 `--workdir` 指定该目录，已有的不同 `AGENTS.md` 会被拒绝覆盖。接入已有真实项目时，应按业务字段手动合并操作政策，不能直接替换其项目指令，也不要把示例当作所有项目的全局规则。

规则要求 Codex 从普通陈述主动更新事实、回答问句前读取当前状态、成功写入后报告版本。第三人信息、计划和问句不应产生记忆。发生版本冲突不自动覆盖；撤回通过 `retract_memory` 完成，之后不能从历史里恢复旧答案。

这是给客户端的操作政策，不是服务端强制执行的语义规则。服务端强制 namespace 隔离、版本检查和失效过滤，但仍需实际观察模型是否正确调用工具。

## 手动观察跨客户端效果

保持服务运行，在每一步使用新会话或新进程，并让两个客户端连接同一 namespace：

1. 向 Codex 告知当前长期住所和咖啡偏好。观察真实 `get_memory` / `remember` 调用，以及确认提交的 revision。
2. 新开 Codex 会话询问这两个事实。确认使用读取工具且没有新增版本。
3. 运行 Python 自动 Agent，正常告知更新后的住所或偏好：

   ```powershell
   sdk/python/.venv/Scripts/python.exe examples/personal-memory/agent.py --namespace personal
   ```

   Python 示例需要配置真实模型，见[自动 Agent 接入指南](automatic-agent-memory.md)。终端的 `[已提交]` 才是服务确认的结果，助手回答本身不是保存证据。

4. 再新开 Codex 会话，询问当前事实。它应读取 Python 提交的新版本，不继续使用旧值。
5. 请 Codex 撤回一个事实；再新开会话询问，应该明确不知道，不能通过 `memory_history` 找回后继续回答。

如果希望每轮单独启动、直接看到答案，可以在专用试用空间内预授权保存与撤回：

```powershell
sdk/python/.venv/Scripts/python.exe ops/run-codex-memory.py --isolate --namespace codex-trial --message "我目前长期住在北京，平时最喜欢无糖美式。"
sdk/python/.venv/Scripts/python.exe ops/run-codex-memory.py --isolate --namespace codex-trial --message "我现在长期住在哪里，喜欢什么咖啡？"
```

`--message` 使用新的 `codex exec --ephemeral` 会话，用户文本通过 UTF-8 stdin 传入。以上 `--isolate` 命令只用于已授权保存/撤回的试用空间；普通 `--message` 保留宿主审批设置，写入可能被拒绝，不保证能无人值守完成。可加 `--json` 观察工具事件、`--output-last-message 路径` 保存最终答案。

工作台选择相同 namespace，可检查活跃事实、历史和撤回状态。受管理事实的旧版本只用于审计，普通召回不包含它们；追加文档不参与字段覆盖。`retract` 保留审计历史；`forget` 删除 live store 的内容与历史，二者都不等于磁盘安全擦除。

## 自动验收真实客户端

配置好 Python 示例所需模型后，在仓库根目录执行：

```powershell
sdk/python/.venv/Scripts/python.exe ops/verify-codex-memory.py
```

脚本使用随机空 namespace，运行 9 轮跨进程对话：Codex 空库问答、普通陈述写入、Python 读取并更新、Codex 读取新值、排除第三人信息和计划、Codex 撤回、Codex 与 Python 再次读取。每轮保留真实工具结果与数据库状态，不以助手自述判定保存成功。

报告默认写入 `target/codex-memory-live.json`，旁边的 `codex-memory-live-evidence` 保存 Codex JSONL 和 Python 报告。已存在的输出会被拒绝覆盖；再次运行请用 `--output target/codex-memory-live-2.json`。`--codex` 可以指定可执行文件，`--timeout` 设置单轮超时秒数。

验收使用 launcher 的 `--isolate` 模式：临时禁用内置记忆、其他 MCP、浏览器、shell 和 hooks 等工具，采用只读文件沙箱，只让 Codex 通过 Vortex MCP 获取长期事实。模型、provider 和认证仍来自用户已有配置。这些限制只作用于本轮子进程；普通交互模式保留用户原来的其他工具。

隔离验收会在配置的 namespace 内预授权 Vortex 的 `remember` 和 `retract_memory`，以完成保存及撤回场景；验收脚本为此创建随机空 namespace。不预授权 `forget_memory` 或其他服务的操作。普通交互模式继续由宿主决定审批，不会获得这两项预授权。手动使用 `--isolate` 时，应明确选择专用试用 namespace。

`--isolate`、`--json` 和 `--output-last-message` 必须与 `--message` 同用。Python 3.10 用户运行隔离验收前需安装 `tomli` 以读取 MCP 配置名称，Python 3.11+ 不需要。隔离模式拒绝无法安全禁用的特殊 MCP 名称，而不会猜测配置路径。

## 如何判断这次接入是否成立

至少保留每轮用户原话、客户端类型、工具调用及结果、最终回答和实际事实版本。只看到“我记住了”不算成功；只用官方 MCP Client 调通也不能代替真实 Codex 验收。

首次验收关注跨客户端一致性、问句不写入和撤回后不引用历史。这是一个小规模行为检查，不是普遍答案准确率，也不是 Codex 所有版本和模型都能稳定遵守指令的保证。

Codex 参数依据 OpenAI Docs 的 [MCP 接入](https://learn.chatgpt.com/docs/extend/mcp?surface=cli)和[非交互模式](https://learn.chatgpt.com/docs/non-interactive-mode)。当前脚本针对本机 `codex-cli 0.163.0` 核对；其他版本应先运行自己的隔离验收。
