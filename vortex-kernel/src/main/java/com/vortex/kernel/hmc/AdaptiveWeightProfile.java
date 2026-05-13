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

    private static final double MIN_WEIGHT = 0.05;
    private static final double MAX_WEIGHT = 0.90;

    private String profileName;
    private double alpha;
    private double beta;
    private double gamma;
    private long updateCount;
    private Instant updatedAt;

    public void normalize() {
        alpha = clamp(alpha);
        beta = clamp(beta);
        gamma = clamp(gamma);
        double sum = Math.max(1e-9, alpha + beta + gamma);
        alpha /= sum;
        beta /= sum;
        gamma /= sum;
        alpha = clamp(alpha);
        beta = clamp(beta);
        gamma = clamp(gamma);
        rebalanceToUnitSum();
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

    public void applyDeltas(double alphaDelta, double betaDelta, double gammaDelta) {
        alpha = clamp(alpha + alphaDelta);
        beta = clamp(beta + betaDelta);
        gamma = clamp(gamma + gammaDelta);
        normalize();
    }

    private double clamp(double value) {
        return Math.max(MIN_WEIGHT, Math.min(MAX_WEIGHT, value));
    }

    private void rebalanceToUnitSum() {
        double sum = alpha + beta + gamma;
        double drift = 1.0 - sum;
        if (Math.abs(drift) <= 1.0e-9) {
            return;
        }
        double[] weights = {alpha, beta, gamma};
        int maxIter = 10;
        while (Math.abs(drift) > 1.0e-9 && maxIter-- > 0) {
            int targetIndex = largestAdjustableIndex(weights, drift > 0.0);
            if (targetIndex < 0) {
                break;
            }
            double room = drift > 0.0
                    ? MAX_WEIGHT - weights[targetIndex]
                    : weights[targetIndex] - MIN_WEIGHT;
            if (room <= 0.0) {
                break;
            }
            double delta = Math.copySign(Math.min(Math.abs(drift), room), drift);
            weights[targetIndex] += delta;
            drift -= delta;
        }
        alpha = weights[0];
        beta = weights[1];
        gamma = weights[2];
    }

    private int largestAdjustableIndex(double[] weights, boolean increasing) {
        int index = -1;
        double best = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < weights.length; i++) {
            double room = increasing ? MAX_WEIGHT - weights[i] : weights[i] - MIN_WEIGHT;
            if (room > 1.0e-9 && weights[i] > best) {
                best = weights[i];
                index = i;
            }
        }
        return index;
    }
}
