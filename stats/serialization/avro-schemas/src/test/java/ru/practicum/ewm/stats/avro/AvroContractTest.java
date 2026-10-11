package ru.practicum.ewm.stats.avro;

import org.apache.avro.Schema;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.specific.SpecificDatumReader;
import org.apache.avro.specific.SpecificDatumWriter;
import org.apache.avro.specific.SpecificRecord;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AvroContractTest {

    @Test
    void shouldPreserveRecordFieldsAndMillisecondTimestampContract() {
        Schema userAction = UserActionAvro.getClassSchema();
        Schema similarity = EventSimilarityAvro.getClassSchema();

        assertThat(userAction.getFullName()).isEqualTo("ru.practicum.ewm.stats.avro.UserActionAvro");
        assertThat(userAction.getFields()).extracting(Schema.Field::name)
                .containsExactly("userId", "eventId", "actionType", "timestamp");
        assertThat(userAction.getField("userId").schema().getType()).isEqualTo(Schema.Type.LONG);
        assertThat(userAction.getField("eventId").schema().getType()).isEqualTo(Schema.Type.LONG);
        assertThat(userAction.getField("actionType").schema()).isEqualTo(ActionTypeAvro.getClassSchema());
        assertThat(ActionTypeAvro.getClassSchema().getEnumSymbols()).containsExactly("VIEW", "REGISTER", "LIKE");
        assertThat(similarity.getFullName()).isEqualTo("ru.practicum.ewm.stats.avro.EventSimilarityAvro");
        assertThat(similarity.getFields()).extracting(Schema.Field::name)
                .containsExactly("eventA", "eventB", "score", "timestamp");
        assertThat(similarity.getField("eventA").schema().getType()).isEqualTo(Schema.Type.LONG);
        assertThat(similarity.getField("eventB").schema().getType()).isEqualTo(Schema.Type.LONG);
        assertThat(similarity.getField("score").schema().getType()).isEqualTo(Schema.Type.DOUBLE);
        for (Schema schema : new Schema[]{userAction, similarity}) {
            assertThat(schema.getField("timestamp").schema().getType()).isEqualTo(Schema.Type.LONG);
            assertThat(schema.getField("timestamp").schema().getLogicalType().getName())
                    .isEqualTo("timestamp-millis");
        }
    }

    @Test
    void shouldRoundTripUserActionsWithLargeIdentifiersAndEveryActionType() throws IOException {
        Instant timestamp = Instant.ofEpochMilli(1_800_000_000_123L);

        for (ActionTypeAvro action : ActionTypeAvro.values()) {
            UserActionAvro original = UserActionAvro.newBuilder()
                    .setUserId(3_000_000_000L).setEventId(Long.MAX_VALUE)
                    .setActionType(action).setTimestamp(timestamp).build();

            assertThat(roundTrip(original)).isEqualTo(original);
        }
    }

    @Test
    void shouldRoundTripEventSimilarityWithLargeIdentifiersAndMillisecondTimestamp() throws IOException {
        EventSimilarityAvro original = EventSimilarityAvro.newBuilder()
                .setEventA(3_000_000_000L).setEventB(Long.MAX_VALUE).setScore(0.75)
                .setTimestamp(Instant.ofEpochMilli(1_800_000_000_123L)).build();

        assertThat(roundTrip(original)).isEqualTo(original);
    }

    private <T extends SpecificRecord> T roundTrip(T original) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        var encoder = EncoderFactory.get().binaryEncoder(output, null);
        new SpecificDatumWriter<T>(original.getSchema()).write(original, encoder);
        encoder.flush();

        return new SpecificDatumReader<T>(original.getSchema())
                .read(null, DecoderFactory.get().binaryDecoder(output.toByteArray(), null));
    }
}
