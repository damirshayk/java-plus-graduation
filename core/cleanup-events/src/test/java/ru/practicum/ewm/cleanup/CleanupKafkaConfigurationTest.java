package ru.practicum.ewm.cleanup;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.TopicConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.listener.MessageListenerContainer;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CleanupKafkaConfigurationTest {
    private final CleanupKafkaConfiguration configuration = new CleanupKafkaConfiguration();

    @Test
    void producerFactoryMustBeManagedBySpringToCloseItsResources() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                    "spring.kafka.admin.auto-create", false, "ewm.cleanup.listener-enabled", false)));
            context.registerBean(KafkaProperties.class, KafkaProperties::new);
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.register(CleanupKafkaConfiguration.class);
            context.refresh();

            var factory = context.getBean(DefaultKafkaProducerFactory.class);
            assertThat(context.getBean(KafkaTemplate.class).getProducerFactory()).isSameAs(factory);
        }
    }

    @Test
    void configurationMustUseConfirmedProducerRecordAckAndUnlimitedTopicRetention() {
        var producer = configuration.cleanupProducerFactory(new KafkaProperties()).getConfigurationProperties();
        assertThat(producer.get(ProducerConfig.ACKS_CONFIG)).isEqualTo("all");
        assertThat(producer.get(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG)).isEqualTo(true);
        var factory = configuration.kafkaListenerContainerFactory(new KafkaProperties(), 0, false);
        assertThat(factory.getContainerProperties().getAckMode()).isEqualTo(ContainerProperties.AckMode.RECORD);
        assertThat(factory.getConsumerFactory().getConfigurationProperties().get(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG)).isEqualTo(false);
        assertThat(factory.createContainer("cleanup").isAutoStartup()).isFalse();
        var topic = configuration.cleanupTopic("cleanup.v1");
        assertThat(topic.configs()).containsEntry(TopicConfig.RETENTION_MS_CONFIG, "-1")
                .containsEntry(TopicConfig.RETENTION_BYTES_CONFIG, "-1")
                .containsEntry(TopicConfig.CLEANUP_POLICY_CONFIG, "delete");
    }

    @Test
    @SuppressWarnings("unchecked")
    void databaseFailuresMustNotBeSkippedEvenAfterDefaultTenAttempts() {
        var factory = configuration.kafkaListenerContainerFactory(new KafkaProperties(), 0, false);
        var handler = factory.createContainer("cleanup").getCommonErrorHandler();
        Consumer<String, String> consumer = mock(Consumer.class);
        MessageListenerContainer container = mock(MessageListenerContainer.class);
        when(container.isRunning()).thenReturn(true);
        var record = new ConsumerRecord<String, String>("cleanup", 0, 42, "1", "{}");
        var failure = new ListenerExecutionFailedException("Ошибка обработчика", new DataAccessResourceFailureException("База недоступна"));

        for (int attempt = 0; attempt < 12; attempt++) {
            assertThatThrownBy(() -> handler.handleRemaining(failure, List.of(record), consumer, container))
                    .isInstanceOf(KafkaException.class);
        }

        verify(consumer, times(12)).seek(new TopicPartition("cleanup", 0), 42L);
        verify(consumer, never()).commitSync(anyMap());
        verify(container, never()).stopAbnormally(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void invalidPayloadMustStopContainerWithoutAcknowledgingRecord() {
        var factory = configuration.kafkaListenerContainerFactory(new KafkaProperties(), 0, false);
        var handler = factory.createContainer("cleanup").getCommonErrorHandler();
        Consumer<String, String> consumer = mock(Consumer.class);
        MessageListenerContainer container = mock(MessageListenerContainer.class);
        var failure = new ListenerExecutionFailedException("Ошибка обработчика", new IllegalArgumentException("Некорректный JSON"));

        assertThatThrownBy(() -> handler.handleRemaining(failure, List.of(), consumer, container))
                .isInstanceOf(KafkaException.class);

        verify(container, timeout(2000)).stopAbnormally(any());
        verifyNoInteractions(consumer);
    }
}
