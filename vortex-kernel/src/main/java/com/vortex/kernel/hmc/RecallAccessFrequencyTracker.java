package com.vortex.kernel.hmc;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.LongSupplier;

/** Tracks recall exposure as a decaying signal without changing the storage schema. */
final class RecallAccessFrequencyTracker {

    private static final long DEFAULT_HALF_LIFE_MILLIS = 7L * 86_400_000L;
    private static final double NORMALIZATION_SCALE = 5.0d;

    private final ConcurrentMap<String, AccessState> states = new ConcurrentHashMap<>();
    private final LongSupplier clock;
    private final long halfLifeMillis;

    RecallAccessFrequencyTracker() {
        this(System::currentTimeMillis, DEFAULT_HALF_LIFE_MILLIS);
    }

    RecallAccessFrequencyTracker(LongSupplier clock, long halfLifeMillis) {
        this.clock = clock;
        this.halfLifeMillis = Math.max(1L, halfLifeMillis);
    }

    void recordRecall(String fragmentId) {
        if (fragmentId == null || fragmentId.isBlank()) {
            return;
        }
        long now = clock.getAsLong();
        states.compute(fragmentId, (ignored, previous) -> {
            double decayedCount = previous == null ? 0.0d : decay(previous, now);
            return new AccessState(decayedCount + 1.0d, now);
        });
    }

    double score(String fragmentId) {
        if (fragmentId == null || fragmentId.isBlank()) {
            return 0.0d;
        }
        AccessState state = states.get(fragmentId);
        if (state == null) {
            return 0.0d;
        }
        double decayedCount = decay(state, clock.getAsLong());
        return 1.0d - Math.exp(-decayedCount / NORMALIZATION_SCALE);
    }

    void removeAll(Collection<String> fragmentIds) {
        if (fragmentIds == null || fragmentIds.isEmpty()) {
            return;
        }
        fragmentIds.stream()
                .filter(fragmentId -> fragmentId != null && !fragmentId.isBlank())
                .forEach(states::remove);
    }

    private double decay(AccessState state, long now) {
        long elapsed = Math.max(0L, now - state.recordedAtMillis());
        double factor = Math.exp(-Math.log(2.0d) * elapsed / halfLifeMillis);
        return state.count() * factor;
    }

    private record AccessState(double count, long recordedAtMillis) {
    }
}
