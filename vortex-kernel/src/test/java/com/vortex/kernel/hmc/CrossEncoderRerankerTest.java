package com.vortex.kernel.hmc;

import com.vortex.common.dto.RerankEffectStatus;
import com.vortex.common.model.MemoryFragment;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CrossEncoderRerankerTest {

    private static final CrossEncoderScoringService.ModelMetadata MODEL_METADATA =
            new CrossEncoderScoringService.ModelMetadata(
                    "test-cross-encoder",
                    "1.0.0",
                    "a".repeat(64));

    @Test
    void reranksOnlyTheBaselineTopFortyAndRecordsIndependentModelEvidence() {
        RecordingScoringService scorer = new RecordingScoringService(
                (query, documents) -> IntStream.range(0, documents.size())
                        .mapToObj(index -> (double) index)
                        .toList());
        CrossEncoderReranker reranker = new CrossEncoderReranker(scorer);
        Map<String, MemoryFragment> candidates = candidates(45);
        Map<String, Double> semanticScores = semanticScores(45);

        CrossEncoderReranker.Result result = reranker.rerank(
                "Which document is relevant?",
                candidates,
                semanticScores,
                Map.of(),
                false,
                40,
                5);

        assertThat(scorer.documents).hasSize(40);
        assertThat(scorer.documents).containsExactlyElementsOf(
                IntStream.range(0, 40).mapToObj(index -> "body-" + index).toList());
        assertThat(result.candidates()).hasSize(40);
        assertThat(result.candidates().getFirst().fragment().getId()).isEqualTo("candidate-39");
        assertThat(result.candidates())
                .extracting(candidate -> candidate.fragment().getId())
                .doesNotContain("candidate-40", "candidate-41", "candidate-42", "candidate-43", "candidate-44");
        assertThat(result.analysis().preselectionCandidateCount()).isEqualTo(45);
        assertThat(result.analysis().inputCandidateCount()).isEqualTo(40);
        assertThat(result.analysis().outputCandidateCount()).isEqualTo(40);
        assertThat(result.analysis().candidatePoolLimit()).isEqualTo(40);
        assertThat(result.analysis().candidatePoolStrategy()).isEqualTo("VECTOR_BASELINE_TOP_40");
        assertThat(result.analysis().scoreDistinctCount()).isEqualTo(40);
        assertThat(result.analysis().topKMembershipChangedCount()).isEqualTo(10);
        assertThat(result.analysis().latencyNanos()).isPositive();
        assertThat(result.analysis().modelMetadata()).isEqualTo(MODEL_METADATA);
        assertThat(result.analysis().effectStatus()).isEqualTo(RerankEffectStatus.ORDER_CHANGED);
    }

    @Test
    void equalScoresPreserveBaselineOrderAndAreNonIdentifiable() {
        RecordingScoringService scorer = new RecordingScoringService(
                (query, documents) -> documents.stream().map(ignored -> 0.5d).toList());
        CrossEncoderReranker reranker = new CrossEncoderReranker(scorer);

        CrossEncoderReranker.Result result = reranker.rerank(
                "query",
                candidates(3),
                semanticScores(3),
                Map.of(),
                false,
                40,
                2);

        assertThat(result.candidates())
                .extracting(candidate -> candidate.fragment().getId())
                .containsExactly("candidate-0", "candidate-1", "candidate-2");
        assertThat(result.analysis().changedPositionCount()).isZero();
        assertThat(result.analysis().topKMembershipChangedCount()).isZero();
        assertThat(result.analysis().scoreDistinctCount()).isEqualTo(1);
        assertThat(result.analysis().effectStatus()).isEqualTo(RerankEffectStatus.NON_IDENTIFIABLE);
    }

    @Test
    void missingScoringServiceFailsInsteadOfFallingBack() {
        CrossEncoderReranker reranker = new CrossEncoderReranker(null);

        assertThatThrownBy(() -> reranker.rerank(
                "query",
                candidates(1),
                semanticScores(1),
                Map.of(),
                false,
                40,
                1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no CrossEncoderScoringService");
    }

    @Test
    void rejectsWrongScoreCardinalityAndNonFiniteScores() {
        CrossEncoderReranker missingScoreReranker = new CrossEncoderReranker(
                new RecordingScoringService((query, documents) -> List.of()));
        CrossEncoderReranker nonFiniteReranker = new CrossEncoderReranker(
                new RecordingScoringService((query, documents) -> List.of(Double.NaN)));

        assertThatThrownBy(() -> missingScoreReranker.rerank(
                "query", candidates(1), semanticScores(1), Map.of(), false, 40, 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("0 scores for 1 documents");
        assertThatThrownBy(() -> nonFiniteReranker.rerank(
                "query", candidates(1), semanticScores(1), Map.of(), false, 40, 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("non-finite score");
    }

    @Test
    void rejectsModelMetadataWithoutContentHash() {
        CrossEncoderScoringService scorer = new CrossEncoderScoringService() {
            @Override
            public ModelMetadata metadata() {
                return new ModelMetadata("model", "version", "not-a-sha256");
            }

            @Override
            public List<Double> score(String query, List<String> documents) {
                return List.of(1.0d);
            }
        };

        assertThatThrownBy(() -> new CrossEncoderReranker(scorer).rerank(
                "query", candidates(1), semanticScores(1), Map.of(), false, 40, 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("64 hex characters");
    }

    private Map<String, MemoryFragment> candidates(int count) {
        Map<String, MemoryFragment> candidates = new LinkedHashMap<>();
        for (int index = 0; index < count; index++) {
            String id = "candidate-" + index;
            candidates.put(id, MemoryFragment.builder()
                    .id(id)
                    .content("body-" + index)
                    .importance(0.5d)
                    .build());
        }
        return candidates;
    }

    private Map<String, Double> semanticScores(int count) {
        Map<String, Double> scores = new LinkedHashMap<>();
        for (int index = 0; index < count; index++) {
            scores.put("candidate-" + index, (double) (count - index));
        }
        return scores;
    }

    private static final class RecordingScoringService implements CrossEncoderScoringService {
        private final BiFunction<String, List<String>, List<Double>> scoringFunction;
        private List<String> documents = new ArrayList<>();

        private RecordingScoringService(BiFunction<String, List<String>, List<Double>> scoringFunction) {
            this.scoringFunction = scoringFunction;
        }

        @Override
        public ModelMetadata metadata() {
            return MODEL_METADATA;
        }

        @Override
        public List<Double> score(String query, List<String> documents) {
            this.documents = List.copyOf(documents);
            return scoringFunction.apply(query, documents);
        }
    }
}
