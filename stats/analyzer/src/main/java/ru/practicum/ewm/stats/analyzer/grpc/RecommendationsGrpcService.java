package ru.practicum.ewm.stats.analyzer.grpc;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.service.GrpcService;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import ru.practicum.ewm.stats.analyzer.service.RecommendationService;
import ru.practicum.ewm.stats.proto.InteractionsCountRequestProto;
import ru.practicum.ewm.stats.proto.RecommendedEventProto;
import ru.practicum.ewm.stats.proto.SimilarEventsRequestProto;
import ru.practicum.ewm.stats.proto.UserPredictionsRequestProto;
import ru.practicum.ewm.stats.proto.dashboard.RecommendationsControllerGrpc;

import java.util.List;
import java.util.function.Supplier;

@Slf4j
@GrpcService
public class RecommendationsGrpcService extends RecommendationsControllerGrpc.RecommendationsControllerImplBase {
    private final RecommendationService service;

    public RecommendationsGrpcService(RecommendationService service) {
        this.service = service;
    }

    @Override
    public void getRecommendationsForUser(UserPredictionsRequestProto request,
                                           StreamObserver<RecommendedEventProto> observer) {
        if (!validRequest(request.getUserId() > 0 && request.getMaxResults() > 0, observer)) {
            return;
        }
        stream(() -> service.recommendations(request.getUserId(), request.getMaxResults()), observer);
    }

    @Override
    public void getSimilarEvents(SimilarEventsRequestProto request, StreamObserver<RecommendedEventProto> observer) {
        if (!validRequest(request.getUserId() > 0 && request.getEventId() > 0 && request.getMaxResults() > 0, observer)) {
            return;
        }
        stream(() -> service.similar(request.getEventId(), request.getUserId(), request.getMaxResults()), observer);
    }

    @Override
    public void getInteractionsCount(InteractionsCountRequestProto request,
                                     StreamObserver<RecommendedEventProto> observer) {
        if (!validRequest(request.getEventIdList().stream().allMatch(id -> id > 0), observer)) {
            return;
        }
        stream(() -> service.interactions(request.getEventIdList()), observer);
    }

    private boolean validRequest(boolean valid, StreamObserver<RecommendedEventProto> observer) {
        if (!valid) {
            observer.onError(Status.INVALID_ARGUMENT.withDescription("Идентификаторы и лимит должны быть положительными")
                    .asRuntimeException());
        }
        return valid;
    }

    private void stream(Supplier<List<RecommendedEventProto>> results, StreamObserver<RecommendedEventProto> observer) {
        try {
            List<RecommendedEventProto> events = results.get();
            events.forEach(observer::onNext);
            observer.onCompleted();
        } catch (TransientDataAccessException | DataAccessResourceFailureException error) {
            log.warn("Хранилище рекомендаций недоступно: {}", error.getClass().getSimpleName());
            observer.onError(Status.UNAVAILABLE.withDescription("Хранилище рекомендаций временно недоступно")
                    .asRuntimeException());
        } catch (RuntimeException error) {
            log.error("Не удалось получить рекомендации: {}", error.getClass().getSimpleName());
            observer.onError(Status.INTERNAL.withDescription("Не удалось получить рекомендации").asRuntimeException());
        }
    }
}
