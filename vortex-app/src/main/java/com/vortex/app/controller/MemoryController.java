package com.vortex.app.controller;

import com.vortex.common.dto.MemoryFeedbackRequest;
import com.vortex.common.dto.MemoryScenario;
import com.vortex.common.dto.RecallQuery;
import com.vortex.common.dto.RecallResult;
import com.vortex.common.model.MemoryFragment;
import com.vortex.app.health.MemoryHealthSignalCatalog;
import com.vortex.app.health.MemorySloHealthIndicator;
import com.vortex.kernel.hmc.AsyncMemoryPipeline;
import com.vortex.kernel.hmc.HierarchicalMemoryController;
import com.vortex.kernel.hmc.MemoryPipelineRequest;
import com.vortex.kernel.hmc.MemoryPipelineStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/memory")
@RequiredArgsConstructor
public class MemoryController {

    private final HierarchicalMemoryController hmc;
    private final AsyncMemoryPipeline asyncMemoryPipeline;
    private final MemorySloHealthIndicator memorySloHealthIndicator;

    /**
     * Store raw text into the memory hierarchy.
     *
     * POST /api/v1/memory/store
     * {
     *   "content": "...",
     *   "namespace": "session-abc",
     *   "tags": ["role:user"]
     * }
     */
    @PostMapping("/store")
    public ResponseEntity<Map<String, Object>> store(@Valid @RequestBody StoreRequest req) {
        List<String> ids = hmc.store(req.content(), req.namespace(), req.tags(), req.reasoningChainId(), req.pinTtlMillis());
        return ResponseEntity.ok(Map.of(
                "fragmentIds", ids,
                "count", ids.size()
        ));
    }

    @PostMapping("/store/async")
    public ResponseEntity<MemoryPipelineStatus> storeAsync(@Valid @RequestBody StoreRequest req) {
        MemoryPipelineStatus status = asyncMemoryPipeline.submit(MemoryPipelineRequest.builder()
                .content(req.content())
                .namespace(req.namespace())
                .tags(req.tags())
                .reasoningChainId(req.reasoningChainId())
                .pinTtlMillis(req.pinTtlMillis())
                .build());
        return ResponseEntity.accepted().body(status);
    }

    @GetMapping("/pipeline/{pipelineId}")
    public ResponseEntity<MemoryPipelineStatus> pipelineStatus(@PathVariable("pipelineId") String pipelineId) {
        return asyncMemoryPipeline.snapshot(pipelineId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Store a pre-built fragment (with optional embedding).
     *
     * POST /api/v1/memory/store/fragment
     */
    @PostMapping("/store/fragment")
    public ResponseEntity<Map<String, String>> storeFragment(@RequestBody MemoryFragment fragment) {
        hmc.storeFragment(fragment);
        return ResponseEntity.ok(Map.of("id", fragment.getId()));
    }

    @GetMapping("/fragment/{fragmentId}")
    public ResponseEntity<MemoryResponseModels.MemoryFragmentResponse> getFragment(@PathVariable("fragmentId") String fragmentId) {
        return hmc.getFragment(fragmentId)
                .map(MemoryResponseModels::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/fragment/{fragmentId}")
    public ResponseEntity<Map<String, String>> deleteFragment(@PathVariable("fragmentId") String fragmentId) {
        if (!hmc.deleteFragment(fragmentId)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("fragmentId", fragmentId, "status", "DELETED"));
    }

    /**
     * Recall semantically relevant fragments.
     *
     * POST /api/v1/memory/recall
     * {
     *   "query": "...",
     *   "namespace": "session-abc",
     *   "topK": 5,
     *   "tokenBudget": 2048
     * }
     */
    @PostMapping("/recall")
    public ResponseEntity<RecallResult> recall(@Valid @RequestBody RecallQuery query) {
        return ResponseEntity.ok(hmc.recall(query));
    }

    @PostMapping("/feedback")
    public ResponseEntity<Map<String, String>> feedback(@Valid @RequestBody MemoryFeedbackRequest request) {
        hmc.recordFeedback(request);
        return ResponseEntity.ok(Map.of("status", "accepted", "recallSessionId", request.getRecallSessionId()));
    }

    @PostMapping("/pin")
    public ResponseEntity<Map<String, Object>> pin(@RequestBody PinRequest request) {
        return hmc.pinFragment(request.fragmentId(), request.pinTtlMillis())
                .<ResponseEntity<Map<String, Object>>>map(fragment -> ResponseEntity.ok(Map.<String, Object>of(
                        "status", "pinned",
                        "fragmentId", fragment.getId(),
                        "pinnedUntil", fragment.getPinnedUntil())))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/unpin")
    public ResponseEntity<Map<String, Object>> unpin(@RequestBody FragmentRefRequest request) {
        return hmc.unpinFragment(request.fragmentId())
                .<ResponseEntity<Map<String, Object>>>map(fragment -> ResponseEntity.ok(Map.<String, Object>of(
                        "status", "unpinned",
                        "fragmentId", fragment.getId())))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Health probe for the memory subsystem.
     * GET /api/v1/memory/health
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Health health = memorySloHealthIndicator.health();
        Object summary = health.getDetails().get("summary");
        Object statusReason = null;
        if (summary instanceof List<?> summaryList && !summaryList.isEmpty()) {
            statusReason = summaryList.getFirst();
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", health.getStatus().getCode());
        payload.put("dictionaryVersion", health.getDetails().get("dictionaryVersion"));
        payload.put("summary", summary);
        payload.put("statusReason", statusReason);
        payload.put("l1TokensUsed", hmc.getL1().currentTokenCount());
        payload.put("l1TokensMax", hmc.getL1().maxTokenCapacity());
        payload.put("details", health.getDetails());
        return ResponseEntity.status(httpStatusFor(health.getStatus())).body(payload);
    }

    @GetMapping("/learning")
    public ResponseEntity<Object> learning(@RequestParam(defaultValue = "chat") String scenario) {
        return ResponseEntity.ok(hmc.learningSnapshot(MemoryScenario.fromNullable(scenario)));
    }

    @GetMapping("/slo")
    public ResponseEntity<Object> slo() {
        return ResponseEntity.ok(hmc.sloSnapshot());
    }

    @GetMapping("/slo/report")
    public ResponseEntity<Object> sloReport() {
        return ResponseEntity.ok(hmc.diagnosticsSnapshot());
    }

    @GetMapping("/health/catalog")
    public ResponseEntity<Map<String, Object>> healthCatalog() {
        return ResponseEntity.ok(Map.of(
                "dictionaryVersion", MemoryHealthSignalCatalog.DICTIONARY_VERSION,
                "migrationGuide", MemoryHealthSignalCatalog.MIGRATION_GUIDE_PATH,
                "compatibility", MemoryHealthSignalCatalog.compatibilityNotes(),
                "signals", MemoryHealthSignalCatalog.catalog()));
    }

    public record StoreRequest(
            @NotBlank @Size(max = 20_000) String content,
            @NotBlank @Size(max = 128) String namespace,
            List<String> tags,
            @Size(max = 128) String reasoningChainId,
            @Positive Long pinTtlMillis) {}

    public record PinRequest(
            String fragmentId,
            long pinTtlMillis) {}

    public record FragmentRefRequest(
            String fragmentId) {}

    private HttpStatus httpStatusFor(Status status) {
        return Status.UP.equals(status) || MemorySloHealthIndicator.DEGRADED.equals(status)
                ? HttpStatus.OK
                : HttpStatus.SERVICE_UNAVAILABLE;
    }
}
