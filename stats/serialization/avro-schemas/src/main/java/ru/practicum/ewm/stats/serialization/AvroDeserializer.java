package ru.practicum.ewm.stats.serialization;

import org.apache.avro.Schema;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.specific.SpecificDatumReader;
import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.serialization.Deserializer;

import java.io.IOException;
import java.util.Objects;

public abstract class AvroDeserializer<T extends SpecificRecord> implements Deserializer<T> {
    private final Schema schema;

    protected AvroDeserializer(Schema schema) {
        this.schema = Objects.requireNonNull(schema);
    }

    @Override
    public T deserialize(String topic, byte[] data) {
        if (data == null) {
            return null;
        }
        try {
            var decoder = DecoderFactory.get().binaryDecoder(data, null);
            T result = new SpecificDatumReader<T>(schema).read(null, decoder);
            if (!decoder.isEnd()) {
                throw new SerializationException("После сообщения Avro остались лишние данные");
            }
            return result;
        } catch (IOException | RuntimeException exception) {
            throw new SerializationException("Не удалось прочитать сообщение Avro", exception);
        }
    }
}
