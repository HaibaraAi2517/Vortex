# Agent 自动记忆：Python、Spring AI 与 MCP

Vortex 的记忆库可以由 Python Agent、Spring AI 和 MCP 客户端共同使用。Python `MemorySession` 和 Spring AI `VortexMemoryAdvisor` 负责自动调用时机；MCP 继续提供 6 个显式工具。

自动流程需要一个真实模型进行回答和事实抽取。Vortex 本地存储和 BGE 检索本身不需要外部模型 API。

使用 Codex CLI 时，见 [Codex 与 Python 共享记忆](codex-memory.md)；[九轮实测记录](codex-memory-verification.md)展示了 Codex 保存、Python 更新、Codex 撤回以及两个客户端重新读取的实际结果。

## 先运行本地记忆服务

按 [本地模式指南](local-memory.md) 启动服务，默认地址 `http://127.0.0.1:8080`。自动事实更新使用 `/api/v1/memory/facts`，目前需要 `local` 存储模式。

配置一个支持 Chat Completions 和 JSON 输出的模型。已有 `DEEPSEEK_API_KEY` 时，Python 和 Java 示例会使用 `deepseek-chat`；也可以显式配置其他兼容端点：

```powershell
$env:MODEL_BASE_URL = "https://ark.cn-beijing.volces.com/api/v3"
$env:MODEL_NAME = "你的方舟模型或推理接入点 ID"
$env:MODEL_API_KEY = "你的模型密钥"
$env:VORTEX_NAMESPACE = "personal"
```

三个 `MODEL_*` 变量显式配置后，不会自动借用 `DEEPSEEK_API_KEY`。上述方舟配置用于说明接法，本次真实验证使用 DeepSeek，未验证方舟模型。

## Python：正常聊天即可

在仓库根目录安装并启动：

```powershell
python -m pip install -e ./sdk/python
python examples/personal-memory/agent.py --namespace personal
```

依次输入：

```text
我目前长期住在北京，平时最喜欢无糖美式。
/new
我现在长期住在哪个城市，喜欢喝什么咖啡？
我已经搬到上海长期居住了，现在最喜欢燕麦拿铁，不再喝美式。
/new
我现在长期住在哪个城市，喜欢喝什么咖啡？
```

`/new` 清空当前会话历史，长期记忆仍从 Vortex 读取。重启 Python 进程也可以。终端分别显示模型回答、`[已提交]` 的实际版本，以及 `[记忆未完成]` 的错误；工作台选择相同 namespace 可以查看事实和历史。

集成到自己的应用：

```python
from vortex_memory import CompatibleChatModel, MemorySession, VortexClient

memory = VortexClient("http://127.0.0.1:8080", namespace="user-123")
session = MemorySession(memory, CompatibleChatModel.from_env())
turn = session.chat("我搬到上海长期居住了。")
print(turn.answer)
print(turn.updates)  # 仅包含服务已确认提交的事实及版本
print(turn.errors)   # 提取失败、版本冲突、写入结果未知等
```

可以传入自己的模型函数，签名为 `model(messages, *, json_mode=False) -> str`。另一个 `extractor=` 模型可专门负责抽取。自定义适配器必须拒绝截断、失败和未完成的工具调用，不能将其当作完整文本返回。

每个会话使用一个 `MemorySession`，串行处理该会话内的请求。不同会话使用不同实例；Vortex 的版本检查负责检测并发写入冲突。

## Spring AI：在 ChatClient 中启用完整读写

```java
VortexMemoryClient memory = new VortexMemoryClient(
        URI.create("http://127.0.0.1:8080"),
        System.getenv("VORTEX_SECURITY_BEARER_TOKEN"));
// 使用原始 ChatModel 抽取，避免再次进入 Advisor 造成递归。
VortexFactLearner learner = new VortexFactLearner(memory, chatModel);
VortexMemoryAdvisor advisor = new VortexMemoryAdvisor(memory, "user-123", learner);
ChatClient chat = ChatClient.builder(chatModel).defaultAdvisors(advisor).build();
var response = chat.prompt().user("我搬到上海长期居住了。")
        .call().chatClientResponse();
String answer = response.chatResponse().getResult().getOutput().getText();
var report = (VortexFactLearner.Report) response.context()
        .get(VortexMemoryAdvisor.CONTEXT_MEMORY_REPORT);
// 向调用方分别展示 answer、report.updates()、report.errors()。
```

旧的两参数 Advisor 构造器仍然只检索；传入 `VortexFactLearner` 才启用自动写回。应用要读取 `chatClientResponse()` 中的报告；单独 `.content()` 会丢弃提交状态。

