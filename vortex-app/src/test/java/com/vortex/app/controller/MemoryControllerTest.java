package com.vortex.app.controller;

import com.vortex.app.health.MemorySloHealthIndicator;
import com.vortex.app.security.NamespaceAuthorizationService;
import com.vortex.app.security.VortexSecurityProperties;
import com.vortex.common.model.MemoryFragment;
import com.vortex.kernel.hmc.AsyncMemoryPipeline;
import com.vortex.kernel.hmc.HierarchicalMemoryController;
import com.vortex.kernel.hmc.MemoryPipelineRequest;
import com.vortex.kernel.hmc.MemoryPipelineStatus;
import com.vortex.kernel.hmc.MemoryPipelineStatusCode;
import com.vortex.storage.api.L1HotStore;
import com.vortex.storage.api.CheckpointStoreException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MemoryController.class)
@AutoConfigureMockMvc(addFilters = false)
class MemoryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private HierarchicalMemoryController hmc;

    @MockBean
    private AsyncMemoryPipeline asyncMemoryPipeline;

    @MockBean
    private MemorySloHealthIndicator memorySloHealthIndicator;

    @MockBean
    private L1HotStore l1HotStore;

    @MockBean
    private NamespaceAuthorizationService namespaceAuthorization;

    @MockBean
    private VortexSecurityProperties securityProperties;

    @Test
    void healthReturnsOkWhenIndicatorIsUp() throws Exception {
        when(hmc.getL1()).thenReturn(l1HotStore);
        when(l1HotStore.currentTokenCount()).thenReturn(128L);
        when(l1HotStore.maxTokenCapacity()).thenReturn(512L);
        when(memorySloHealthIndicator.health()).thenReturn(Health.up()
                .withDetail("dictionaryVersion", "memory-health-v2")
                .withDetail("summary", List.of(Map.of(
                        "severity", "info",
                        "code", "healthy",
                        "message", "Memory healthy",
                        "runbook", "ops/runbooks/memory-health-signals.md#healthy")))
                .withDetail("diagnosticWarnings", List.of())
                .withDetail("prefetchStrategies", List.of())
                .withDetail("regretModes", List.of())
                .build());

        mockMvc.perform(get("/api/v1/memory/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.dictionaryVersion").value("memory-health-v2"))
                .andExpect(jsonPath("$.summary[0].code").value("healthy"))
                .andExpect(jsonPath("$.statusReason.code").value("healthy"))
                .andExpect(jsonPath("$.statusReason.runbook").value("ops/runbooks/memory-health-signals.md#healthy"))
                .andExpect(jsonPath("$.l1TokensUsed").value(128))
                .andExpect(jsonPath("$.l1TokensMax").value(512))
                .andExpect(jsonPath("$.details.diagnosticWarnings").isArray());
    }

    @Test
    void healthReturnsServiceUnavailableWhenIndicatorIsDown() throws Exception {
        when(hmc.getL1()).thenReturn(l1HotStore);
        when(l1HotStore.currentTokenCount()).thenReturn(480L);
        when(l1HotStore.maxTokenCapacity()).thenReturn(512L);
        when(memorySloHealthIndicator.health()).thenReturn(Health.down()
                .withDetail("dictionaryVersion", "memory-health-v2")
                .withDetail("summary", List.of(Map.of(
                        "severity", "critical",
                        "code", "eviction_regret_high",
                        "alertName", "VortexMemoryEvictionRegretHigh",
                        "message", "Eviction regret is high",
                        "runbook", "ops/runbooks/memory-health-signals.md#eviction_regret_high")))
                .withDetail("diagnosticWarnings", List.of("prefetch strategy semantic-nbhd degraded"))
                .withDetail("regretModes", List.of(Map.of("mode", "semantic", "regretRate", 0.2)))
                .build());

        mockMvc.perform(get("/api/v1/memory/health"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(jsonPath("$.statusReason.code").value("eviction_regret_high"))
                .andExpect(jsonPath("$.statusReason.runbook").value("ops/runbooks/memory-health-signals.md#eviction_regret_high"))
                .andExpect(jsonPath("$.details.diagnosticWarnings[0]").value("prefetch strategy semantic-nbhd degraded"));
    }

    @Test
    void healthCatalogExposesDictionaryMetadata() throws Exception {
        mockMvc.perform(get("/api/v1/memory/health/catalog"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dictionaryVersion").value("memory-health-v2"))
                .andExpect(jsonPath("$.migrationGuide").value("ops/runbooks/memory-health-migration.md"))
                .andExpect(jsonPath("$.compatibility[0].deprecatedKey").value("recovery_success_rate_low"))
                .andExpect(jsonPath("$.compatibility[0].replacementKey").value("checkpoint_recovery_success_rate_low"))
                .andExpect(jsonPath("$.signals[0].code").value("namespace_isolation_violation"))
                .andExpect(jsonPath("$.signals[0].runbook").value("ops/runbooks/memory-health-signals.md#namespace_isolation_violation"));
    }

    @Test
    void getFragmentReturnsFragmentWhenPresent() throws Exception {
        MemoryFragment fragment = MemoryFragment.builder()
                .id("frag-1")
                .namespace("ns")
                .content("payload")
                .tokenCount(3)
                .build();
        when(hmc.getFragment("frag-1")).thenReturn(Optional.of(fragment));

        mockMvc.perform(get("/api/v1/memory/fragment/frag-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("frag-1"))
                .andExpect(jsonPath("$.namespace").value("ns"))
                .andExpect(jsonPath("$.content").value("payload"));
    }

    @Test
    void deleteFragmentReturnsDeletedStatusWhenPresent() throws Exception {
        when(hmc.getFragment("frag-1")).thenReturn(Optional.of(MemoryFragment.builder()
                .id("frag-1")
                .namespace("ns")
                .build()));
        when(hmc.deleteFragment("frag-1")).thenReturn(true);

        mockMvc.perform(delete("/api/v1/memory/fragment/frag-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fragmentId").value("frag-1"))
                .andExpect(jsonPath("$.status").value("DELETED"));
    }

    @Test
    void deleteFragmentReturnsServerErrorWhenColdStoreDeletionFails() throws Exception {
        when(hmc.getFragment("frag-1")).thenReturn(Optional.of(MemoryFragment.builder()
                .id("frag-1")
                .namespace("ns")
                .build()));
        when(hmc.deleteFragment("frag-1")).thenThrow(new CheckpointStoreException(
                CheckpointStoreException.FailureType.DELETE_FAILED,
                "MinIO delete failed for key fragments/frag-1.json",
                new IllegalStateException("simulated delete failure")));

        mockMvc.perform(delete("/api/v1/memory/fragment/frag-1"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("INTERNAL_SERVER_ERROR"))
                .andExpect(jsonPath("$.detail").value("Internal server error"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    void storeRejectsNullContentAsBadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/memory/store")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":null,"namespace":"ns"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.content").exists());
    }

    @Test
    void storeAcceptsValidRequest() throws Exception {
        when(hmc.store(anyString(), anyString(), any(), any(), any()))
                .thenReturn(List.of("frag-1", "frag-2"));

        mockMvc.perform(post("/api/v1/memory/store")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"hello","namespace":"ns","pinTtlMillis":1000}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(2))
                .andExpect(jsonPath("$.fragmentIds[0]").value("frag-1"));
    }

    @Test
    void storeRejectsOversizedContentBeforeEnteringMemoryPipeline() throws Exception {
        mockMvc.perform(post("/api/v1/memory/store")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"%s","namespace":"ns"}
                                """.formatted("x".repeat(20_001))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.content").exists());

        verifyNoInteractions(hmc);
    }

    @Test
    void recallRejectsTopKAboveLimitBeforeEnteringMemoryPipeline() throws Exception {
        mockMvc.perform(post("/api/v1/memory/recall")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                {"query":"hello","namespace":"ns","topK":101,"tokenBudget":512}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.topK").exists());

        verifyNoInteractions(hmc);
    }

    @Test
    void recallRejectsInvalidEnumWithStableError() throws Exception {
        mockMvc.perform(post("/api/v1/memory/recall")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                {"query":"hello","namespace":"ns","retrievalMode":"INVALID"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.message").value("Request body is malformed or contains invalid enum values"));

        verifyNoInteractions(hmc);
    }

    @Test
    void storeAsyncAcceptsValidRequestAndReturnsPipelineStatus() throws Exception {
        when(asyncMemoryPipeline.submit(any(MemoryPipelineRequest.class))).thenReturn(MemoryPipelineStatus.builder()
                .pipelineId("pipeline-1")
                .namespace("ns")
                .status(MemoryPipelineStatusCode.ACCEPTED)
                .build());

        mockMvc.perform(post("/api/v1/memory/store/async")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"hello","namespace":"ns","reasoningChainId":"chain-1","pinTtlMillis":1000}
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.pipelineId").value("pipeline-1"))
                .andExpect(jsonPath("$.namespace").value("ns"))
                .andExpect(jsonPath("$.status").value("ACCEPTED"));
    }

    @Test
    void pipelineStatusReturnsCurrentStatusWhenPresent() throws Exception {
        when(asyncMemoryPipeline.snapshot("pipeline-1")).thenReturn(Optional.of(MemoryPipelineStatus.builder()
                .pipelineId("pipeline-1")
                .namespace("ns")
                .status(MemoryPipelineStatusCode.COMPLETED)
                .fragmentIds(List.of("frag-1"))
                .build()));

        mockMvc.perform(get("/api/v1/memory/pipeline/pipeline-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pipelineId").value("pipeline-1"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.fragmentIds[0]").value("frag-1"));
    }

    @Test
    void pipelineStatusReturnsNotFoundWhenMissing() throws Exception {
        when(asyncMemoryPipeline.snapshot("missing")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/memory/pipeline/missing"))
                .andExpect(status().isNotFound());
    }
}
