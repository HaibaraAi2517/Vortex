package com.vortex.common.serialization;

import com.fasterxml.jackson.databind.JsonNode;
import com.vortex.common.model.DagGraph;
import com.vortex.common.model.TaskState;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * Bridge for loading old Jackson-serialized TaskState checkpoints
 * and migrating them to the new DagGraph-based format.
 *
 * Auto-detects format: if data starts with '{' it's Jackson JSON;
 * otherwise it's Kryo binary.
 */
@Slf4j
public class JacksonCompatibilityBridge {

    /**
     * Detect if the byte array is in Jackson JSON format.
     */
    public static boolean isJacksonFormat(byte[] data) {
        if (data == null || data.length == 0) return false;
        return data[0] == '{' || data[0] == '[';
    }

    /**
     * Load a TaskState from old Jackson JSON bytes, migrating flat ThoughtNode list to DagGraph.
     */
    public static TaskState migrateFromJackson(byte[] jsonData) {
        try {
            // Deserialize into a temporary TaskState via Jackson
            TaskState legacy = JsonMapperFactory.shared().readValue(jsonData, TaskState.class);

            // Try to extract old nodes from the deprecated fields
            JsonNode root = JsonMapperFactory.shared().readTree(jsonData);
            JsonNode rawNodes = root.get("nodes");
            if (rawNodes != null && rawNodes.isArray()) {
                List<TaskState.ThoughtNode> legacyNodes = new ArrayList<>();
                for (JsonNode nodeJson : rawNodes) {
                    TaskState.ThoughtNode tn = JsonMapperFactory.shared().treeToValue(nodeJson, TaskState.ThoughtNode.class);
                    legacyNodes.add(tn);
                }

                if (legacyNodes != null && !legacyNodes.isEmpty()) {
                    DagGraph graph = DagGraph.fromLegacy(legacyNodes);
                    legacy.setGraph(graph);

                    // Set currentNodeId to the last node's ID (closest to old cursor behavior)
                    if (!legacyNodes.isEmpty()) {
                        legacy.setCurrentNodeId(legacyNodes.get(legacyNodes.size() - 1).getNodeId());
                    }

                    log.info("Migrated legacy checkpoint: {} nodes converted to DAG", legacyNodes.size());
                }
            }

            // Ensure graph is never null
            if (legacy.getGraph() == null) {
                legacy.setGraph(new DagGraph());
            }

            return legacy;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to migrate legacy Jackson checkpoint to DAG format", e);
        }
    }
}
