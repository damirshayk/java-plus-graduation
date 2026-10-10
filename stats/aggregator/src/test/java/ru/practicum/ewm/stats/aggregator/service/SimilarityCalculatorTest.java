package ru.practicum.ewm.stats.aggregator.service;

import org.junit.jupiter.api.Test;
import ru.practicum.ewm.stats.avro.ActionTypeAvro;
import ru.practicum.ewm.stats.avro.EventSimilarityAvro;
import ru.practicum.ewm.stats.avro.UserActionAvro;
import ru.practicum.ewm.stats.serialization.ActionWeights;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class SimilarityCalculatorTest {

    private static final Instant TIMESTAMP = Instant.ofEpochMilli(1_800_000_000_123L);
    private final SimilarityCalculator calculator = new SimilarityCalculator(new ActionWeights(0.4, 0.8, 1.0));

    @Test
    void shouldProduceNoPairForTheFirstEvent() {
        assertThat(process(1, 10, ActionTypeAvro.VIEW)).isEmpty();
    }

    @Test
    void shouldCompareNewEventWithTheCurrentUsersHistory() {
        process(1, 10, ActionTypeAvro.VIEW);
        process(1, 20, ActionTypeAvro.LIKE);

        List<EventSimilarityAvro> result = process(1, 30, ActionTypeAvro.REGISTER);

        assertThat(result).hasSize(2);
        assertScore(result, 10, 30, 0.4 / Math.sqrt(0.4 * 0.8));
        assertScore(result, 20, 30, 0.8 / Math.sqrt(1.0 * 0.8));
    }

    @Test
    void shouldIncrementMaximumWeightRatherThanAccumulateActions() {
        process(1, 10, ActionTypeAvro.VIEW);
        process(1, 20, ActionTypeAvro.REGISTER);

        assertScore(process(1, 10, ActionTypeAvro.LIKE), 10, 20, 0.8 / Math.sqrt(1.0 * 0.8));
    }

    @Test
    void shouldNotRecalculateAnEqualOrLowerWeight() {
        process(1, 10, ActionTypeAvro.LIKE);
        process(1, 20, ActionTypeAvro.VIEW);

        assertThat(process(1, 10, ActionTypeAvro.LIKE)).isEmpty();
        assertThat(process(1, 10, ActionTypeAvro.VIEW)).isEmpty();
        assertThat(process(1, 10, ActionTypeAvro.REGISTER)).isEmpty();
        assertScore(process(1, 20, ActionTypeAvro.LIKE), 10, 20, 1.0);
    }

    @Test
    void shouldNormalizeIdentifiersNumerically() {
        process(1, Long.MAX_VALUE, ActionTypeAvro.LIKE);

        assertScore(process(1, 3_000_000_000L, ActionTypeAvro.LIKE), 3_000_000_000L, Long.MAX_VALUE, 1.0);
    }

    @Test
    void shouldNotPublishForEventsWithoutCommonUsers() {
        process(5, 8, ActionTypeAvro.REGISTER);

        assertThat(process(7, 3, ActionTypeAvro.REGISTER)).isEmpty();
        List<EventSimilarityAvro> result = process(7, 13, ActionTypeAvro.VIEW);

        assertThat(result).hasSize(1);
        assertScore(result, 3, 13, 0.4 / Math.sqrt(0.8 * 0.4));
    }

    @Test
    void shouldPublishWhenPreviouslyDisjointEventsGainTheirFirstCommonUser() {
        process(5, 8, ActionTypeAvro.REGISTER);
        assertThat(process(7, 3, ActionTypeAvro.REGISTER)).isEmpty();

        List<EventSimilarityAvro> result = process(5, 3, ActionTypeAvro.VIEW);

        assertThat(result).hasSize(1);
        assertScore(result, 3, 8, 0.4 / Math.sqrt(1.2 * 0.8));
    }

    @Test
    void shouldIncludeAllUsersInTheEventSums() {
        process(1, 10, ActionTypeAvro.LIKE);
        process(2, 10, ActionTypeAvro.VIEW);
        process(1, 20, ActionTypeAvro.REGISTER);

        assertScore(process(2, 20, ActionTypeAvro.LIKE), 10, 20,
                (0.8 + 0.4) / Math.sqrt((1.0 + 0.4) * (0.8 + 1.0)));
    }

    @Test
    void shouldRecalculateWhenOnlyTheDenominatorChanges() {
        process(1, 10, ActionTypeAvro.REGISTER);
        process(1, 20, ActionTypeAvro.VIEW);
        process(2, 30, ActionTypeAvro.REGISTER);

        List<EventSimilarityAvro> result = process(1, 10, ActionTypeAvro.LIKE);

        assertThat(result).hasSize(1);
        assertScore(result, 10, 20, 0.4 / Math.sqrt(1.0 * 0.4));
    }

    @Test
    void shouldPublishOnlyPairsFromTheCurrentUsersHistory() {
        process(1, 10, ActionTypeAvro.VIEW);
        process(1, 20, ActionTypeAvro.VIEW);
        process(2, 30, ActionTypeAvro.REGISTER);

        List<EventSimilarityAvro> result = process(2, 10, ActionTypeAvro.LIKE);

        assertThat(result).hasSize(1);
        assertScore(result, 10, 30, 0.8 / Math.sqrt(1.4 * 0.8));
    }

    @Test
    void shouldNotPublishAKnownPairForANewUserWithoutOtherInteractions() {
        process(1, 10, ActionTypeAvro.VIEW);
        process(1, 20, ActionTypeAvro.VIEW);

        assertThat(process(2, 10, ActionTypeAvro.LIKE)).isEmpty();
    }

    @Test
    void shouldKeepAllUsersWeightsForTheNextUpdateOfThePair() {
        process(1, 10, ActionTypeAvro.VIEW);
        process(1, 20, ActionTypeAvro.VIEW);
        process(2, 10, ActionTypeAvro.LIKE);

        List<EventSimilarityAvro> result = process(1, 10, ActionTypeAvro.LIKE);

        assertThat(result).hasSize(1);
        assertScore(result, 10, 20, 0.4 / Math.sqrt(2.0 * 0.4));
    }

    @Test
    void shouldPreserveTheOriginalMillisecondTimestamp() {
        process(1, 10, ActionTypeAvro.VIEW);
        Instant timestamp = TIMESTAMP.minusSeconds(30);

        var update = calculator.prepare(new UserActionAvro(1L, 20L, ActionTypeAvro.REGISTER, timestamp));

        assertThat(update.similarities()).extracting(EventSimilarityAvro::getTimestamp).containsExactly(timestamp);
    }

    @Test
    void shouldNotChangeMemoryBeforePreparedUpdateIsApplied() {
        process(1, 10, ActionTypeAvro.VIEW);
        UserActionAvro action = new UserActionAvro(1L, 20L, ActionTypeAvro.LIKE, TIMESTAMP);

        var firstAttempt = calculator.prepare(action);
        var retry = calculator.prepare(action);

        assertThat(firstAttempt.similarities()).hasSize(1);
        assertThat(retry.similarities()).isEqualTo(firstAttempt.similarities());
        calculator.apply(retry);
        assertThat(calculator.prepare(action).similarities()).isEmpty();
    }

    @Test
    void shouldNotExposeMutablePreparedState() {
        process(1, 10, ActionTypeAvro.VIEW);
        var update = calculator.prepare(new UserActionAvro(1L, 20L, ActionTypeAvro.LIKE, TIMESTAMP));

        List<EventSimilarityAvro> result = update.similarities();
        assertThat(result).hasSize(1);
        assertThatThrownBy(result::clear).isInstanceOf(UnsupportedOperationException.class);
        result.getFirst().setScore(42.0);

        assertScore(update.similarities(), 10, 20, 0.4 / Math.sqrt(0.4 * 1.0));
        calculator.apply(update);
        assertScore(process(1, 10, ActionTypeAvro.LIKE), 10, 20, 1.0);
    }

    @Test
    void shouldRejectStalePreparedUpdate() {
        var stale = calculator.prepare(new UserActionAvro(1L, 10L, ActionTypeAvro.VIEW, TIMESTAMP));
        process(1, 20, ActionTypeAvro.LIKE);

        assertThatThrownBy(() -> calculator.apply(stale)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldRejectAnUpdatePreparedByAnotherCalculator() {
        var another = new SimilarityCalculator(new ActionWeights(0.4, 0.8, 1.0));
        var update = another.prepare(new UserActionAvro(1L, 10L, ActionTypeAvro.VIEW, TIMESTAMP));

        assertThatThrownBy(() -> calculator.apply(update)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldResetAllStateBeforeReplay() {
        process(1, 10, ActionTypeAvro.LIKE);
        process(1, 20, ActionTypeAvro.LIKE);

        calculator.reset();

        assertThat(process(2, 30, ActionTypeAvro.VIEW)).isEmpty();
        assertScore(process(2, 40, ActionTypeAvro.VIEW), 30, 40, 1.0);
    }

    @Test
    void shouldNotPublishWhenBothEventSumsAreZero() {
        var zeroCalculator = new SimilarityCalculator(new ActionWeights(0.0, 0.0, 0.0));
        zeroCalculator.apply(zeroCalculator.prepare(new UserActionAvro(1L, 10L, ActionTypeAvro.VIEW, TIMESTAMP)));

        var update = zeroCalculator.prepare(new UserActionAvro(1L, 20L, ActionTypeAvro.LIKE, TIMESTAMP));

        assertThat(update.similarities()).isEmpty();
    }

    @Test
    void shouldAvoidUnderflowWhenComputingTheDenominator() {
        var smallCalculator = new SimilarityCalculator(
                new ActionWeights(Double.MIN_VALUE, Double.MIN_VALUE, Double.MIN_VALUE));
        smallCalculator.apply(smallCalculator.prepare(new UserActionAvro(1L, 10L, ActionTypeAvro.VIEW, TIMESTAMP)));

        var update = smallCalculator.prepare(new UserActionAvro(1L, 20L, ActionTypeAvro.LIKE, TIMESTAMP));

        assertScore(update.similarities(), 10, 20, 1.0);
    }

    @Test
    void shouldInvalidatePreparedUpdatesAfterReset() {
        var update = calculator.prepare(new UserActionAvro(1L, 10L, ActionTypeAvro.VIEW, TIMESTAMP));

        calculator.reset();

        assertThatThrownBy(() -> calculator.apply(update)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldCaptureActionFieldsBeforePublication() {
        process(1, 10, ActionTypeAvro.VIEW);
        UserActionAvro action = new UserActionAvro(1L, 20L, ActionTypeAvro.LIKE, TIMESTAMP);
        var update = calculator.prepare(action);

        action.setEventId(30L);
        action.setUserId(2L);
        action.setActionType(ActionTypeAvro.VIEW);
        calculator.apply(update);

        assertThat(calculator.prepare(new UserActionAvro(1L, 20L, ActionTypeAvro.LIKE, TIMESTAMP)).similarities())
                .isEmpty();
        assertScore(process(1, 10, ActionTypeAvro.LIKE), 10, 20, 1.0);
    }

    private List<EventSimilarityAvro> process(long userId, long eventId, ActionTypeAvro actionType) {
        var update = calculator.prepare(new UserActionAvro(userId, eventId, actionType, TIMESTAMP));
        calculator.apply(update);
        return update.similarities();
    }

    private void assertScore(List<EventSimilarityAvro> similarities, long eventA, long eventB, double expected) {
        var matching = similarities.stream()
                .filter(value -> value.getEventA() == eventA && value.getEventB() == eventB)
                .findFirst();
        assertThat(matching).as("Сходство пары %d/%d", eventA, eventB).isPresent();
        EventSimilarityAvro similarity = matching.orElseThrow();
        assertThat(similarity.getScore()).isFinite().isCloseTo(expected, within(1e-12));
    }
}
