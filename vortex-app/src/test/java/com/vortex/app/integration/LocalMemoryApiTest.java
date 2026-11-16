package com.vortex.app.integration;

import com.vortex.app.VortexApplication;
import com.vortex.kernel.embedding.EmbeddingService;
import com.vortex.kernel.embedding.TokenCounter;
import com.vortex.storage.api.L1HotStore;
import com.vortex.storage.api.L2WarmStore;
import com.vortex.storage.api.L3ColdStore;
import com.vortex.storage.local.LuceneMemoryStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/** Real HTTP + real Lucene + existing kernel. Only the embedding model is deterministic. */
@SpringBootTest(classes = {VortexApplication.class, LocalMemoryApiTest.Embeddings.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.main.allow-bean-definition-overriding=true", "vortex.storage.l2.embedding-dim=3",
        "vortex.security.enabled=true", "vortex.security.bearer-token=local-test-token-0123456789abcdef0123456789",
        "vortex.security.namespace-patterns[0]=personal", "vortex.security.audit-enabled=false"})
@ActiveProfiles("local")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LocalMemoryApiTest {
    @TempDir static Path data;
    @Autowired TestRestTemplate http;
    @Autowired L1HotStore l1;
    @Autowired L2WarmStore l2;
    @Autowired L3ColdStore l3;

    @DynamicPropertySource
    static void paths(DynamicPropertyRegistry registry) {
        registry.add("vortex.storage.local.path", () -> data.resolve("index").toString());
        registry.add("vortex.kernel.persistence.dlq.path", () -> data.resolve("dlq.jsonl").toString());
        registry.add("vortex.kernel.persistence.processed-keys.path", () -> data.resolve("processed.txt").toString());
        registry.add("vortex.kernel.snapshot.wal.dir", () -> data.resolve("wal").toString());
        registry.add("vortex.kernel.learning.shadow-persistence-path", () -> data.resolve("shadow.json").toString());
    }

    @Test
    void defaultLocalBackendRequiresNoExternalServicesAndAppendIsDurableAtAcknowledgement() {
        assertThat(l2).isInstanceOf(LuceneMemoryStore.class).isSameAs(l3);
        var response = call(HttpMethod.POST, "/api/v1/memory/store", Map.of("namespace", "personal", "content", "我喜欢喝美式咖啡，不加糖"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String id = (String) ((List<?>) response.getBody().get("fragmentIds")).getFirst();
        assertThat(l2.get(id)).isPresent();
        l1.clear("personal");
        assertThat(contents(recall("用户喜欢什么咖啡", "KEYWORD_ONLY"))).contains("我喜欢喝美式咖啡，不加糖");
        assertThat(contents(recall("用户喜欢什么咖啡", "HYBRID"))).contains("我喜欢喝美式咖啡，不加糖");
    }

    @Test
    void updatesExcludeWarmL1AndRejectedWritesDoNotChangeCurrentFact() {
        var first = put("city", "我住北京", 0);
        String oldId = (String) first.getBody().get("fragmentId");
        recall("我住哪里", "VECTOR_ONLY"); // Cache the first revision in L1 before updating it.
        assertThat(l1.peek(oldId)).isPresent();
        assertThat(put("city", "我搬到上海了", 1).getStatusCode()).isEqualTo(HttpStatus.OK);
        var conflict = put("city", "旧请求写回北京", 1);
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflict.getBody()).containsEntry("currentRevision", 2);
        for (String mode : List.of("VECTOR_ONLY", "HYBRID", "KEYWORD_ONLY")) {
            var contents = contents(recall("现在住上海还是北京", mode));
            assertThat(contents).contains("我搬到上海了").doesNotContain("我住北京", "旧请求写回北京");
        }
        assertThat(call(HttpMethod.GET, "/api/v1/memory/fragment/" + oldId, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(HttpMethod.POST, "/api/v1/memory/facts/retract",
                Map.of("namespace", "personal", "key", "city", "expectedRevision", 2)).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(contents(recall("上海", "KEYWORD_ONLY"))).doesNotContain("我搬到上海了");
    }

    @Test
    void expiredFactsAreRemovedFromCachedRecallAndDeletedHistoryContainsNoContent() throws Exception {
        var response = call(HttpMethod.PUT, "/api/v1/memory/facts", Map.of("namespace", "personal", "key", "temporary",
                "content", "临时暗号星际咖啡", "expectedRevision", 0, "expiresAt", Instant.now().plusSeconds(2).toString()));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        recall("暗号", "KEYWORD_ONLY");
        Instant expires = Instant.parse((String) response.getBody().get("expiresAt"));
        Thread.sleep(Math.max(0, expires.toEpochMilli() - System.currentTimeMillis() + 25));
        assertThat(contents(recall("暗号", "KEYWORD_ONLY"))).doesNotContain("临时暗号星际咖啡");
        assertThat(call(HttpMethod.GET, "/api/v1/memory/facts?namespace=personal&key=temporary", null).getBody())
                .containsEntry("status", "EXPIRED");
        assertThat(call(HttpMethod.DELETE, "/api/v1/memory/facts?namespace=personal&key=temporary&expectedRevision=1", null).getStatusCode()).isEqualTo(HttpStatus.OK);
        var history = http.exchange("/api/v1/memory/facts/history?namespace=personal&key=temporary", HttpMethod.GET,
                entity(null), String.class);
        assertThat(history.getBody()).doesNotContain("临时暗号星际咖啡").contains("DELETED");
    }

    @Test
    void everyFactRouteEnforcesNamespaceAndInputValidation() {
        assertThat(http.getForEntity("/api/v1/memory/facts?namespace=personal&key=x", Map.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        for (String route : List.of("/api/v1/memory/facts?namespace=other&key=x", "/api/v1/memory/facts/history?namespace=other&key=x")) {
            assertThat(call(HttpMethod.GET, route, null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }
        assertThat(call(HttpMethod.PUT, "/api/v1/memory/facts", Map.of("namespace", "other", "key", "x", "content", "secret", "expectedRevision", 0))
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.POST, "/api/v1/memory/facts/retract", Map.of("namespace", "other", "key", "x", "expectedRevision", 1))
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.DELETE, "/api/v1/memory/facts?namespace=other&key=x&expectedRevision=1", null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.PUT, "/api/v1/memory/facts", Map.of("namespace", "personal", "key", "x", "content", "a"))
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(put("oversize", "文".repeat(481), 0).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(call(HttpMethod.GET, "/api/v1/memory/facts/history?namespace=personal&key=x&limit=101", null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void keywordOnlyDoesNotReturnUnrelatedL1Memories() {
        assertThat(contents(recall("无匹配词条夸克虫洞", "KEYWORD_ONLY"))).isEmpty();
    }

    @Test
    void workbenchShellIsPublicWhileCatalogRequiresTokenAndNamespaceAccess() {
        var page = http.getForEntity("/", String.class);
        assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(page.getBody()).contains("记忆工作台", "/workbench/app.js").doesNotContain("local-test-token");
        assertThat(http.getForEntity("/workbench/app.js", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(http.getForEntity("/workbench/app.css", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(http.getForEntity("/api/v1/memory/facts/list?namespace=personal", Map.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(call(HttpMethod.GET, "/api/v1/memory/facts/list?namespace=other", null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.GET, "/api/v1/memory/facts/list?namespace=personal&limit=101", null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(call(HttpMethod.GET, "/api/v1/memory/facts/list?namespace=personal&afterKey=" + "x".repeat(129), null).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void catalogReturnsExclusiveCursorAndDoesNotReturnDeletedFacts() {
        for (String key : List.of("catalog.a", "catalog.b", "catalog.c")) {
            assertThat(put(key, "目录事实 " + key, 0).getStatusCode()).isEqualTo(HttpStatus.OK);
        }
        var first = call(HttpMethod.GET, "/api/v1/memory/facts/list?namespace=personal&limit=2", null).getBody();
        assertThat(first).containsEntry("nextCursor", "catalog.b");
        assertThat((List<Map>) first.get("items")).extracting(item -> item.get("key")).containsExactly("catalog.a", "catalog.b");
        assertThat(put("catalog.b", "并发更新不改变 key 的分页顺序", 1).getStatusCode()).isEqualTo(HttpStatus.OK);
        var next = call(HttpMethod.GET, "/api/v1/memory/facts/list?namespace=personal&limit=1&afterKey=catalog.b", null).getBody();
        assertThat((List<Map>) next.get("items")).extracting(item -> item.get("key")).containsExactly("catalog.c");
        assertThat(call(HttpMethod.DELETE, "/api/v1/memory/facts?namespace=personal&key=catalog.c&expectedRevision=1", null).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        var after = call(HttpMethod.GET, "/api/v1/memory/facts/list?namespace=personal&includeInactive=true", null).getBody();
        assertThat((List<Map>) after.get("items")).extracting(item -> item.get("key")).doesNotContain("catalog.c");
    }

    private ResponseEntity<Map> put(String key, String content, int revision) {
        return call(HttpMethod.PUT, "/api/v1/memory/facts", Map.of("namespace", "personal", "key", key, "content", content, "expectedRevision", revision));
    }

    private Map recall(String query, String mode) {
        var response = call(HttpMethod.POST, "/api/v1/memory/recall", Map.of("namespace", "personal", "query", query,
                "retrievalMode", mode, "topK", 10, "tokenBudget", 2048));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private List<String> contents(Map result) {
        return ((List<Map>) result.get("fragments")).stream().map(hit -> (String) ((Map) hit.get("fragment")).get("content")).toList();
    }

    private ResponseEntity<Map> call(HttpMethod method, String path, Object body) {
        return http.exchange(path, method, entity(body), Map.class);
    }

    private HttpEntity<Object> entity(Object body) {
        var headers = new HttpHeaders();
        headers.setBearerAuth("local-test-token-0123456789abcdef0123456789");
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    @TestConfiguration
    static class Embeddings {
        @Bean("bgeSmallEmbeddingService") @Primary
        StubEmbedding embedding() { return new StubEmbedding(); }
    }

    static class StubEmbedding implements EmbeddingService, TokenCounter {
        public float[] embed(String text) { return new float[]{1, 0, 0}; }
        public int dimension() { return 3; }
        public int countTokens(String text) { return text.length(); }
    }
}
