package com.vortex.kernel.hmc;

import java.util.List;

/**
 * Produces independent query-document relevance scores for a batch of candidate texts.
 *
 * <p>Implementations receive only the query and document content. Fragment IDs, expected
 * fragments, tags, importance, and benchmark labels are deliberately excluded from the model
 * input contract.</p>
 */
public interface CrossEncoderScoringService {

    ModelMetadata metadata();

    /**
     * Returns one finite relevance score per document, in the same order as {@code documents}.
     */
    List<Double> score(String query, List<String> documents);

    record ModelMetadata(
            String model,
            String version,
            String sha256) {
    }
}
