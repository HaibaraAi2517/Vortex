package com.vortex.common.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** Request DTO for a semantic memory recall operation. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecallQuery {

    @NotBlank
    private String query;

    /** Namespace to search within. */
    @NotBlank
    private String namespace;

    /** Maximum number of fragments to return. */
    @Builder.Default
    @Min(1)
    private int topK = 5;

    /** Maximum total token budget for the returned fragments. */
    @Builder.Default
    private int tokenBudget = 2048;

    /** Optional tag filters (AND semantics). */
    private List<String> tags;

    /** Scenario for adaptive scoring profile selection: coding/chat/search. */
    @Builder.Default
    private MemoryScenario scenario = MemoryScenario.CHAT;
}
