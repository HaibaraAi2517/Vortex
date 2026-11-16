package com.vortex.app.controller;

import com.vortex.app.security.NamespaceAuthorizationService;
import com.vortex.kernel.hmc.FactMemoryService;
import com.vortex.storage.api.FactMemoryStore;
import com.vortex.storage.api.MemoryFact;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/v1/memory/facts")
@Validated
@RequiredArgsConstructor
@ConditionalOnProperty(name = "vortex.storage.backend", havingValue = "local")
public class FactMemoryController {
    private final FactMemoryService service;
    private final FactMemoryStore store;
    private final NamespaceAuthorizationService authorization;

    @PutMapping
    public FactResponse put(@Valid @RequestBody PutFactRequest request) {
        authorization.requireAccess(request.namespace());
        return FactResponse.from(service.put(request.namespace(), request.key(), request.content(), request.tags(),
                request.expectedRevision(), request.expiresAt()));
    }

    @GetMapping
    public ResponseEntity<FactResponse> get(@RequestParam("namespace") @NotBlank @Size(max = 128) String namespace,
            @RequestParam("key") @NotBlank @Size(max = 128) String key) {
        authorization.requireAccess(namespace);
        return store.getFact(namespace, key).map(FactResponse::from).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/history")
    public List<FactResponse> history(@RequestParam("namespace") @NotBlank @Size(max = 128) String namespace,
            @RequestParam("key") @NotBlank @Size(max = 128) String key,
            @RequestParam(name = "limit", defaultValue = "20") @Min(1) @Max(100) int limit,
            @RequestParam(name = "beforeRevision", defaultValue = "9223372036854775807") @Min(1) long beforeRevision) {
        authorization.requireAccess(namespace);
        return store.factHistory(namespace, key, beforeRevision, limit).stream().map(FactResponse::from).toList();
    }

    @GetMapping("/list")
    public FactPage list(@RequestParam("namespace") @NotBlank @Size(max = 128) String namespace,
            @RequestParam(name = "afterKey", required = false) @Size(max = 128) String afterKey,
            @RequestParam(name = "limit", defaultValue = "30") @Min(1) @Max(100) int limit,
            @RequestParam(name = "includeInactive", defaultValue = "false") boolean includeInactive) {
        authorization.requireAccess(namespace);
        List<MemoryFact> page = store.listFacts(namespace, afterKey, limit + 1, includeInactive);
        List<FactResponse> items = page.stream().limit(limit).map(FactResponse::from).toList();
        return new FactPage(items, page.size() > limit ? items.getLast().key() : null);
    }

    public record FactPage(List<FactResponse> items, String nextCursor) {}

    @PostMapping("/retract")
    public FactResponse retract(@Valid @RequestBody FactRevision request) {
        authorization.requireAccess(request.namespace());
        return FactResponse.from(store.retractFact(request.namespace(), request.key(), request.expectedRevision()));
    }

    @DeleteMapping
    public FactResponse delete(@RequestParam("namespace") @NotBlank @Size(max = 128) String namespace,
            @RequestParam("key") @NotBlank @Size(max = 128) String key,
            @RequestParam("expectedRevision") @Min(1) long expectedRevision) {
        authorization.requireAccess(namespace);
        return FactResponse.from(store.deleteFact(namespace, key, expectedRevision));
    }

    public record PutFactRequest(
            @NotBlank @Size(max = 128) String namespace,
            @NotBlank @Size(max = 128) String key,
            @NotBlank @Size(max = 2000) String content,
            @Size(max = 32) List<@NotBlank @Size(max = 128) String> tags,
            @NotNull @Min(0) Long expectedRevision,
            @Future Instant expiresAt) {}

    public record FactRevision(@NotBlank @Size(max = 128) String namespace,
            @NotBlank @Size(max = 128) String key, @NotNull @Min(1) Long expectedRevision) {}

    public record FactResponse(String namespace, String key, long revision, String fragmentId,
            String content, List<String> tags, String status, Instant createdAt, Instant updatedAt, Instant expiresAt) {
        static FactResponse from(MemoryFact fact) {
            String status = fact.state() == MemoryFact.State.ACTIVE && !fact.activeAt(Instant.now())
                    ? "EXPIRED" : fact.state().name();
            return new FactResponse(fact.namespace(), fact.key(), fact.revision(), fact.fragmentId(), fact.content(),
                    fact.tags(), status, fact.createdAt(), fact.updatedAt(), fact.expiresAt());
        }
    }
}
