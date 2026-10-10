package ru.practicum.ewm.stats.client;

import com.google.protobuf.Timestamp;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import ru.practicum.ewm.stats.proto.ActionTypeProto;
import ru.practicum.ewm.stats.proto.UserActionProto;
import ru.practicum.ewm.stats.proto.collector.UserActionControllerGrpc;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

@Slf4j
public class CollectorClient {

    private final UserActionControllerGrpc.UserActionControllerBlockingStub stub;
    private final long timeoutMs;
    private final CircuitBreaker circuitBreaker;

    public CollectorClient(UserActionControllerGrpc.UserActionControllerBlockingStub stub,
                           StatsClientProperties properties, CircuitBreaker circuitBreaker) {
        this.stub = stub;
        this.timeoutMs = properties.getTimeoutMs();
        this.circuitBreaker = circuitBreaker;
    }

    public void collect(long userId, long eventId, ActionTypeProto type) {
        if (userId <= 0 || eventId <= 0) {
            throw new IllegalArgumentException("Идентификаторы пользователя и события должны быть положительными");
        }
        if (type == null || type == ActionTypeProto.UNRECOGNIZED) {
            throw new IllegalArgumentException("Требуется известный тип действия");
        }
        Instant now = Instant.now();
        UserActionProto request = UserActionProto.newBuilder().setUserId(userId).setEventId(eventId)
                .setActionType(type).setTimestamp(Timestamp.newBuilder()
                        .setSeconds(now.getEpochSecond()).setNanos(now.getNano())).build();
        try {
            circuitBreaker.executeSupplier(() -> stub.withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS)
                    .collectUserAction(request));
        } catch (RuntimeException exception) {
            if (!GrpcFailures.isUnavailable(exception)) {
                throw exception;
            }
            log.warn("Collector временно недоступен, действие пропущено: {}", GrpcFailures.code(exception));
        }
    }
}
