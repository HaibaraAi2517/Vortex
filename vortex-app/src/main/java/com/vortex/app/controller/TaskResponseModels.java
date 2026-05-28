package com.vortex.app.controller;

import com.vortex.common.model.DagNode;
import com.vortex.common.model.TaskBranch;
import com.vortex.common.model.TaskState;

import java.time.Instant;
import java.util.List;
import java.util.Map;

final class TaskResponseModels {

    private TaskResponseModels() {
    }

    static TaskResponse from(TaskState state) {
        return new TaskResponse(
                state.getTaskId(),
                state.getDescription(),
                state.getStatus().name(),
                state.getCurrentNodeId(),
                state.getCurrentBranchId(),
                List.copyOf(state.getBranches()),
                Map.copyOf(state.getContext()),
                state.getNamespace(),
                state.getCreatedAt(),
                state.getLastCheckpointAt(),
                state.getLatestCheckpointId(),
                state.getFinalizationStatus().name(),
                state.getGraph().nodeCount(),
                state.getGraph().edgeCount());
    }

    static DagNodeResponse from(DagNode node) {
        return new DagNodeResponse(
                node.getNodeId(),
                node.getType().name(),
                node.getContent(),
                node.getResult(),
                node.getStatus().name(),
                node.getCreatedAt(),
                node.getExecutedAt(),
                node.getMetadata());
    }

    record TaskResponse(
            String taskId,
            String description,
            String status,
            String currentNodeId,
            String currentBranchId,
            List<TaskBranch> branches,
            Map<String, String> context,
            String namespace,
            Instant createdAt,
            Instant lastCheckpointAt,
            String latestCheckpointId,
            String finalizationStatus,
            int nodeCount,
            int edgeCount) {
    }

    record DagNodeResponse(
            String nodeId,
            String type,
            String content,
            String result,
            String status,
            Instant createdAt,
            Instant executedAt,
            Map<String, String> metadata) {
    }
}
