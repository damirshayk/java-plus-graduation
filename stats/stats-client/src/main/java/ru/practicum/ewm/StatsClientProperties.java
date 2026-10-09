package ru.practicum.ewm;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "stats")
public class StatsClientProperties {

    @NotBlank(message = "Идентификатор сервиса статистики не должен быть пустым")
    private String serviceId = "stats-server";

    @Valid
    @NotNull(message = "Настройки поиска сервиса должны быть заданы")
    private Discovery discovery = new Discovery();

    @Valid
    @NotNull(message = "Настройки HTTP должны быть заданы")
    private Http http = new Http();

    @Valid
    @NotNull(message = "Настройки защиты от отказов должны быть заданы")
    private CircuitBreaker circuitBreaker = new CircuitBreaker();

    @Getter
    @Setter
    public static class Discovery {

        @Positive(message = "Количество попыток должно быть положительным")
        private int maxAttempts = 2;

        @Positive(message = "Пауза между попытками должна быть положительной")
        private long backoffMs = 100;
    }

    @Getter
    @Setter
    public static class Http {

        @Positive(message = "Таймаут подключения должен быть положительным")
        private long connectTimeoutMs = 1000;

        @Positive(message = "Таймаут чтения должен быть положительным")
        private long readTimeoutMs = 2000;
    }

    @Getter
    @Setter
    public static class CircuitBreaker {

        @Positive(message = "Размер окна должен быть положительным")
        private int slidingWindowSize = 10;

        @Positive(message = "Минимальное количество вызовов должно быть положительным")
        private int minimumNumberOfCalls = 1;

        @DecimalMin(value = "0", inclusive = false, message = "Порог ошибок должен быть больше 0")
        @DecimalMax(value = "100", message = "Порог ошибок не должен превышать 100")
        private float failureRateThreshold = 50;

        @Positive(message = "Время открытого состояния должно быть положительным")
        private long waitDurationInOpenStateMs = 5000;

        @Positive(message = "Количество пробных вызовов должно быть положительным")
        private int permittedNumberOfCallsInHalfOpenState = 1;
    }
}
