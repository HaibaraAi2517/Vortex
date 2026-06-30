package com.vortex.common.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationState {

    private String conversationId;
    private String title;

    @Builder.Default
    private List<ConversationMessage> messages = new ArrayList<>();

    @Builder.Default
    private Instant createdAt = Instant.now();

    @Builder.Default
    private Instant updatedAt = Instant.now();

    @Builder.Default
    private Map<String, String> metadata = new HashMap<>();

    public void appendMessage(ConversationMessage message) {
        messages.add(message);
        updatedAt = message.getCreatedAt() != null ? message.getCreatedAt() : Instant.now();
    }
}
