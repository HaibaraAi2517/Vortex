# Spring AI Integration Example

This example shows Vortex as durable recall memory for Spring AI `ChatClient`.
It implements a small `BaseAdvisor` that calls Vortex `/api/v1/memory/recall`
with the current user request, then injects the recalled facts into the Spring AI
system prompt.

The runnable demo uses a fake advisor chain, so it verifies the Spring AI advisor
integration without calling an external LLM provider. No API key is required.

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
VortexMemoryClient vortex = new VortexMemoryClient(URI.create("http://localhost:8080"));
VortexMemoryAdvisor memoryAdvisor = new VortexMemoryAdvisor(vortex, "agent-session-1");

ChatClient chatClient = ChatClient.builder(chatModel)
        .defaultAdvisors(memoryAdvisor)
        .build();

String answer = chatClient.prompt()
        .user("What launch facts should I keep in mind?")
        .advisors(advisors -> advisors.param(VortexMemoryAdvisor.CONTEXT_NAMESPACE, "agent-session-1"))
        .call()
        .content();
```

The advisor is intentionally small: it demonstrates where Vortex fits in Spring
AI without taking over model selection, prompt policy, authentication, or
production deployment concerns.

## API Surface Used

- `POST /api/v1/memory/store`
- `POST /api/v1/memory/recall`