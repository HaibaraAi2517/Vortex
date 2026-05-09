package com.vortex.kernel.hmc;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdaptiveWeightProfile {

    private String profileName;
    private double alpha;
    private double beta;
    private double gamma;
    private long updateCount;
    private Instant updatedAt;

    public void normalize() {
        double sum = Math.max(1e-9, alpha + beta + gamma);
        alpha = clamp(alpha / sum);
        beta = clamp(beta / sum);
        gamma = clamp(gamma / sum);
    }

    public AdaptiveWeightProfile copyAs(String nextName) {
        return AdaptiveWeightProfile.builder()
                .profileName(nextName)
                .alpha(alpha)
                .beta(beta)
                .gamma(gamma)
                .updateCount(updateCount)
                .updatedAt(updatedAt)
                .build();
    }

    private double clamp(double value) {
        return Math.max(0.05, Math.min(0.90, value));
    }
}
