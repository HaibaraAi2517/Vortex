package com.vortex.kernel.hmc;

import com.vortex.common.model.MemoryFragment;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FragmentPersistenceTask {

    private String idempotencyKey;
    private String reason;
    private MemoryFragment fragment;
    private Instant createdAt;
    private int attemptCount;
    private String lastFailure;
    private boolean l2Persisted;
    private boolean l3Archived;
}
