package com.vortex.kernel.snapshot;

import com.vortex.common.model.ActionLogEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class ActionLogTest {

    @TempDir
    Path walDir;

    private ActionLogWriter writer;
    private ActionLogReader reader;
    private ActionLogTruncator truncator;

    @BeforeEach
    void setUp() {
        writer = new ActionLogWriter(walDir.toString());
        reader = new ActionLogReader(walDir.toString());
        truncator = new ActionLogTruncator(reader, walDir.toString());
    }

    @Test
    void append_incrementsSequenceNumber() {
        ActionLogEntry e1 = writer.append("task-1", ActionLogEntry.OperationType.APPEND_NODE,
                "{\"nodeId\":\"n1\"}");
        ActionLogEntry e2 = writer.append("task-1", ActionLogEntry.OperationType.COMPLETE_NODE,
                "{\"nodeId\":\"n1\"}");

        assertThat(e1.getSequenceNumber()).isEqualTo(1);
        assertThat(e2.getSequenceNumber()).isEqualTo(2);
    }

    @Test
    void independentTasks_haveSeparateSequenceNumbers() {
        writer.append("task-A", ActionLogEntry.OperationType.APPEND_NODE, "{}");
        writer.append("task-A", ActionLogEntry.OperationType.APPEND_NODE, "{}");
        writer.append("task-B", ActionLogEntry.OperationType.APPEND_NODE, "{}");

        assertThat(writer.currentSequenceNumber("task-A")).isEqualTo(2);
        assertThat(writer.currentSequenceNumber("task-B")).isEqualTo(1);
    }

    @Test
    void readAll_returnsEntriesInOrder() {
        writer.append("task-r", ActionLogEntry.OperationType.APPEND_NODE, "p1");
        writer.append("task-r", ActionLogEntry.OperationType.COMPLETE_NODE, "p2");
        writer.append("task-r", ActionLogEntry.OperationType.UPDATE_CONTEXT, "p3");
        writer.flush("task-r");

        List<ActionLogEntry> entries = reader.readAll("task-r");
        assertThat(entries).hasSize(3);
        assertThat(entries).extracting(ActionLogEntry::getSequenceNumber)
                .containsExactly(1L, 2L, 3L);
    }

    @Test
    void readFrom_returnsEntriesAfterSequenceNumber() {
        writer.append("task-rf", ActionLogEntry.OperationType.APPEND_NODE, "p1");
        writer.append("task-rf", ActionLogEntry.OperationType.APPEND_NODE, "p2");
        writer.append("task-rf", ActionLogEntry.OperationType.APPEND_NODE, "p3");
        writer.flush("task-rf");

        List<ActionLogEntry> entries = reader.readFrom("task-rf", 2);
        assertThat(entries).hasSize(2);
        assertThat(entries).extracting(ActionLogEntry::getSequenceNumber)
                .containsExactly(2L, 3L);
    }

    @Test
    void readFrom_zero_returnsAll() {
        writer.append("task-rz", ActionLogEntry.OperationType.APPEND_NODE, "p");
        writer.flush("task-rz");

        List<ActionLogEntry> entries = reader.readFrom("task-rz", 0);
        assertThat(entries).hasSize(1);
    }

    @Test
    void truncate_removesEntriesUpToSequenceNumber() {
        writer.append("task-trunc", ActionLogEntry.OperationType.APPEND_NODE, "p1");
        writer.append("task-trunc", ActionLogEntry.OperationType.APPEND_NODE, "p2");
        writer.append("task-trunc", ActionLogEntry.OperationType.APPEND_NODE, "p3");
        writer.flush("task-trunc");

        truncator.truncate("task-trunc", 2);

        List<ActionLogEntry> remaining = reader.readAll("task-trunc");
        assertThat(remaining).hasSize(1);
        assertThat(remaining.get(0).getSequenceNumber()).isEqualTo(3);
    }

    @Test
    void eachEntry_hasUniqueId() {
        ActionLogEntry e1 = writer.append("task-uid", ActionLogEntry.OperationType.APPEND_NODE, "{}");
        ActionLogEntry e2 = writer.append("task-uid", ActionLogEntry.OperationType.APPEND_NODE, "{}");

        assertThat(e1.getEntryId()).isNotEqualTo(e2.getEntryId());
    }

    @Test
    void findEntry_byId() {
        ActionLogEntry e = writer.append("task-find", ActionLogEntry.OperationType.APPEND_NODE,
                "{\"key\":\"value\"}");
        writer.flush("task-find");

        assertThat(reader.findEntry("task-find", e.getEntryId())).isPresent();
        assertThat(reader.findEntry("task-find", "nonexistent")).isEmpty();
    }

    @Test
    void close_removesWriterState() {
        writer.append("task-close", ActionLogEntry.OperationType.APPEND_NODE, "{}");
        writer.close("task-close");

        assertThat(writer.currentSequenceNumber("task-close")).isEqualTo(0);
    }

    @Test
    void exists_returnsTrueAfterWrite() {
        writer.append("task-ex", ActionLogEntry.OperationType.APPEND_NODE, "{}");
        writer.flush("task-ex");
        assertThat(reader.exists("task-ex")).isTrue();
        assertThat(reader.exists("nonexistent")).isFalse();
    }
}
