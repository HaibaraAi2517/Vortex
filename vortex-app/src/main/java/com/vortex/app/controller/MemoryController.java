package com.vortex.app.controller;

import com.vortex.common.dto.MemoryFeedbackRequest;
import com.vortex.common.dto.MemoryScenario;
import com.vortex.common.dto.RecallQuery;
import com.vortex.common.dto.RecallResult;
import com.vortex.common.model.MemoryFragment;
import com.vortex.kernel.hmc.HierarchicalMemoryController;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/memory")
@RequiredArgsConstructor
public class MemoryController {

    private final HierarchicalMemoryController hmc;

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
    public ResponseEntity<Map<String, Object>> store(@RequestBody StoreRequest req) {
        List<String> ids = hmc.store(req.content(), req.namespace(), req.tags(), req.reasoningChainId(), req.pinTtlMillis());
        return ResponseEntity.ok(Map.of(
                "fragmentIds", ids,
                "count", ids.size()
        ));
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
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "l1TokensUsed", hmc.getL1().currentTokenCount(),
                "l1TokensMax", hmc.getL1().maxTokenCapacity()
        ));
    }

    @GetMapping("/learning")
    public ResponseEntity<Object> learning(@RequestParam(defaultValue = "chat") String scenario) {
        return ResponseEntity.ok(hmc.learningSnapshot(MemoryScenario.fromNullable(scenario)));
    }

    @GetMapping("/slo")
    public ResponseEntity<Object> slo() {
        return ResponseEntity.ok(hmc.sloSnapshot());
    }

    public record StoreRequest(
            String content,
            String namespace,
            List<String> tags,
            String reasoningChainId,
            Long pinTtlMillis) {}

    public record PinRequest(
            String fragmentId,
            long pinTtlMillis) {}

    public record FragmentRefRequest(
            String fragmentId) {}
}
