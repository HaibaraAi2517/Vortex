package com.vortex.kernel.snapshot;

public final class NodeNotFoundException extends ResourceNotFoundException {

    public NodeNotFoundException(String nodeId) {
        super("Node", nodeId);
    }
}
