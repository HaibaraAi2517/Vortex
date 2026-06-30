package com.vortex.common.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationMessage {

    private String messageId;
    private String role;
    private String content;

    @Builder.Default
    private Instant createdAt = Instant.now();

    @Builder.Default
    private Map<String, String> metadata = new HashMap<>();
}
