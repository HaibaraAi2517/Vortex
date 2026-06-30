package com.vortex.kernel.snapshot;

import com.vortex.common.model.ActionLogEntry;
import com.vortex.common.model.ConversationMessage;
import com.vortex.common.model.ConversationState;
import com.vortex.common.model.LlmCallState;
import com.vortex.common.model.TaskState;
import com.vortex.common.model.ToolExecutionState;
import com.vortex.common.serialization.WalPayloads;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
public class RuntimeMutationService {

    private final ActionLogWriter walWriter;
    private final DirtySetTracker dirtySetTracker;
    private final CheckpointScheduler scheduler;
    private final TaskLifecycleManager taskLifecycleManager;

    public RuntimeMutationService(
            ActionLogWriter walWriter,
            DirtySetTracker dirtySetTracker,
            CheckpointScheduler scheduler,
            TaskLifecycleManager taskLifecycleManager) {
        this.walWriter = walWriter;
        this.dirtySetTracker = dirtySetTracker;
        this.scheduler = scheduler;
        this.taskLifecycleManager = taskLifecycleManager;
    }

    public ConversationMessage appendConversationMessage(
            String taskId,
            String conversationId,
            String role,
            String content) {
        TaskState state = requireTask(taskId);
        String resolvedConversationId = requireNonBlank(conversationId, "conversationId");
        String messageId = UUID.randomUUID().toString();
        ActionLogEntry entry = walWriter.append(taskId,
                ActionLogEntry.OperationType.APPEND_CONVERSATION_MESSAGE,
                jsonPayload(
                        "conversationId", resolvedConversationId,
                        "messageId", messageId,
                        "role", role,
                        "content", content));

        ConversationMessage message = buildConversationMessage(messageId, role, content, entry.getTimestamp());
        appendConversationMessage(state, resolvedConversationId, message);
        markMutation(state, entry, () -> dirtySetTracker.markConversationDirty(taskId, resolvedConversationId));
        return message;
    }

    public ToolExecutionState startToolExecution(String taskId, String executionId, String toolName, String input) {
        TaskState state = requireTask(taskId);
        String resolvedExecutionId = requireNonBlank(executionId, "executionId");
        ActionLogEntry entry = walWriter.append(taskId,
                ActionLogEntry.OperationType.START_TOOL_EXECUTION,
                jsonPayload(
                        "executionId", resolvedExecutionId,
                        "toolName", toolName,
                        "input", input));

        ToolExecutionState toolExecution = buildToolExecution(
                resolvedExecutionId, toolName, input, entry.getTimestamp());
        state.getToolExecutions().put(resolvedExecutionId, toolExecution);
        markMutation(state, entry, () -> dirtySetTracker.markToolExecutionDirty(taskId, resolvedExecutionId));
        return toolExecution;
    }

    public ToolExecutionState completeToolExecution(String taskId, String executionId, String output) {
        TaskState state = requireTask(taskId);
        String resolvedExecutionId = requireNonBlank(executionId, "executionId");
        ActionLogEntry entry = walWriter.append(taskId,
                ActionLogEntry.OperationType.COMPLETE_TOOL_EXECUTION,
                jsonPayload("executionId", resolvedExecutionId, "output", output));

        ToolExecutionState toolExecution = requireToolExecution(state, resolvedExecutionId);
        toolExecution.succeed(output, entry.getTimestamp());
        markMutation(state, entry, () -> dirtySetTracker.markToolExecutionDirty(taskId, resolvedExecutionId));
        return toolExecution;
    }

    public ToolExecutionState failToolExecution(String taskId, String executionId, String errorMessage) {
        TaskState state = requireTask(taskId);
        String resolvedExecutionId = requireNonBlank(executionId, "executionId");
        ActionLogEntry entry = walWriter.append(taskId,
                ActionLogEntry.OperationType.FAIL_TOOL_EXECUTION,
                jsonPayload("executionId", resolvedExecutionId, "errorMessage", errorMessage));

        ToolExecutionState toolExecution = requireToolExecution(state, resolvedExecutionId);
        toolExecution.fail(errorMessage, entry.getTimestamp());
        markMutation(state, entry, () -> dirtySetTracker.markToolExecutionDirty(taskId, resolvedExecutionId));
        return toolExecution;
    }

    public LlmCallState startLlmCall(
            String taskId,
            String callId,
            String provider,
            String model,
            String prompt,
            long timeoutMillis) {
        TaskState state = requireTask(taskId);
        String resolvedCallId = requireNonBlank(callId, "callId");
        ActionLogEntry entry = walWriter.append(taskId,
                ActionLogEntry.OperationType.START_LLM_CALL,
                jsonPayload(
                        "callId", resolvedCallId,
                        "provider", provider,
                        "model", model,
                        "prompt", prompt,
                        "timeoutMillis", String.valueOf(timeoutMillis)));

        LlmCallState llmCall = buildLlmCall(
                resolvedCallId, provider, model, prompt, timeoutMillis, entry.getTimestamp());
        state.getLlmCalls().put(resolvedCallId, llmCall);
        markMutation(state, entry, () -> dirtySetTracker.markLlmCallDirty(taskId, resolvedCallId));
        return llmCall;
    }

