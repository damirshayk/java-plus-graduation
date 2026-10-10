package ru.practicum.ewm.stats.client;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.grpc.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import ru.practicum.ewm.stats.proto.ActionTypeProto;
import ru.practicum.ewm.stats.proto.InteractionsCountRequestProto;
import ru.practicum.ewm.stats.proto.RecommendedEventProto;
import ru.practicum.ewm.stats.proto.SimilarEventsRequestProto;
import ru.practicum.ewm.stats.proto.UserActionProto;
import ru.practicum.ewm.stats.proto.UserPredictionsRequestProto;
import ru.practicum.ewm.stats.proto.collector.UserActionControllerGrpc;
import ru.practicum.ewm.stats.proto.dashboard.RecommendationsControllerGrpc;

import java.time.Instant;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GrpcClientsTest {

    private UserActionControllerGrpc.UserActionControllerBlockingStub collectorStub;
    private UserActionControllerGrpc.UserActionControllerBlockingStub timedCollector;
    private RecommendationsControllerGrpc.RecommendationsControllerBlockingStub analyzerStub;
    private RecommendationsControllerGrpc.RecommendationsControllerBlockingStub timedAnalyzer;
    private CollectorClient collector;
    private AnalyzerClient analyzer;
    private CircuitBreaker collectorBreaker;
    private CircuitBreaker analyzerBreaker;

    @BeforeEach
    void setUp() {
        collectorStub = mock(UserActionControllerGrpc.UserActionControllerBlockingStub.class);
        timedCollector = mock(UserActionControllerGrpc.UserActionControllerBlockingStub.class);
        analyzerStub = mock(RecommendationsControllerGrpc.RecommendationsControllerBlockingStub.class);
        timedAnalyzer = mock(RecommendationsControllerGrpc.RecommendationsControllerBlockingStub.class);
        when(collectorStub.withDeadlineAfter(1000, TimeUnit.MILLISECONDS)).thenReturn(timedCollector);
        when(analyzerStub.withDeadlineAfter(1000, TimeUnit.MILLISECONDS)).thenReturn(timedAnalyzer);
        StatsClientProperties properties = new StatsClientProperties();
        properties.setTimeoutMs(1000);
        collectorBreaker = breaker("collector");
        analyzerBreaker = breaker("analyzer");
        collector = new CollectorClient(collectorStub, properties, collectorBreaker);
        analyzer = new AnalyzerClient(analyzerStub, properties, analyzerBreaker);
    }

    @Test
    void collectorSendsLargeIdsAndCurrentTimestampOnceWithDeadline() {
        Instant before = Instant.now();

        collector.collect(Long.MAX_VALUE, Long.MAX_VALUE - 1, ActionTypeProto.ACTION_LIKE);

        Instant after = Instant.now();
        ArgumentCaptor<UserActionProto> captor = ArgumentCaptor.forClass(UserActionProto.class);
        verify(timedCollector).collectUserAction(captor.capture());
        UserActionProto action = captor.getValue();
        assertThat(action.getUserId()).isEqualTo(Long.MAX_VALUE);
        assertThat(action.getEventId()).isEqualTo(Long.MAX_VALUE - 1);
        assertThat(action.getActionType()).isEqualTo(ActionTypeProto.ACTION_LIKE);
        assertThat(action.hasTimestamp()).isTrue();
        assertThat(Instant.ofEpochSecond(action.getTimestamp().getSeconds(), action.getTimestamp().getNanos()))
                .isBetween(before, after);
        verify(collectorStub).withDeadlineAfter(1000, TimeUnit.MILLISECONDS);
    }

    @ParameterizedTest
    @CsvSource({"0,1", "-1,1", "1,0", "1,-1"})
    void collectorRejectsInvalidIdsBeforeRpc(long userId, long eventId) {
        assertThatThrownBy(() -> collector.collect(userId, eventId, ActionTypeProto.ACTION_VIEW))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(timedCollector);
    }

    @Test
    void collectorRejectsMissingAndUnknownTypeBeforeRpc() {
        assertThatThrownBy(() -> collector.collect(1, 1, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> collector.collect(1, 1, ActionTypeProto.UNRECOGNIZED))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(timedCollector);
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNAVAILABLE", "DEADLINE_EXCEEDED", "RESOURCE_EXHAUSTED"})
    void temporaryCollectorFailureIsSkippedWithoutRetry(String code) {
        when(timedCollector.collectUserAction(any())).thenThrow(status(code).asRuntimeException());

        assertThatCode(() -> collector.collect(1, 1, ActionTypeProto.ACTION_REGISTER)).doesNotThrowAnyException();

        verify(timedCollector).collectUserAction(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"INVALID_ARGUMENT", "INTERNAL", "UNKNOWN", "PERMISSION_DENIED"})
    void permanentCollectorFailureIsPropagated(String code) {
        RuntimeException failure = status(code).asRuntimeException();
        when(timedCollector.collectUserAction(any())).thenThrow(failure);

        assertThatThrownBy(() -> collector.collect(1, 1, ActionTypeProto.ACTION_VIEW)).isSameAs(failure);
    }

    @Test
    void collectorOpenCircuitSkipsRpcButDoesNotBlockAnalyzer() {
        collectorBreaker.transitionToOpenState();
        when(timedAnalyzer.getInteractionsCount(any())).thenReturn(List.of(event(1, 2)).iterator());

        assertThatCode(() -> collector.collect(1, 1, ActionTypeProto.ACTION_VIEW)).doesNotThrowAnyException();
        assertThat(analyzer.ratings(List.of(1L))).isEqualTo(Map.of(1L, 2D));

        verifyNoInteractions(timedCollector);
    }

    @Test
    void ratingsDeduplicatesIdsUsesOneRpcAndAllowsScoresAboveOne() {
        when(timedAnalyzer.getInteractionsCount(any()))
                .thenReturn(List.of(event(Long.MAX_VALUE, 12.5), event(2, 0)).iterator());

        Map<Long, Double> result = analyzer.ratings(List.of(Long.MAX_VALUE, 2L, Long.MAX_VALUE));

        assertThat(result).isEqualTo(Map.of(Long.MAX_VALUE, 12.5, 2L, 0D));
        ArgumentCaptor<InteractionsCountRequestProto> captor = ArgumentCaptor.forClass(InteractionsCountRequestProto.class);
        verify(timedAnalyzer).getInteractionsCount(captor.capture());
        assertThat(captor.getValue().getEventIdList()).containsExactly(Long.MAX_VALUE, 2L);
        verify(analyzerStub).withDeadlineAfter(1000, TimeUnit.MILLISECONDS);
        assertThatThrownBy(() -> result.put(3L, 1D)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void emptyRatingsSkipsRpcEvenWithOpenCircuit() {
        analyzerBreaker.transitionToOpenState();

        assertThat(analyzer.ratings(List.of())).isEmpty();

        verifyNoInteractions(timedAnalyzer);
    }

    @Test
    void invalidRatingsIdsAreRejectedBeforeRpc() {
        for (List<Long> ids : List.of(List.of(0L), List.of(-1L), Arrays.asList(1L, null))) {
            assertThatThrownBy(() -> analyzer.ratings(ids)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> analyzer.ratings(null)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(timedAnalyzer);
    }

    @Test
    void recommendationsPreservesStreamingOrderAndRequestLimits() {
        List<RecommendedEventProto> expected = List.of(event(9, 1), event(3, 0.5), event(5, 0));
        when(timedAnalyzer.getRecommendationsForUser(any())).thenReturn(expected.iterator());

        List<RecommendedEventProto> result = analyzer.recommendations(Long.MAX_VALUE, 3);

        assertThat(result).containsExactlyElementsOf(expected);
        ArgumentCaptor<UserPredictionsRequestProto> captor = ArgumentCaptor.forClass(UserPredictionsRequestProto.class);
        verify(timedAnalyzer).getRecommendationsForUser(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(Long.MAX_VALUE);
        assertThat(captor.getValue().getMaxResults()).isEqualTo(3);
        assertThatThrownBy(() -> result.add(event(2, 0))).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void similarPassesEventUserAndLimitAndPreservesOrder() {
        when(timedAnalyzer.getSimilarEvents(any())).thenReturn(List.of(event(2, 0.7)).iterator());

        assertThat(analyzer.similar(Long.MAX_VALUE, Long.MAX_VALUE - 1, 4)).containsExactly(event(2, 0.7));

        ArgumentCaptor<SimilarEventsRequestProto> captor = ArgumentCaptor.forClass(SimilarEventsRequestProto.class);
        verify(timedAnalyzer).getSimilarEvents(captor.capture());
        assertThat(captor.getValue().getEventId()).isEqualTo(Long.MAX_VALUE);
        assertThat(captor.getValue().getUserId()).isEqualTo(Long.MAX_VALUE - 1);
        assertThat(captor.getValue().getMaxResults()).isEqualTo(4);
    }

    @ParameterizedTest
    @CsvSource({"0,1", "-1,1", "1,0", "1,-1"})
    void recommendationArgumentsAreValidatedBeforeRpc(long userId, int maxResults) {
        assertThatThrownBy(() -> analyzer.recommendations(userId, maxResults)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> analyzer.similar(1, userId, maxResults)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(timedAnalyzer);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void similarRejectsInvalidEventBeforeRpc(long eventId) {
        assertThatThrownBy(() -> analyzer.similar(eventId, 1, 1)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(timedAnalyzer);
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNAVAILABLE", "DEADLINE_EXCEEDED", "RESOURCE_EXHAUSTED"})
    void partialTemporaryStreamFailureDiscardsAllAccumulatedResponses(String code) {
        when(timedAnalyzer.getInteractionsCount(any())).thenReturn(brokenStream(status(code)));
        when(timedAnalyzer.getRecommendationsForUser(any())).thenReturn(brokenStream(status(code)));
        when(timedAnalyzer.getSimilarEvents(any())).thenReturn(brokenStream(status(code)));

        assertThat(analyzer.ratings(List.of(1L))).isEmpty();
        assertThat(analyzer.recommendations(1, 3)).isEmpty();
        assertThat(analyzer.similar(2, 1, 3)).isEmpty();
        verify(timedAnalyzer).getInteractionsCount(any());
        verify(timedAnalyzer).getRecommendationsForUser(any());
        verify(timedAnalyzer).getSimilarEvents(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"INVALID_ARGUMENT", "INTERNAL", "UNKNOWN"})
    void permanentStreamFailuresAreNotHidden(String code) {
        when(timedAnalyzer.getInteractionsCount(any())).thenReturn(brokenStream(status(code)));

        assertThatThrownBy(() -> analyzer.ratings(List.of(1L))).hasMessageContaining(code);
    }

    @ParameterizedTest
    @ValueSource(doubles = {-1, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
    void malformedRatingsScoresAreNotHidden(double score) {
        when(timedAnalyzer.getInteractionsCount(any())).thenReturn(List.of(event(1, score)).iterator());

        assertThatThrownBy(() -> analyzer.ratings(List.of(1L))).isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @ValueSource(doubles = {-1, 1.01, Double.NaN, Double.POSITIVE_INFINITY})
    void malformedRecommendationAndSimilarityScoresAreNotHidden(double score) {
        when(timedAnalyzer.getRecommendationsForUser(any())).thenReturn(List.of(event(1, score)).iterator());
        when(timedAnalyzer.getSimilarEvents(any())).thenReturn(List.of(event(1, score)).iterator());

        assertThatThrownBy(() -> analyzer.recommendations(1, 1)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> analyzer.similar(2, 1, 1)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void duplicateResponseIdsAreRejectedForEveryRead() {
        List<RecommendedEventProto> duplicated = List.of(event(1, 0.5), event(1, 0.7));
        when(timedAnalyzer.getInteractionsCount(any())).thenReturn(duplicated.iterator());
        when(timedAnalyzer.getRecommendationsForUser(any())).thenReturn(duplicated.iterator());
        when(timedAnalyzer.getSimilarEvents(any())).thenReturn(duplicated.iterator());

        assertThatThrownBy(() -> analyzer.ratings(List.of(1L))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> analyzer.recommendations(1, 2)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> analyzer.similar(2, 1, 2)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void invalidResponseIdAndMissingResponseAreRejected() {
        when(timedAnalyzer.getInteractionsCount(any())).thenReturn(List.of(event(0, 1)).iterator());
        when(timedAnalyzer.getRecommendationsForUser(any())).thenReturn(Arrays.asList((RecommendedEventProto) null).iterator());
        when(timedAnalyzer.getSimilarEvents(any())).thenReturn(null);

        assertThatThrownBy(() -> analyzer.ratings(List.of(1L))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> analyzer.recommendations(1, 1)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> analyzer.similar(2, 1, 1)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void analyzerSharesOneCircuitAcrossAllReadsAndRestoresOnHalfOpenSuccess() {
        analyzerBreaker.transitionToOpenState();

        assertThat(analyzer.ratings(List.of(1L))).isEmpty();
        assertThat(analyzer.recommendations(1, 1)).isEmpty();
        assertThat(analyzer.similar(2, 1, 1)).isEmpty();
        verifyNoInteractions(timedAnalyzer);
        analyzerBreaker.transitionToHalfOpenState();
        when(timedAnalyzer.getInteractionsCount(any())).thenReturn(List.of(event(1, 1)).iterator());
        assertThat(analyzer.ratings(List.of(1L))).isEqualTo(Map.of(1L, 1D));
        assertThat(analyzerBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void unexpectedClientErrorsArePropagatedWithoutRetry() {
        RuntimeException failure = new IllegalStateException("Некорректный клиент");
        when(timedCollector.collectUserAction(any())).thenThrow(failure);
        when(timedAnalyzer.getInteractionsCount(any())).thenThrow(failure);

        assertThatThrownBy(() -> collector.collect(1, 1, ActionTypeProto.ACTION_VIEW)).isSameAs(failure);
        assertThatThrownBy(() -> analyzer.ratings(List.of(1L))).isSameAs(failure);
        verify(timedCollector, times(1)).collectUserAction(any());
        verify(timedAnalyzer, times(1)).getInteractionsCount(any());
    }

    private CircuitBreaker breaker(String name) {
        return CircuitBreaker.of(name, CircuitBreakerConfig.custom().minimumNumberOfCalls(10)
                .permittedNumberOfCallsInHalfOpenState(1).build());
    }

    private Status status(String code) {
        return Status.fromCode(Status.Code.valueOf(code)).withDescription("Внутренние данные сервера");
    }

    private RecommendedEventProto event(long id, double score) {
        return RecommendedEventProto.newBuilder().setEventId(id).setScore(score).build();
    }

    private Iterator<RecommendedEventProto> brokenStream(Status failure) {
        return new Iterator<>() {

            private boolean emitted;

            @Override
            public boolean hasNext() {
                if (emitted) {
                    throw failure.asRuntimeException();
                }
                return true;
            }

            @Override
            public RecommendedEventProto next() {
                emitted = true;
                return event(1, 0.5);
            }
        };
    }
}
