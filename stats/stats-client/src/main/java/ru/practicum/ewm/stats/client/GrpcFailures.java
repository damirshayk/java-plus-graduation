package ru.practicum.ewm.stats.client;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.StatusRuntimeException;

final class GrpcFailures {

    private GrpcFailures() {
    }

    static boolean isTemporary(Throwable error) {
        if (!(error instanceof StatusRuntimeException) && !(error instanceof StatusException)) {
            return false;
        }
        return switch (Status.fromThrowable(error).getCode()) {
            case UNAVAILABLE, DEADLINE_EXCEEDED, RESOURCE_EXHAUSTED -> true;
            default -> false;
        };
    }

    static boolean isUnavailable(Throwable error) {
        return isTemporary(error) || error instanceof CallNotPermittedException;
    }

    static String code(Throwable error) {
        return error instanceof CallNotPermittedException ? "CIRCUIT_OPEN" : Status.fromThrowable(error).getCode().name();
    }
}
