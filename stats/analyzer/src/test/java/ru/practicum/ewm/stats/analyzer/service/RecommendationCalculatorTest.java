package ru.practicum.ewm.stats.analyzer.service;

import org.junit.jupiter.api.Test;
import ru.practicum.ewm.stats.analyzer.model.EventSimilarity;
import ru.practicum.ewm.stats.analyzer.model.Interaction;
import ru.practicum.ewm.stats.proto.RecommendedEventProto;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class RecommendationCalculatorTest {
    private static final Instant TIME = Instant.parse("2026-10-09T00:00:00Z");
    private final RecommendationCalculator calculator = new RecommendationCalculator();

    @Test
    void similarSearchesBothSidesAndExcludesKnownZeroAndTarget() {
        List<RecommendedEventProto> result = calculator.similar(10, List.of(
                edge(1, 10, 0.8), edge(10, 20, 0.8), edge(10, 30, 0), edge(10, 40, 1),
                edge(10, 50, 0.5), edge(20, 50, 1)), Set.of(40L), 3);

        assertThat(result).extracting(RecommendedEventProto::getEventId).containsExactly(1L, 20L, 50L);
        assertThat(result).extracting(RecommendedEventProto::getScore).containsExactly(0.8, 0.8, 0.5);
    }

    @Test
    void candidatesMergeByMaximumSimilarityBeforeApplyingStableLimit() {
        Set<Long> result = calculator.candidates(List.of(
                edge(1, 10, 0.9), edge(2, 10, 0.1), edge(1, 20, 0.8), edge(2, 30, 0.8),
                edge(1, 2, 1), edge(1, 40, 0), edge(3, 50, 1)), Set.of(1L, 2L), Set.of(1L, 2L, 20L), 2);

        assertThat(result).containsExactly(10L, 30L);
    }

    @Test
    void predictionUsesWeightedAverageAndAllHistoryInsteadOfOnlyRecent() {
        List<Interaction> history = List.of(interaction(1, 0.4), interaction(2, 1), interaction(3, 0.8));
        List<RecommendedEventProto> result = calculator.predictions(Set.of(10L, 20L), history,
                List.of(edge(1, 10, 0.5), edge(2, 10, 0.8), edge(3, 10, 0.2),
                        edge(1, 20, 0.9)), 2, 10);

        assertThat(result).extracting(RecommendedEventProto::getEventId).containsExactly(10L, 20L);
        assertThat(result.getFirst().getScore()).isCloseTo(1.0 / 1.3, within(1e-12));
        assertThat(result.getLast().getScore()).isCloseTo(0.4, within(1e-12));
    }

    @Test
    void predictionIgnoresZeroSimilarityAndUnrelatedEdgesAndBreaksTiesById() {
        List<RecommendedEventProto> result = calculator.predictions(Set.of(10L, 20L, 30L),
                List.of(interaction(1, 1)), List.of(edge(1, 10, 0.8), edge(1, 20, 0.5),
                        edge(1, 30, 0), edge(20, 30, 1)), 5, 1);

        assertThat(result).extracting(RecommendedEventProto::getEventId).containsExactly(10L);
        assertThat(result.getFirst().getScore()).isEqualTo(1);
    }

    @Test
    void zeroWeightAndEmptyInputsDoNotProduceNaNOrEmptyInQueries() {
        assertThat(calculator.predictions(Set.of(10L), List.of(interaction(1, 0)),
                List.of(edge(1, 10, 0.8)), 5, 10)).isEmpty();
        assertThat(calculator.similar(1, List.of(), Set.of(), 10)).isEmpty();
        assertThat(calculator.candidates(List.of(), Set.of(), Set.of(), 10)).isEmpty();
        assertThat(calculator.predictions(Set.of(), List.of(), List.of(), 5, 10)).isEmpty();
    }

    @Test
    void neighborTiesUseEventIdAndTinyPositiveSimilarityRemainsFinite() {
        List<RecommendedEventProto> result = calculator.predictions(Set.of(10L),
                List.of(interaction(2, 1), interaction(1, 0.4)),
                List.of(edge(2, 10, 1e-300), edge(1, 10, 1e-300)), 1, 10);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().getScore()).isCloseTo(0.4, within(1e-12));
    }

    private EventSimilarity edge(long first, long second, double score) {
        return new EventSimilarity(first, second, score, TIME);
    }

    private Interaction interaction(long eventId, double weight) {
        return new Interaction(1L, eventId, weight, TIME);
    }
}