    public LlmCallState completeLlmCall(String taskId, String callId, String response) {
        TaskState state = requireTask(taskId);
        String resolvedCallId = requireNonBlank(callId, "callId");
        ActionLogEntry entry = walWriter.append(taskId,
                ActionLogEntry.OperationType.COMPLETE_LLM_CALL,
                jsonPayload("callId", resolvedCallId, "response", response));

        LlmCallState llmCall = requireLlmCall(state, resolvedCallId);
        llmCall.complete(response, entry.getTimestamp());
        markMutation(state, entry, () -> dirtySetTracker.markLlmCallDirty(taskId, resolvedCallId));
        return llmCall;
    }

    public LlmCallState timeoutLlmCall(String taskId, String callId, String errorMessage) {
        TaskState state = requireTask(taskId);
        String resolvedCallId = requireNonBlank(callId, "callId");
        ActionLogEntry entry = walWriter.append(taskId,
                ActionLogEntry.OperationType.TIMEOUT_LLM_CALL,
                jsonPayload("callId", resolvedCallId, "errorMessage", errorMessage));

        LlmCallState llmCall = requireLlmCall(state, resolvedCallId);
        llmCall.timeout(errorMessage, entry.getTimestamp());
        markMutation(state, entry, () -> dirtySetTracker.markLlmCallDirty(taskId, resolvedCallId));
        return llmCall;
    }

    public LlmCallState markLlmCallRetry(String taskId, String callId) {
        TaskState state = requireTask(taskId);
        String resolvedCallId = requireNonBlank(callId, "callId");
        ActionLogEntry entry = walWriter.append(taskId,
                ActionLogEntry.OperationType.MARK_LLM_CALL_RETRY,
                jsonPayload("callId", resolvedCallId));

        LlmCallState llmCall = requireLlmCall(state, resolvedCallId);
        llmCall.markRetryPending();
        markMutation(state, entry, () -> dirtySetTracker.markLlmCallDirty(taskId, resolvedCallId));
        return llmCall;
    }

    private TaskState requireTask(String taskId) {
        return taskLifecycleManager.getTask(taskId)
                .orElseThrow(() -> new TaskNotFoundException(taskId));
    }

    private ToolExecutionState requireToolExecution(TaskState state, String executionId) {
        ToolExecutionState toolExecution = state.getToolExecutions().get(executionId);
        if (toolExecution == null) {
            throw new IllegalArgumentException("Tool execution not found: " + executionId);
        }
        return toolExecution;
    }

    private LlmCallState requireLlmCall(TaskState state, String callId) {
        LlmCallState llmCall = state.getLlmCalls().get(callId);
        if (llmCall == null) {
            throw new IllegalArgumentException("LLM call not found: " + callId);
        }
        return llmCall;
    }

    private void markMutation(TaskState state, ActionLogEntry entry, Runnable dirtyMarker) {
        state.setWalSequenceNumber(entry.getSequenceNumber());
        dirtyMarker.run();
        scheduler.recordAction(state.getTaskId());
    }

    static void appendConversationMessage(TaskState state, String conversationId, ConversationMessage message) {
        ConversationState conversation = state.getConversations().computeIfAbsent(conversationId,
                id -> ConversationState.builder()
                        .conversationId(id)
                        .title(id)
                        .createdAt(message.getCreatedAt())
                        .updatedAt(message.getCreatedAt())
                        .build());
        conversation.appendMessage(message);
    }

    static ConversationMessage buildConversationMessage(
            String messageId,
            String role,
            String content,
            Instant createdAt) {
        return ConversationMessage.builder()
                .messageId(messageId)
                .role(role)
                .content(content)
                .createdAt(createdAt != null ? createdAt : Instant.now())
                .build();
    }

    static ToolExecutionState buildToolExecution(
            String executionId,
            String toolName,
            String input,
            Instant startedAt) {
        return ToolExecutionState.builder()
                .executionId(executionId)
                .toolName(toolName)
                .input(input)
                .startedAt(startedAt != null ? startedAt : Instant.now())
                .build();
    }

    static LlmCallState buildLlmCall(
            String callId,
            String provider,
            String model,
            String prompt,
            long timeoutMillis,
            Instant startedAt) {
        return LlmCallState.builder()
                .callId(callId)
                .provider(provider)
                .model(model)
                .prompt(prompt)
                .timeoutMillis(timeoutMillis)
                .startedAt(startedAt != null ? startedAt : Instant.now())
                .build();
    }

    static long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }
        return Long.parseLong(value);
    }

    private String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }

    private String jsonPayload(String... keyValues) {
        return WalPayloads.jsonPayload(keyValues);
    }
}
