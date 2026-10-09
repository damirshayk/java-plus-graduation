package ru.practicum.ewm.exception;

import feign.FeignException;
import feign.codec.DecodeException;

public final class FeignExceptionMapper {

    private FeignExceptionMapper() {
    }

    public static RuntimeException translate(FeignException exception, String unavailableMessage) {
        return translate(exception, unavailableMessage, null);
    }

    public static RuntimeException translate(FeignException exception, String unavailableMessage, String notFoundMessage) {
        if (exception instanceof DecodeException) {
            return exception;
        }
        int status = exception.status();
        if (status == 404 && notFoundMessage != null) {
            return new NotFoundException(notFoundMessage);
        }
        if (status == -1 || (status >= 500 && status < 600)) {
            return new ServiceUnavailableException(unavailableMessage, exception);
        }
        return exception;
    }
}
