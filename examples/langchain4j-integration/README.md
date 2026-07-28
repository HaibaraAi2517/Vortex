# LangChain4j Integration Example

This example shows Vortex as durable recall memory for a LangChain4j
`AiServices` interface. It configures a `chatRequestTransformer` that calls
Vortex `/api/v1/memory/recall` for the current user request, then injects the
recalled facts as a `SystemMessage` before the `ChatRequest` reaches the
`ChatModel`.

The runnable demo uses a fake `ChatModel`, so it verifies the LangChain4j
integration path without calling an external LLM provider. No API key is
required.

## Prerequisites

Start Vortex with the quickstart stack from the repository root:

```powershell
docker compose -f docker-compose.quickstart.yml up --build -d
```

Wait for health:

```powershell
Invoke-RestMethod http://localhost:8080/actuator/health
```

## Run

```powershell
mvn -q -f examples/langchain4j-integration/pom.xml exec:java
```

Expected output includes:

```text
Transformer recall count: 1
Injected LangChain4j system memory:
Relevant durable memory recalled from Vortex:
1. LangChain4j integration demo facts: launch codename is Nimbus Ledger; ...
Demo complete. No external LLM API key was used.
```

## Use With A Real ChatModel

Replace the fake model with any LangChain4j `ChatModel` implementation and keep
the Vortex transformer in the `AiServices` builder:

```java
VortexMemoryClient vortex = new VortexMemoryClient(URI.create("http://localhost:8080"));
VortexChatRequestTransformer memoryTransformer =
        new VortexChatRequestTransformer(vortex, "agent-session-1");

Assistant assistant = AiServices.builder(Assistant.class)
        .chatModel(realChatModel)
        .chatRequestTransformer(memoryTransformer)
        .build();

String answer = assistant.chat("What launch facts should I keep in mind?");
```

The transformer is intentionally small: it demonstrates where Vortex fits in a
LangChain4j application without taking over model selection, prompt policy,
authentication, or production deployment concerns.

## API Surface Used

- `POST /api/v1/memory/store`
- `POST /api/v1/memory/recall`
