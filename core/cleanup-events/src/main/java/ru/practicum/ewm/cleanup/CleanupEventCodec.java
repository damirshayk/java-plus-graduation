package ru.practicum.ewm.cleanup;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.DeserializationFeature;

public class CleanupEventCodec {
    private final ObjectMapper mapper;
    private final ObjectReader reader;

    public CleanupEventCodec(ObjectMapper mapper) {
        this.mapper = mapper;
        reader = mapper.readerFor(CommonCleanupEvent.class)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .with(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                .without(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
    }

    public String encode(CommonCleanupEvent event) {
        try {
            return mapper.writeValueAsString(event);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Не удалось сериализовать событие очистки", exception);
        }
    }

    public CommonCleanupEvent decode(String payload) {
        if (payload == null) {
            throw new IllegalArgumentException("Отсутствует сообщение очистки");
        }
        try {
            CommonCleanupEvent event = reader.readValue(payload);
            if (event == null) {
                throw new IllegalArgumentException("Отсутствует событие очистки");
            }
            return event;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Некорректное сообщение очистки", exception);
        }
    }
}
