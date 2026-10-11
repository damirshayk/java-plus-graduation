package ru.practicum.ewm.stats.serialization;

import org.apache.avro.io.EncoderFactory;
import org.apache.avro.specific.SpecificDatumWriter;
import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.serialization.Serializer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

public class AvroSerializer implements Serializer<SpecificRecord> {
    @Override
    public byte[] serialize(String topic, SpecificRecord data) {
        if (data == null) {
            return null;
        }
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            var encoder = EncoderFactory.get().binaryEncoder(output, null);
            new SpecificDatumWriter<SpecificRecord>(data.getSchema()).write(data, encoder);
            encoder.flush();
            return output.toByteArray();
        } catch (IOException | RuntimeException exception) {
            throw new SerializationException("Не удалось сериализовать сообщение Avro", exception);
        }
    }
}
