package ru.practicum.ewm.stats.collector.service;

import com.google.protobuf.Timestamp;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import ru.practicum.ewm.stats.avro.ActionTypeAvro;
import ru.practicum.ewm.stats.avro.UserActionAvro;
import ru.practicum.ewm.stats.proto.UserActionProto;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.CompletableFuture;

@Service
public class UserActionService {

    private static final long MIN_TIMESTAMP_SECONDS = -62_135_596_800L;
    private static final long MAX_TIMESTAMP_SECONDS = 253_402_300_799L;
    private final KafkaTemplate<Long, UserActionAvro> kafkaTemplate;
    private final String topic;

    public UserActionService(KafkaTemplate<Long, UserActionAvro> kafkaTemplate,
                             @Value("${stats.kafka.topics.user-actions}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    public CompletableFuture<Void> collectUserAction(UserActionProto request) {
        UserActionAvro action = toAvro(request);
        ProducerRecord<Long, UserActionAvro> record = new ProducerRecord<>(topic, null,
                action.getTimestamp().toEpochMilli(), action.getUserId(), action);
        try {
            return kafkaTemplate.send(record).thenApply(result -> null);
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    private UserActionAvro toAvro(UserActionProto request) {
        if (request == null) {
            throw new IllegalArgumentException("Действие пользователя обязательно");
        }
        if (request.getUserId() <= 0 || request.getEventId() <= 0) {
            throw new IllegalArgumentException("Идентификаторы пользователя и события должны быть положительными");
        }
        if (!request.hasTimestamp()) {
            throw new IllegalArgumentException("Время действия обязательно");
        }
        Timestamp timestamp = request.getTimestamp();
        if (timestamp.getSeconds() < MIN_TIMESTAMP_SECONDS || timestamp.getSeconds() > MAX_TIMESTAMP_SECONDS
                || timestamp.getNanos() < 0 || timestamp.getNanos() > 999_999_999) {
            throw new IllegalArgumentException("Некорректное время действия");
        }
        Instant instant = Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos())
                .truncatedTo(ChronoUnit.MILLIS);
        if (instant.toEpochMilli() < 0) {
            throw new IllegalArgumentException("Время действия не поддерживается Kafka: раньше 1970 года");
        }
        ActionTypeAvro actionType = switch (request.getActionType()) {
            case ACTION_VIEW -> ActionTypeAvro.VIEW;
            case ACTION_REGISTER -> ActionTypeAvro.REGISTER;
            case ACTION_LIKE -> ActionTypeAvro.LIKE;
            case UNRECOGNIZED -> throw new IllegalArgumentException("Неизвестный тип действия");
        };
        return new UserActionAvro(request.getUserId(), request.getEventId(), actionType, instant);
    }
}
