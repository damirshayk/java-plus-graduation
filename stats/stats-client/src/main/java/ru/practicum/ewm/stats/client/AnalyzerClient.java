package ru.practicum.ewm.stats.client;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import ru.practicum.ewm.stats.proto.InteractionsCountRequestProto;
import ru.practicum.ewm.stats.proto.RecommendedEventProto;
import ru.practicum.ewm.stats.proto.SimilarEventsRequestProto;
import ru.practicum.ewm.stats.proto.UserPredictionsRequestProto;
import ru.practicum.ewm.stats.proto.dashboard.RecommendationsControllerGrpc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Slf4j
public class AnalyzerClient {

    private final RecommendationsControllerGrpc.RecommendationsControllerBlockingStub stub;
    private final long timeoutMs;
    private final CircuitBreaker circuitBreaker;

    public AnalyzerClient(RecommendationsControllerGrpc.RecommendationsControllerBlockingStub stub,
                          StatsClientProperties properties, CircuitBreaker circuitBreaker) {
        this.stub = stub;
        this.timeoutMs = properties.getTimeoutMs();
        this.circuitBreaker = circuitBreaker;
    }

    public Map<Long, Double> ratings(List<Long> ids) {
        if (ids == null) {
            throw new IllegalArgumentException("Список идентификаторов событий обязателен");
        }
        Set<Long> uniqueIds = new LinkedHashSet<>();
        for (Long id : ids) {
            if (id == null || id <= 0) {
                throw new IllegalArgumentException("Идентификаторы событий должны быть положительными");
            }
            uniqueIds.add(id);
        }
        if (uniqueIds.isEmpty()) {
            return Map.of();
        }
        InteractionsCountRequestProto request = InteractionsCountRequestProto.newBuilder()
                .addAllEventId(uniqueIds).build();
        return execute(() -> {
            List<RecommendedEventProto> responses = read(timedStub().getInteractionsCount(request), false);
            Map<Long, Double> result = new HashMap<>();
            for (RecommendedEventProto response : responses) {
                result.put(response.getEventId(), response.getScore());
            }
            return Map.copyOf(result);
        }, Map.of());
    }

    public List<RecommendedEventProto> recommendations(long userId, int maxResults) {
        validate(userId, maxResults);
        UserPredictionsRequestProto request = UserPredictionsRequestProto.newBuilder()
                .setUserId(userId).setMaxResults(maxResults).build();
        return execute(() -> read(timedStub().getRecommendationsForUser(request), true), List.of());
    }

    public List<RecommendedEventProto> similar(long eventId, long userId, int maxResults) {
        validate(userId, maxResults);
        if (eventId <= 0) {
            throw new IllegalArgumentException("Идентификатор события должен быть положительным");
        }
        SimilarEventsRequestProto request = SimilarEventsRequestProto.newBuilder()
                .setEventId(eventId).setUserId(userId).setMaxResults(maxResults).build();
        return execute(() -> read(timedStub().getSimilarEvents(request), true), List.of());
    }

    private void validate(long userId, int maxResults) {
        if (userId <= 0 || maxResults <= 0) {
            throw new IllegalArgumentException("Идентификатор пользователя и лимит должны быть положительными");
        }
    }

    private RecommendationsControllerGrpc.RecommendationsControllerBlockingStub timedStub() {
        return stub.withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS);
    }

    private List<RecommendedEventProto> read(Iterator<RecommendedEventProto> stream, boolean normalizedScores) {
        if (stream == null) {
            throw new IllegalStateException("Analyzer вернул отсутствующий поток");
        }
        List<RecommendedEventProto> result = new ArrayList<>();
        Set<Long> ids = new HashSet<>();
        while (stream.hasNext()) {
            RecommendedEventProto response = stream.next();
            if (response == null || response.getEventId() <= 0 || !Double.isFinite(response.getScore())
                    || response.getScore() < 0 || normalizedScores && response.getScore() > 1) {
                throw new IllegalStateException("Analyzer вернул некорректные данные события");
            }
            if (!ids.add(response.getEventId())) {
                throw new IllegalStateException("Analyzer вернул повторный идентификатор события");
            }
            result.add(response);
        }
        return List.copyOf(result);
    }

    private <T> T execute(Supplier<T> operation, T fallback) {
        try {
            return circuitBreaker.executeSupplier(operation);
        } catch (RuntimeException exception) {
            if (!GrpcFailures.isUnavailable(exception)) {
                throw exception;
            }
            log.warn("Analyzer временно недоступен, результат отсутствует: {}", GrpcFailures.code(exception));
            return fallback;
        }
    }
}
