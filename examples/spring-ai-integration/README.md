# Spring AI Automatic Memory

Use Spring AI 2.0 `ChatClient` with automatic retrieval and evidence-backed fact updates:

```java
VortexMemoryClient memory = new VortexMemoryClient(
        URI.create("http://127.0.0.1:8080"), System.getenv("VORTEX_SECURITY_BEARER_TOKEN"));
VortexFactLearner learner = new VortexFactLearner(memory, chatModel);
VortexMemoryAdvisor advisor = new VortexMemoryAdvisor(memory, "personal", learner);
ChatClient chat = ChatClient.builder(chatModel).defaultAdvisors(advisor).build();
var result = chat.prompt().user("我搬到上海长期居住了。")
        .call().chatClientResponse();
var report = (VortexFactLearner.Report) result.context()
        .get(VortexMemoryAdvisor.CONTEXT_MEMORY_REPORT);
```

`before()` snapshots revisions and injects retrieved facts. After a completed answer,
the raw extraction model proposes updates only from the user's message. The learner
validates the configured field keys and verbatim evidence, then commits with the original
revisions. Do not recursively call the advised ChatClient from the extraction model.

Display `report.errors()` alongside the answer; `.content()` alone discards commit status.
Multi-field updates are not a transaction. Conflicts are not retried, and network failures
can leave an unknown commit outcome. Semantic judgments still need domain evaluation.
Default fields cover residence, coffee preference, name, occupation and explicitly
requested response language. Customize them using `VortexFactLearner.Field`.

Streaming forwards answer chunks immediately and learns once after successful completion.
The final `ChatClientResponse` is a metadata-only event with `vortex.memory.report`.
Cancellation and stream errors do not write facts. The original two-argument Advisor
constructor remains recall-only.

## Run a real-model conversation

Start Vortex in [local mode](../../docs/local-memory.md). Set `DEEPSEEK_API_KEY`, or all
of `MODEL_BASE_URL`, `MODEL_NAME`, `MODEL_API_KEY` for a compatible provider.

```powershell
$env:VORTEX_NAMESPACE = "personal"
$env:AGENT_MESSAGE = "我长期住在北京，最喜欢无糖美式。"
mvn -q -f examples/spring-ai-integration/pom.xml compile exec:java "-Dexec.mainClass=com.vortex.examples.springai.AutomaticMemoryDemo"
```

Run again with `AGENT_MESSAGE` set to a question or changed preference. Each run is a
fresh JVM with no previous chat history. `AGENT_REPORT` optionally saves the answer,
recall count, acknowledged commits and errors as JSON.

Run deterministic tests with `mvn -B -f examples/spring-ai-integration/pom.xml test`.
They cover the real ChatClient advisor chain, CAS, invalid evidence, retraction, failures
and streaming lifecycle. The [complete guide](../../docs/automatic-agent-memory.md)
includes a live Python → Spring AI → Python → MCP verification command.

## Original recall-only demo

The original demo below shows Vortex as durable recall memory for Spring AI `ChatClient`.
It implements a small `BaseAdvisor` that calls Vortex `/api/v1/memory/recall`
with the current user request, then injects the recalled facts into the Spring AI
system prompt.

The runnable demo uses a fake advisor chain, so it verifies the Spring AI advisor
integration without calling an external LLM provider. No API key is required.

## Prerequisites

Start Vortex in local mode from the repository root:

```powershell
.\ops\run-local.ps1
```

Wait for health:

```powershell
Invoke-RestMethod http://localhost:8080/actuator/health
```

## Run

```powershell
mvn -q -f examples/spring-ai-integration/pom.xml exec:java
```

Expected output includes:

```text
Advisor recall count: 1
Injected Spring AI system memory:
Relevant durable memory recalled from Vortex:
1. Spring AI integration demo facts: launch codename is Aurora Ledger; ...
Demo complete. No external LLM API key was used.
```

## Use With A Real ChatClient

Wire the advisor into a normal Spring AI `ChatClient` that uses your chosen
`ChatModel`:

```java
VortexMemoryClient vortex = new VortexMemoryClient(
        URI.create("http://localhost:8080"),
        System.getenv("VORTEX_SECURITY_BEARER_TOKEN"));
VortexMemoryAdvisor memoryAdvisor = new VortexMemoryAdvisor(vortex, "quickstart-agent-session-1");

ChatClient chatClient = ChatClient.builder(chatModel)
        .defaultAdvisors(memoryAdvisor)
        .build();

String answer = chatClient.prompt()
        .user("What launch facts should I keep in mind?")
        .advisors(advisors -> advisors.param(
                VortexMemoryAdvisor.CONTEXT_NAMESPACE,
                "quickstart-agent-session-1"))
        .call()
        .content();
```

The advisor is intentionally small: it demonstrates where Vortex fits in Spring
AI without taking over model selection, prompt policy, authentication, or
production deployment concerns.

## API Surface Used

- `POST /api/v1/memory/store`
- `POST /api/v1/memory/recall`
- `GET /api/v1/memory/facts` (automatic learner)
- `PUT /api/v1/memory/facts` (automatic learner)
- `POST /api/v1/memory/facts/retract` (automatic learner)
