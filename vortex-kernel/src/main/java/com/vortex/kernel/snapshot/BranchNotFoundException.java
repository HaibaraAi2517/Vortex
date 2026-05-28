package com.vortex.kernel.snapshot;

public final class BranchNotFoundException extends ResourceNotFoundException {

    public BranchNotFoundException(String branchId) {
        super("Branch", branchId);
    }
}
