package ru.practicum.ewm.stats.analyzer.config;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.serialization.LongDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.util.backoff.FixedBackOff;
import ru.practicum.ewm.stats.avro.EventSimilarityAvro;
import ru.practicum.ewm.stats.avro.UserActionAvro;
import ru.practicum.ewm.stats.serialization.EventSimilarityDeserializer;
import ru.practicum.ewm.stats.serialization.UserActionDeserializer;

@Slf4j
@Configuration
public class AnalyzerKafkaConfiguration {
    private final KafkaProperties properties;
    private final long retryBackoff;

    public AnalyzerKafkaConfiguration(KafkaProperties properties,
                                      @Value("${stats.kafka.retry-backoff-ms:1000}") long retryBackoff) {
        if (retryBackoff < 0) {
            throw new IllegalArgumentException("Интервал повтора Kafka не может быть отрицательным");
        }
        this.properties = properties;
        this.retryBackoff = retryBackoff;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<Long, UserActionAvro> analyzerActionsFactory() {
        ConcurrentKafkaListenerContainerFactory<Long, UserActionAvro> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(properties.buildConsumerProperties(null),
                () -> new ErrorHandlingDeserializer<>(new LongDeserializer()),
                () -> new ErrorHandlingDeserializer<>(new UserActionDeserializer())));
        configure(factory);
        return factory;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, EventSimilarityAvro> analyzerSimilaritiesFactory() {
        ConcurrentKafkaListenerContainerFactory<String, EventSimilarityAvro> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(properties.buildConsumerProperties(null),
                () -> new ErrorHandlingDeserializer<>(new StringDeserializer()),
                () -> new ErrorHandlingDeserializer<>(new EventSimilarityDeserializer())));
        configure(factory);
        return factory;
    }

    @Bean
    public DefaultErrorHandler analyzerErrorHandler() {
        DefaultErrorHandler handler = new DefaultErrorHandler((record, error) ->
                log.warn("Пропущено некорректное сообщение: topic={}, partition={}, offset={}, type={}",
                        record.topic(), record.partition(), record.offset(), error.getClass().getSimpleName()),
                new FixedBackOff(retryBackoff, FixedBackOff.UNLIMITED_ATTEMPTS));
        handler.addNotRetryableExceptions(IllegalArgumentException.class);
        return handler;
    }

    private void configure(ConcurrentKafkaListenerContainerFactory<?, ?> factory) {
        factory.setConcurrency(1);
        factory.setAutoStartup(properties.getListener().isAutoStartup());
        factory.getContainerProperties().setAckMode(properties.getListener().getAckMode() == null
                ? ContainerProperties.AckMode.RECORD : properties.getListener().getAckMode());
        factory.setCommonErrorHandler(analyzerErrorHandler());
    }
}
