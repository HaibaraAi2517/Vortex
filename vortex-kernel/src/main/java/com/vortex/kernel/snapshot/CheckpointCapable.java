package com.vortex.kernel.snapshot;

public interface CheckpointCapable {

    String checkpoint(String taskId);

    boolean isTaskLoadedForCheckpoint(String taskId);
}
