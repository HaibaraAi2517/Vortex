package com.vortex.examples.springai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class VortexMemoryAdvisorTest {
    static final String CITY = "user.home.city", COFFEE = "user.preference.coffee";
    final ObjectMapper json = new ObjectMapper();
    final Map<String, JsonNode> facts = new java.util.concurrent.ConcurrentHashMap<>();
    final List<JsonNode> writes = new java.util.concurrent.CopyOnWriteArrayList<>();
    final AtomicInteger extractions = new AtomicInteger();
    final AtomicReference<String> extractionInput = new AtomicReference<>();
    volatile String proposals = "{\"updates\":[]}";
    volatile int writeError;
    HttpServer server;
    VortexMemoryClient client;

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/memory", this::handle);
        server.start();
        client = new VortexMemoryClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
    }

    @AfterEach void stop() { server.stop(0); }

    @Test void realChatClientRetrievesAndCommitsWithUppercaseStop() throws Exception {
        put(CITY, "北京", 1, "ACTIVE");
        proposals = updates(update(CITY, "上海", "搬到上海了", "set"));
        AtomicReference<String> answerInput = new AtomicReference<>();
        ChatModel answering = prompt -> {
            answerInput.set(prompt.getContents());
            return response("了解。", "STOP");
        };
        var result = ChatClient.builder(answering).defaultAdvisors(advisor()).build()
                .prompt().user("搬到上海了").call().chatClientResponse();
        assertTrue(answerInput.get().contains("北京"));
        assertEquals("上海", facts.get(CITY).path("content").asText());
        assertEquals(2, facts.get(CITY).path("revision").asInt());
        assertEquals(1, report(result).updates().size());
        assertTrue(report(result).memoryOk());
        assertFalse(extractionInput.get().contains("了解。"));
    }

    @Test void validatesEveryProposalBeforeWritingAnything() throws Exception {
        proposals = updates(update(CITY, "上海", "搬到上海了", "set"),
                update("someone.else", "拿铁", "喜欢拿铁", "set"));
        var result = call("搬到上海了，喜欢拿铁", "了解。", "stop");
        assertEquals("extraction", report(result).errors().getFirst().stage());
        assertTrue(writes.isEmpty());
    }

    @Test void rejectsEvidenceFromAssistantRatherThanUser() throws Exception {
        proposals = updates(update(CITY, "上海", "你住上海", "set"));
        var result = call("你猜我住哪", "你住上海", "stop");
        assertFalse(report(result).memoryOk());
        assertTrue(writes.isEmpty());
    }

    @Test void staleRevisionNeverOverwritesConcurrentUpdate() throws Exception {
        put(CITY, "北京", 1, "ACTIVE");
        proposals = updates(update(CITY, "上海", "搬到上海了", "set"));
        ChatModel answer = prompt -> {
            try { put(CITY, "深圳", 2, "ACTIVE"); } catch (Exception e) { throw new RuntimeException(e); }
            return response("了解", "stop");
        };
        var result = ChatClient.builder(answer).defaultAdvisors(advisor()).build()
                .prompt().user("搬到上海了").call().chatClientResponse();
        assertEquals("深圳", facts.get(CITY).path("content").asText());
        assertEquals("revision_conflict", report(result).errors().getFirst().code());
        assertEquals(1, writes.size());
        assertEquals(1, writes.getFirst().path("expectedRevision").asInt());
    }

    @Test void identicalFactsDoNotChurnRevisions() throws Exception {
        put(CITY, "上海", 4, "ACTIVE");
        proposals = updates(update(CITY, "上海", "住上海", "set"));
        var result = call("我住上海", "了解", "stop");
        assertTrue(report(result).updates().isEmpty());
        assertTrue(writes.isEmpty());
        assertEquals(4, facts.get(CITY).path("revision").asInt());
    }

    @Test void retractsAndRecreatesUsingTheStoredRevision() throws Exception {
        put(CITY, "上海", 2, "ACTIVE");
        proposals = updates(update(CITY, "", "住址不对", "retract"));
        call("住址不对", "了解", "stop");
        assertEquals("RETRACTED", facts.get(CITY).path("status").asText());
        assertTrue(client.recall("住哪", "test", 5, 2000).isEmpty());
        put(CITY, "", 8, "DELETED");
        proposals = updates(update(CITY, "上海", "住上海", "set"));
        call("我住上海", "了解", "stop");
        assertEquals(9, facts.get(CITY).path("revision").asInt());
    }

    @Test void reportsWriteFailureWithoutPretendingItCommitted() throws Exception {
        writeError = 503;
        proposals = updates(update(CITY, "上海", "住上海", "set"));
        var result = call("我住上海", "了解", "stop");
        assertEquals("了解", result.chatResponse().getResult().getOutput().getText());
        assertTrue(report(result).updates().isEmpty());
        assertEquals("http_503", report(result).errors().getFirst().code());
        assertEquals(1, writes.size());
    }

    @Test void failedOrTruncatedAnswerDoesNotLearn() {
        var result = call("我住上海", "半句回答", "LENGTH");
        assertEquals("incomplete_answer", report(result).errors().getFirst().code());
        assertEquals(0, extractions.get());
        ChatModel failing = prompt -> { throw new IllegalStateException("model unavailable"); };
        assertThrows(IllegalStateException.class, () -> ChatClient.builder(failing).defaultAdvisors(advisor()).build()
                .prompt().user("我住上海").call().content());
        assertEquals(0, extractions.get());
    }

    @Test void streamingLearnsOnceAfterCompletionAndEmitsSeparateReport() throws Exception {
        proposals = updates(update(CITY, "上海", "住上海", "set"));
        List<ChatClientResponse> chunks = advisor().adviseStream(request("我住上海"), streamChain(false))
                .collectList().block();
        assertNotNull(chunks);
        assertEquals(4, chunks.size());
        assertEquals("你", chunks.get(0).chatResponse().getResult().getOutput().getText());
        assertEquals("好", chunks.get(1).chatResponse().getResult().getOutput().getText());
        assertEquals(1, extractions.get());
        assertEquals(1, report(chunks.getLast()).updates().size());
        assertTrue(chunks.getLast().chatResponse().getResults().isEmpty());
    }

    @Test void cancelledOrErroredStreamDoesNotWrite() {
        advisor().adviseStream(request("我住上海"), streamChain(false)).take(1).collectList().block();
        assertEquals(0, extractions.get());
        assertThrows(IllegalStateException.class, () -> advisor().adviseStream(request("我住上海"), streamChain(true))
                .collectList().block());
        assertEquals(0, extractions.get());
        assertTrue(writes.isEmpty());
    }

    @Test void factsInOneNamespaceAreNotRetrievedFromAnother() throws Exception {
        client.remember("one", CITY, "北京", 0, List.of(), null);
        assertNull(client.getFact("two", CITY));
        assertTrue(client.recall("住哪", "two", 5, 2000).isEmpty());
    }

    private VortexMemoryAdvisor advisor() {
        ChatModel extractor = prompt -> {
            extractions.incrementAndGet();
            extractionInput.set(prompt.getContents());
            return response(proposals, "STOP");
        };
        return new VortexMemoryAdvisor(client, "test", new VortexFactLearner(client, extractor));
    }

    private ChatClientResponse call(String user, String answer, String reason) {
        return ChatClient.builder((ChatModel) prompt -> response(answer, reason)).defaultAdvisors(advisor()).build()
                .prompt().user(user).call().chatClientResponse();
    }

    private static ChatResponse response(String text, String reason) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text),
                ChatGenerationMetadata.builder().finishReason(reason).build())));
    }

    private static ChatClientRequest request(String user) {
        return ChatClientRequest.builder().prompt(new Prompt(user)).context(Map.of()).build();
    }

    private static VortexFactLearner.Report report(ChatClientResponse response) {
        return (VortexFactLearner.Report) response.context().get(VortexMemoryAdvisor.CONTEXT_MEMORY_REPORT);
    }

    private StreamAdvisorChain streamChain(boolean fail) {
        return new StreamAdvisorChain() {
            @Override public Flux<ChatClientResponse> nextStream(ChatClientRequest request) {
                Flux<ChatClientResponse> first = Flux.just(ChatClientResponse.builder().chatResponse(response("你", ""))
                        .context(request.context()).build());
                return fail ? first.concatWith(Flux.error(new IllegalStateException("broken stream")))
                        : first.concatWithValues(ChatClientResponse.builder().chatResponse(response("好", "STOP"))
                                .context(request.context()).build(),
                                ChatClientResponse.builder().chatResponse(new ChatResponse(List.of()))
                                        .context(request.context()).build()); // trailing usage-only event
            }
            @Override public List<StreamAdvisor> getStreamAdvisors() { return List.of(); }
            @Override public StreamAdvisorChain copy(StreamAdvisor advisor) { return this; }
        };
    }

    private Map<String, String> update(String key, String content, String evidence, String operation) {
        return Map.of("key", key, "content", content, "evidence", evidence, "operation", operation);
    }

    @SafeVarargs private final String updates(Map<String, String>... items) throws Exception {
        return json.writeValueAsString(Map.of("updates", List.of(items)));
    }

    private void put(String key, String content, int revision, String status) throws Exception {
        facts.put(key, json.readTree(json.writeValueAsString(Map.of("key", key, "content", content,
                "revision", revision, "status", status, "tags", List.of()))));
    }

    private void handle(HttpExchange exchange) throws IOException {
        Map<String, String> query = new HashMap<>();
        if (exchange.getRequestURI().getRawQuery() != null) for (String part : exchange.getRequestURI().getRawQuery().split("&")) {
            String[] pair = part.split("=", 2);
            query.put(pair[0], URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
        }
        JsonNode body = exchange.getRequestMethod().equals("GET") ? null : json.readTree(exchange.getRequestBody());
        String namespace = body == null ? query.get("namespace") : body.path("namespace").asText();
        String key = body == null ? query.get("key") : body.path("key").asText();
        String storageKey = "test".equals(namespace) ? key : namespace + "/" + key;
        String path = exchange.getRequestURI().getPath();
        if (path.endsWith("/recall")) {
            List<Object> hits = new ArrayList<>();
            facts.forEach((k, f) -> {
                boolean sameNamespace = "test".equals(namespace) ? !k.contains("/") : k.startsWith(namespace + "/");
                if (sameNamespace && "ACTIVE".equals(f.path("status").asText()))
                    hits.add(Map.of("fragment", Map.of("id", k, "content", f.path("content").asText()), "score", 1));
            });
            respond(exchange, 200, Map.of("fragments", hits));
        } else if (exchange.getRequestMethod().equals("GET")) {
            JsonNode fact = facts.get(storageKey);
            respond(exchange, fact == null ? 404 : 200, fact == null ? Map.of() : fact);
        } else {
            writes.add(body);
            if (writeError != 0) { respond(exchange, writeError, Map.of()); return; }
            long revision = facts.containsKey(storageKey) ? facts.get(storageKey).path("revision").asLong() : 0;
            if (body.path("expectedRevision").asLong() != revision) {
                respond(exchange, 409, Map.of("currentRevision", revision)); return;
            }
            JsonNode fact = json.valueToTree(Map.of("key", key, "content", body.path("content").asText(""),
                    "revision", revision + 1, "status", path.endsWith("/retract") ? "RETRACTED" : "ACTIVE", "tags", List.of()));
            facts.put(storageKey, fact);
            respond(exchange, 200, fact);
        }
    }

    private void respond(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = json.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
