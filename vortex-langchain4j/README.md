# Vortex LangChain4j Adapters

This module connects LangChain4j model interfaces to Vortex's provider-neutral
kernel contracts:

- `LangChain4jGenerationAdapter` adapts `ChatModel` to `GenerationService`.
- `LangChain4jEmbeddingAdapter` adapts `EmbeddingModel` to `EmbeddingService`.

The adapters are optional. The default application path continues to use the
repository's OpenAI-compatible generation client and local ONNX BGE embedding
service unless an application explicitly supplies these adapters.

## Generation

```java
ChatModel chatModel = createChatModel();
GenerationService generation = new LangChain4jGenerationAdapter(chatModel);

GenerationResult result = generation.generate(GenerationRequest.builder()
        .systemPrompt("Answer from durable memory when relevant.")
        .userPrompt("What should the agent resume next?")
        .temperature(0.1)
        .maxTokens(256)
        .build());
```

## Embedding

```java
EmbeddingModel embeddingModel = createEmbeddingModel();
EmbeddingService embeddings = new LangChain4jEmbeddingAdapter(embeddingModel);

float[] vector = embeddings.embed("checkpoint recovery state");
```

Provider timeouts, authentication, retries, and transport settings remain the
responsibility of the supplied LangChain4j model implementation.
