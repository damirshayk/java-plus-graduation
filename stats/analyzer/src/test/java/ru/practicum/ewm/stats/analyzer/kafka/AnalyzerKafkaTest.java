package ru.practicum.ewm.stats.analyzer.kafka;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.LongSerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.DeserializationException;
import ru.practicum.ewm.stats.analyzer.config.AnalyzerKafkaConfiguration;
import ru.practicum.ewm.stats.analyzer.service.AnalyzerIngestionService;
import ru.practicum.ewm.stats.avro.ActionTypeAvro;
import ru.practicum.ewm.stats.avro.EventSimilarityAvro;
import ru.practicum.ewm.stats.avro.UserActionAvro;
import ru.practicum.ewm.stats.serialization.AvroSerializer;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AnalyzerKafkaTest {
    private static final Instant TIME = Instant.parse("2026-10-09T00:00:00Z");

    @Test
    void actionsFactoryReadsLongAndAvroWithErrorHandlingSingleWriterAndRecordAck() {
        var configuration = configuration();
        var factory = configuration.analyzerActionsFactory();
        var consumerFactory = (DefaultKafkaConsumerFactory<?, ?>) factory.getConsumerFactory();
        var container = factory.createContainer("stats.user-actions.v1");
        UserActionAvro action = new UserActionAvro(1L, 10L, ActionTypeAvro.VIEW, TIME);

        assertThat(consumerFactory.getConfigurationProperties().get(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG))
                .isEqualTo(false);
        assertThat(consumerFactory.getKeyDeserializer()).isInstanceOf(ErrorHandlingDeserializer.class);
        assertThat(consumerFactory.getValueDeserializer()).isInstanceOf(ErrorHandlingDeserializer.class);
        assertThat(consumerFactory.getKeyDeserializer().deserialize("actions", new LongSerializer().serialize("actions", 1L)))
                .isEqualTo(1L);
        assertThat(consumerFactory.getValueDeserializer().deserialize("actions", new AvroSerializer()
                .serialize("actions", action))).isEqualTo(action);
        assertThat(consumerFactory.getValueDeserializer().deserialize("actions", new byte[]{-1})).isNull();
        assertThat(container.getConcurrency()).isEqualTo(1);
        assertThat(container.getContainerProperties().getAckMode()).isEqualTo(ContainerProperties.AckMode.RECORD);
        assertThat(container.isAutoStartup()).isFalse();
    }

    @Test
    void similaritiesFactoryReadsStringAndAvroAndRetainsIndependentContainer() {
        var factory = configuration().analyzerSimilaritiesFactory();
        var consumerFactory = (DefaultKafkaConsumerFactory<?, ?>) factory.getConsumerFactory();
        var container = factory.createContainer("stats.events-similarity.v1");
        EventSimilarityAvro similarity = new EventSimilarityAvro(1L, 10L, 0.8, TIME);

        assertThat(consumerFactory.getKeyDeserializer()).isInstanceOf(ErrorHandlingDeserializer.class);
        assertThat(consumerFactory.getValueDeserializer()).isInstanceOf(ErrorHandlingDeserializer.class);
        assertThat(consumerFactory.getKeyDeserializer().deserialize("similarities",
                new StringSerializer().serialize("similarities", "1:10"))).isEqualTo("1:10");
        assertThat(consumerFactory.getValueDeserializer().deserialize("similarities", new AvroSerializer()
                .serialize("similarities", similarity))).isEqualTo(similarity);
        assertThat(container.getConcurrency()).isEqualTo(1);
        assertThat(container.getContainerProperties().getAckMode()).isEqualTo(ContainerProperties.AckMode.RECORD);
    }

    @Test
    void handlersRetryDatabaseFailuresAndRecoverInvalidPayloadWithoutAdvancingFailedRecord() {
        var handler = configuration().analyzerErrorHandler();
        Consumer<?, ?> consumer = mock(Consumer.class);
        MessageListenerContainer container = mock(MessageListenerContainer.class);
        ConsumerRecord<Long, UserActionAvro> record = new ConsumerRecord<>("actions", 0, 10, 1L,
                new UserActionAvro(1L, 10L, ActionTypeAvro.VIEW, TIME));

        for (int attempt = 0; attempt < 12; attempt++) {
            assertThat(handler.handleOne(new TransientDataAccessResourceException("Недоступно"),
                    record, consumer, container)).isFalse();
        }
        assertThat(handler.handleOne(new IllegalArgumentException("Некорректное действие"), record, consumer, container))
                .isTrue();
        assertThat(handler.handleOne(new DeserializationException("Некорректный Avro", new byte[]{-1}, false,
                new IllegalStateException()), record, consumer, container)).isTrue();
        assertThat(handler.isAckAfterHandle()).isTrue();
    }

    @Test
    void listenersPersistBothFlowsAndPropagateDatabaseFailureForRetry() {
        AnalyzerIngestionService ingestion = mock(AnalyzerIngestionService.class);
        AnalyzerKafkaListener listener = new AnalyzerKafkaListener(ingestion);
        UserActionAvro action = new UserActionAvro(1L, 10L, ActionTypeAvro.VIEW, TIME);
        EventSimilarityAvro similarity = new EventSimilarityAvro(1L, 10L, 0.8, TIME);

        listener.onUserAction(action);
        listener.onSimilarity(similarity);

        verify(ingestion).saveAction(action);
        verify(ingestion).saveSimilarity(similarity);
        doThrow(new TransientDataAccessResourceException("Недоступно")).when(ingestion).saveAction(action);
        assertThatThrownBy(() -> listener.onUserAction(action)).isInstanceOf(TransientDataAccessResourceException.class);
    }

    private AnalyzerKafkaConfiguration configuration() {
        KafkaProperties properties = new KafkaProperties();
        properties.setBootstrapServers(List.of("localhost:9092"));
        properties.getConsumer().setEnableAutoCommit(false);
        properties.getListener().setAckMode(ContainerProperties.AckMode.RECORD);
        properties.getListener().setAutoStartup(false);
        return new AnalyzerKafkaConfiguration(properties, 1);
    }
}
