package ru.practicum.ewm.stats.analyzer.grpc;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import ru.practicum.ewm.stats.analyzer.service.RecommendationService;
import ru.practicum.ewm.stats.proto.InteractionsCountRequestProto;
import ru.practicum.ewm.stats.proto.RecommendedEventProto;
import ru.practicum.ewm.stats.proto.SimilarEventsRequestProto;
import ru.practicum.ewm.stats.proto.UserPredictionsRequestProto;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RecommendationsGrpcServiceTest {
    private final RecommendationService service = mock(RecommendationService.class);
    private final RecommendationsGrpcService controller = new RecommendationsGrpcService(service);

    @Test
    void recommendationsStreamEveryResultInOrderAndComplete() {
        when(service.recommendations(1, 2)).thenReturn(List.of(event(10, 0.8), event(20, 0.4)));
        RecordingObserver observer = new RecordingObserver();

        controller.getRecommendationsForUser(UserPredictionsRequestProto.newBuilder()
                .setUserId(1).setMaxResults(2).build(), observer);

        assertThat(observer.values).containsExactly(event(10, 0.8), event(20, 0.4));
        assertThat(observer.completed).isTrue();
        assertThat(observer.error).isNull();
    }

    @Test
    void similarStreamsResultsAndEmptyInteractionsCompleteNormally() {
        when(service.similar(10, 1, 1)).thenReturn(List.of(event(20, 0.8)));
        when(service.interactions(List.of())).thenReturn(List.of());
        RecordingObserver similar = new RecordingObserver();
        RecordingObserver empty = new RecordingObserver();

        controller.getSimilarEvents(SimilarEventsRequestProto.newBuilder()
                .setEventId(10).setUserId(1).setMaxResults(1).build(), similar);
        controller.getInteractionsCount(InteractionsCountRequestProto.getDefaultInstance(), empty);

        assertThat(similar.values).containsExactly(event(20, 0.8));
        assertThat(similar.completed).isTrue();
        assertThat(empty.values).isEmpty();
        assertThat(empty.completed).isTrue();
    }

    @Test
    void countsStreamMissingEventZeroWithoutReordering() {
        when(service.interactions(List.of(30L, 10L))).thenReturn(List.of(event(30, 0), event(10, 1.8)));
        RecordingObserver observer = new RecordingObserver();

        controller.getInteractionsCount(InteractionsCountRequestProto.newBuilder()
                .addEventId(30).addEventId(10).build(), observer);

        assertThat(observer.values).containsExactly(event(30, 0), event(10, 1.8));
        assertThat(observer.completed).isTrue();
    }

    @Test
    void invalidIdsAndLimitsProduceInvalidArgumentWithoutDatabaseCalls() {
        List<RecordingObserver> observers = new ArrayList<>();
        for (long userId : new long[]{0, -1}) {
            RecordingObserver observer = new RecordingObserver();
            observers.add(observer);
            controller.getRecommendationsForUser(UserPredictionsRequestProto.newBuilder()
                    .setUserId(userId).setMaxResults(1).build(), observer);
        }
        for (int limit : new int[]{0, -1}) {
            RecordingObserver observer = new RecordingObserver();
            observers.add(observer);
            controller.getSimilarEvents(SimilarEventsRequestProto.newBuilder()
                    .setEventId(1).setUserId(1).setMaxResults(limit).build(), observer);
        }
        RecordingObserver invalidEvent = new RecordingObserver();
        observers.add(invalidEvent);
        controller.getInteractionsCount(InteractionsCountRequestProto.newBuilder().addEventId(0).build(), invalidEvent);

        for (RecordingObserver observer : observers) {
            assertThat(observer.error).isNotNull();
            assertThat(Status.fromThrowable(observer.error).getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
            assertThat(observer.completed).isFalse();
        }
        verifyNoInteractions(service);
    }

    @Test
    void databaseOutageIsUnavailableAndDoesNotExposeCredentials() {
        when(service.interactions(List.of(1L)))
                .thenThrow(new DataAccessResourceFailureException("password=secret jdbc://internal"));
        RecordingObserver observer = new RecordingObserver();

        controller.getInteractionsCount(InteractionsCountRequestProto.newBuilder().addEventId(1).build(), observer);

        assertThat(observer.error).isNotNull();
        Status status = Status.fromThrowable(observer.error);
        assertThat(status.getCode()).isEqualTo(Status.Code.UNAVAILABLE);
        assertThat(status.getDescription()).doesNotContain("secret", "jdbc", "password");
        assertThat(observer.completed).isFalse();
    }

    @Test
    void unexpectedFailureIsInternalAndDoesNotExposeDatabaseDetails() {
        when(service.recommendations(1, 1)).thenThrow(new IllegalStateException("SELECT internal_secret"));
        RecordingObserver observer = new RecordingObserver();

        controller.getRecommendationsForUser(UserPredictionsRequestProto.newBuilder()
                .setUserId(1).setMaxResults(1).build(), observer);

        assertThat(observer.error).isNotNull();
        Status status = Status.fromThrowable(observer.error);
        assertThat(status.getCode()).isEqualTo(Status.Code.INTERNAL);
        assertThat(status.getDescription()).doesNotContain("SELECT", "internal_secret");
        assertThat(observer.completed).isFalse();
    }

    @Test
    void internalArgumentFailureIsNotMistakenForInvalidClientInput() {
        when(service.interactions(List.of(1L))).thenThrow(new IllegalArgumentException("SELECT password=secret"));
        RecordingObserver observer = new RecordingObserver();

        controller.getInteractionsCount(InteractionsCountRequestProto.newBuilder().addEventId(1).build(), observer);

        assertThat(observer.error).isNotNull();
        Status status = Status.fromThrowable(observer.error);
        assertThat(status.getCode()).isEqualTo(Status.Code.INTERNAL);
        assertThat(status.getDescription()).doesNotContain("SELECT", "password", "secret");
    }

    private RecommendedEventProto event(long eventId, double score) {
        return RecommendedEventProto.newBuilder().setEventId(eventId).setScore(score).build();
    }

    private static class RecordingObserver implements StreamObserver<RecommendedEventProto> {
        private final List<RecommendedEventProto> values = new ArrayList<>();
        private Throwable error;
        private boolean completed;

        @Override
        public void onNext(RecommendedEventProto value) {
            values.add(value);
        }

        @Override
        public void onError(Throwable failure) {
            error = failure;
        }

        @Override
        public void onCompleted() {
            completed = true;
        }
    }
}
