package com.vortex.common.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemoryFeedbackRequest {

    @NotBlank
    private String recallSessionId;

    /** Fragment IDs that were actually used in the final answer. */
    private List<String> usedFragmentIds;

    /** Whether the final answer is considered successful/accepted. */
    @Builder.Default
    private boolean answerAccepted = true;
}
