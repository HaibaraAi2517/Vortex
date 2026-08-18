package com.vortex.app.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vortex.app.runtime.ExecutionIdService;
import com.vortex.app.security.NamespaceAuthorizationService;
import com.vortex.app.security.VortexSecurityProperties;
import com.vortex.common.model.TaskState;
import com.vortex.kernel.snapshot.SnapshotService;
import com.vortex.kernel.snapshot.TaskLifecycleManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TaskController.class)
@AutoConfigureMockMvc(addFilters = false)
class TaskControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private SnapshotService snapshotService;

    @MockBean
    private ExecutionIdService executionIdService;

    @MockBean
    private NamespaceAuthorizationService namespaceAuthorization;

    @MockBean
    private VortexSecurityProperties securityProperties;

    @BeforeEach
    void setUpExecutionIdPassthrough() {
        doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(3)).get())
                .when(executionIdService).execute(any(), any(), any(), any());
    }

    @Test
    void listTasks_returnsPagedPayload() throws Exception {
        TaskState task = TaskState.builder()
                .taskId("task-1")
                .description("demo")
                .createdAt(Instant.parse("2026-05-25T00:00:00Z"))
                .build();
        when(snapshotService.listActiveTasks(1, 1))
                .thenReturn(new TaskLifecycleManager.TaskPage(List.of(task), 1, 1, 3));

        mockMvc.perform(get("/api/v1/tasks").param("page", "1").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].taskId").value("task-1"))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.hasNext").value(true));
    }

    @Test
    void listTasks_rejectsPageSizeAboveLimit() throws Exception {
        mockMvc.perform(get("/api/v1/tasks").param("size", "201"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value("Request parameter validation failed"));
    }

    @Test
    void createTask_usesExecutionIdWhenHeaderIsPresent() throws Exception {
        TaskState created = TaskState.builder()
                .taskId("task-1")
                .description("demo")
                .namespace("ns")
                .createdAt(Instant.parse("2026-05-25T00:00:00Z"))
                .build();
        when(snapshotService.createTask("demo", "ns")).thenReturn(created);

        mockMvc.perform(post("/api/v1/tasks")
                        .header(ExecutionIdService.HEADER_NAME, "exec-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new TaskController.CreateTaskRequest("demo", "ns"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value("task-1"));

        verify(executionIdService).execute(any(), any(), any(), any());
        verify(snapshotService).createTask("demo", "ns");
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
    void deleteTask_returnsDeletedStatusWhenServiceDeletes() throws Exception {
        when(snapshotService.deleteTask("task-1")).thenReturn(true);

        mockMvc.perform(delete("/api/v1/tasks/task-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value("task-1"))
                .andExpect(jsonPath("$.status").value("DELETED"));
    }

    @Test
    void deleteNode_callsService() throws Exception {
        doNothing().when(snapshotService).deleteNode("task-1", "node-1");

        mockMvc.perform(delete("/api/v1/tasks/task-1/nodes/node-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value("task-1"))
                .andExpect(jsonPath("$.nodeId").value("node-1"))
                .andExpect(jsonPath("$.status").value("DELETED"));

        verify(snapshotService).deleteNode("task-1", "node-1");
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
