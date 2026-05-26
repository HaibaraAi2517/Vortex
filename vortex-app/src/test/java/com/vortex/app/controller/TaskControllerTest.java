package com.vortex.app.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vortex.common.model.TaskState;
import com.vortex.kernel.snapshot.SnapshotService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TaskController.class)
class TaskControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private SnapshotService snapshotService;

    @Test
    void listTasks_returnsPagedPayload() throws Exception {
        TaskState task = TaskState.builder()
                .taskId("task-1")
                .description("demo")
                .createdAt(Instant.parse("2026-05-25T00:00:00Z"))
                .build();
        when(snapshotService.listActiveTasks(1, 1))
                .thenReturn(new SnapshotService.TaskPage(List.of(task), 1, 1, 3));

        mockMvc.perform(get("/api/v1/tasks").param("page", "1").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].taskId").value("task-1"))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.hasNext").value(true));
    }

    @Test
    void updateContext_callsService() throws Exception {
        doNothing().when(snapshotService).updateContext("task-1", "mode", "strict");

        mockMvc.perform(put("/api/v1/tasks/task-1/context")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new TaskController.UpdateContextRequest("mode", "strict"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value("task-1"))
                .andExpect(jsonPath("$.key").value("mode"));

        verify(snapshotService).updateContext("task-1", "mode", "strict");
    }

    @Test
    void switchBranch_callsService() throws Exception {
        doNothing().when(snapshotService).switchBranch("task-1", "branch-1");

        mockMvc.perform(post("/api/v1/tasks/task-1/branch/switch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new TaskController.SwitchBranchRequest("branch-1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value("task-1"))
                .andExpect(jsonPath("$.branchId").value("branch-1"));

        verify(snapshotService).switchBranch("task-1", "branch-1");
    }

    @Test
    void failTask_callsService() throws Exception {
        doNothing().when(snapshotService).failTask("task-1");

        mockMvc.perform(post("/api/v1/tasks/task-1/fail"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"));

        verify(snapshotService).failTask("task-1");
    }

    @Test
    void createTask_rejectsBlankNamespace() throws Exception {
        mockMvc.perform(post("/api/v1/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "description", "demo",
                                "namespace", ""))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));
    }

    @Test
    void appendNode_rejectsInvalidEdgeTypeAsBadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/tasks/task-1/nodes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "type", "THOUGHT",
                                "content", "demo",
                                "targetNodeId", "node-1",
                                "edgeType", "not-an-edge"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
    }
}