流式 `.stream().chatClientResponse()` 立即转发回答片段，在流成功结束后执行一次抽取和提交，最后发出一个只携带上下文报告、没有重复回答文本的事件。取消或流错误不执行学习。消费方需要处理最后的 `vortex.memory.report`。

真实模型示例，每次运行都是全新的 JVM 和会话：

```powershell
$env:VORTEX_NAMESPACE = "personal"
$env:AGENT_MESSAGE = "我已经搬到上海长期居住了，现在最喜欢燕麦拿铁。"
mvn -q -f examples/spring-ai-integration/pom.xml compile exec:java "-Dexec.mainClass=com.vortex.examples.springai.AutomaticMemoryDemo"
```

下次把 `AGENT_MESSAGE` 改为问句，保持 namespace 不变，再次运行即可。

## 自动事实的范围和更新规则

默认自动维护 5 个稳定字段：

| key | 范围 |
| --- | --- |
| `user.home.city` | 当前长期居住城市，排除旅游、出差、计划和过去的住址 |
| `user.preference.coffee` | 长期咖啡偏好，排除单次点单和他人偏好 |
| `user.name` | 明确告知的姓名或称呼 |
| `user.work.role` | 当前职业或工作角色 |
| `user.preference.language` | 明确要求的长期回复语言，不凭本次输入语言猜测 |

这是可配置的事实 schema，不是任意领域的全自动知识归并。应用可定义自己的字段及含义：Python 使用 `MemoryField` / `fields=`，Java 使用 `VortexFactLearner.Field` / 三参数 Learner 构造器，最多 32 个字段。同一 namespace 的不同 Agent 应使用相同的 key 含义。

```python
from vortex_memory import MemoryField, PERSONAL_FACTS
session = MemorySession(memory, model, fields=[
    *PERSONAL_FACTS,
    MemoryField("project.language", "用户明确确认的当前项目主要编程语言"),
])
```

每一轮按以下顺序执行：

1. 读取配置字段及其版本，并检索当前问题相关记忆。
2. 把检索结果作为参考资料加入模型上下文，生成回答。
3. 单独调用模型，从本轮用户原话提取 `set` / `retract` 提案；助手回答不作为事实证据。
4. 检查字段白名单、类型、长度、重复字段，以及证据是否是用户原话中的精确片段。整份提案验证通过后才开始写入。
5. 按第 1 步的版本逐条提交；相同文本不新增版本，明确撤回停止召回但保留历史。

证据片段验证只能确认原话来源，不能证明语义判断一定正确。模型仍可能误解否定、引用或复杂时间关系，需要针对业务场景扩充评测。

## 失败、并发和隔离

- 回答前读取失败会中止本轮，不会默默当作“没有记忆”。
- 回答后抽取或提交失败保留回答，并返回明确报告。界面应显示报告，不能只依据模型自然语言判断是否已保存。
- 不自动重试版本冲突，不先读取新版本再覆盖。网络超时或无效响应按“提交结果未知”报告，需要读回核对。
- 多字段更新逐条提交，不是跨字段事务；报告区分已提交项和失败项。
- 更新活跃事实保留标签和到期时间；自动流程不执行永久遗忘。MCP 或 REST 的显式操作仍可管理删除、历史和失效。
- 默认同步学习后才返回，紧接着的新会话能看到已确认写入的事实；每轮通常需要 2 次模型调用。
- namespace 由应用根据已验证的用户身份确定，不允许模型选择。当前共享 Bearer token 不等于多用户身份系统。

## MCP 继续共享同一个记忆库

同一服务、同一 namespace 下，自动 Agent 写入的事实可以被 MCP 的 `recall` / `get_memory` 读取。MCP 的 `remember` 遵守同一版本控制。配置见 [Python/MCP README](../sdk/python/README.md)。MCP 仍是显式工具入口，没有变成监听全部聊天的后台进程。

## 复现真实跨进程验证

```powershell
python -m pip install -e "./sdk/python[mcp]"
mvn -B -f examples/spring-ai-integration/pom.xml test dependency:build-classpath "-Dmdep.outputFile=target/live-classpath.txt"
python ops/verify-agent-memory.py
```

脚本使用随机隔离 namespace 和实际模型，每轮都启动新 Python 进程或 JVM。依次验证无记忆基线、Python 自动写入、Spring AI 读取并更新、Python 和 Spring AI 再次读取、他人信息/计划不被误存，以及 MCP 共享读取。报告在 `target/agent-memory-live.json`，测试事实保留供工作台检查。

这是小规模端到端场景验收，不是 LongMemEval 答案准确率或生产可靠性证明。CI 运行确定性测试；真实模型验收由配置好凭据的开发者显式执行。

已执行的逐轮输入、模型回答和版本变化见 [真实验收记录](automatic-agent-memory-verification.md)。
