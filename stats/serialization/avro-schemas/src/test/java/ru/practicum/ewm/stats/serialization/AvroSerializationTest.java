package ru.practicum.ewm.stats.serialization;

import org.apache.kafka.common.errors.SerializationException;
import org.junit.jupiter.api.Test;
import ru.practicum.ewm.stats.avro.ActionTypeAvro;
import ru.practicum.ewm.stats.avro.EventSimilarityAvro;
import ru.practicum.ewm.stats.avro.UserActionAvro;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AvroSerializationTest {
    private final AvroSerializer serializer = new AvroSerializer();

    @Test
    void shouldPreserveUserActionThroughKafkaSerialization() {
        UserActionAvro action = UserActionAvro.newBuilder().setUserId(3_000_000_000L)
                .setEventId(Long.MAX_VALUE).setActionType(ActionTypeAvro.LIKE)
                .setTimestamp(Instant.ofEpochMilli(1_800_000_000_123L)).build();

        byte[] bytes = serializer.serialize("actions", action);

        assertThat(bytes).isNotEmpty();
        assertThat(new UserActionDeserializer().deserialize("actions", bytes)).isEqualTo(action);
    }

    @Test
    void shouldPreserveSimilarityThroughKafkaSerialization() {
        EventSimilarityAvro similarity = EventSimilarityAvro.newBuilder().setEventA(3_000_000_000L)
                .setEventB(Long.MAX_VALUE).setScore(0.75)
                .setTimestamp(Instant.ofEpochMilli(1_800_000_000_123L)).build();

        byte[] bytes = serializer.serialize("similarities", similarity);

        assertThat(bytes).isNotEmpty();
        assertThat(new EventSimilarityDeserializer().deserialize("similarities", bytes)).isEqualTo(similarity);
    }

    @Test
    void shouldPreserveKafkaTombstones() {
        assertThat(serializer.serialize("actions", null)).isNull();
        assertThat(new UserActionDeserializer().deserialize("actions", null)).isNull();
        assertThat(new EventSimilarityDeserializer().deserialize("similarities", null)).isNull();
    }

    @Test
    void shouldRejectMalformedBinaryData() {
        assertThatThrownBy(() -> new UserActionDeserializer().deserialize("actions", new byte[]{(byte) 0xFF}))
                .isInstanceOf(SerializationException.class);
    }
}
