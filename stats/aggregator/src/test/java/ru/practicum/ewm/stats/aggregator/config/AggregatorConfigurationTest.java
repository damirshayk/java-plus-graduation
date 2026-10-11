package ru.practicum.ewm.stats.aggregator.config;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.SerializationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.DeserializationException;
import ru.practicum.ewm.stats.aggregator.kafka.UserActionListener;
import ru.practicum.ewm.stats.aggregator.service.SimilarityCalculator;
import ru.practicum.ewm.stats.avro.ActionTypeAvro;
import ru.practicum.ewm.stats.avro.UserActionAvro;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AggregatorConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(KafkaAutoConfiguration.class))
            .withUserConfiguration(AggregatorConfiguration.class, UserActionListener.class)
            .withPropertyValues("spring.kafka.listener.auto-startup=false",
                    "spring.kafka.listener.ack-mode=manual_immediate", "spring.kafka.listener.concurrency=1",
                    "spring.kafka.consumer.group-id=aggregator-test", "spring.kafka.consumer.enable-auto-commit=false",
                    "stats.kafka.topics.user-actions=stats.user-actions.v1",
                    "stats.kafka.topics.events-similarity=stats.events-similarity.v1",
                    "stats.kafka.send-timeout-ms=1000", "stats.kafka.retry-backoff-ms=1000",
                    "stats.weights.view=0.2", "stats.weights.register=0.4", "stats.weights.like=0.6");

    @Test
    void shouldConnectRecoveryListenerAndManualOffsetsToTheActualContainer() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            var registry = context.getBean(KafkaListenerEndpointRegistry.class);
            var container = (ConcurrentMessageListenerContainer<?, ?>) registry
                    .getListenerContainer("aggregator-user-actions");

            assertThat(container).isNotNull();
            assertThat(container.getConcurrency()).isEqualTo(1);
            assertThat(container.getContainerProperties().getAckMode())
                    .isEqualTo(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
            assertThat(container.getContainerProperties().getAssignmentCommitOption())
                    .isEqualTo(ContainerProperties.AssignmentCommitOption.NEVER);
            assertThat(container.getContainerProperties().getConsumerRebalanceListener())
                    .isSameAs(context.getBean(UserActionListener.class));
        });
    }

    @Test
    void shouldConnectTheErrorHandlerWithoutAcknowledgingFailedRecords() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            var container = (ConcurrentMessageListenerContainer<?, ?>) context
                    .getBean(KafkaListenerEndpointRegistry.class).getListenerContainer("aggregator-user-actions");
            var errorHandler = context.getBean(DefaultErrorHandler.class);

            assertThat(container.getCommonErrorHandler()).isSameAs(errorHandler);
            assertThat(errorHandler.isAckAfterHandle()).isFalse();
        });
    }

    @Test
    void shouldReadTheSharedActionWeightsFromConfiguration() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            SimilarityCalculator calculator = context.getBean(SimilarityCalculator.class);
            Instant timestamp = Instant.ofEpochMilli(1_800_000_000_123L);
            calculator.apply(calculator.prepare(new UserActionAvro(1L, 10L, ActionTypeAvro.VIEW, timestamp)));

            var update = calculator.prepare(new UserActionAvro(1L, 20L, ActionTypeAvro.LIKE, timestamp));

            assertThat(update.similarities()).hasSize(1);
            assertThat(update.similarities().getFirst().getScore())
                    .isCloseTo(0.2 / Math.sqrt(0.2 * 0.6), within(1e-12));
        });
    }

    @Test
    void shouldSkipInvalidNewRecordAndCommitOnlyItsNextOffset() {
        contextRunner.run(context -> {
            Consumer<?, ?> consumer = assignedConsumer(context, 2);
            var record = new ConsumerRecord<>("stats.user-actions.v1", 0, 2L, 1L, null);
            boolean recovered = context.getBean(DefaultErrorHandler.class).handleOne(
                    new IllegalArgumentException("Некорректное действие"), record, consumer,
                    mock(ConcurrentMessageListenerContainer.class));

            assertThat(recovered).isTrue();
            verify(consumer).commitSync(Map.of(new TopicPartition(record.topic(), 0), new OffsetAndMetadata(3L)));
        });
    }

    @Test
    void shouldSkipInvalidHistoricalRecordWithoutRewindingCommittedOffset() {
        contextRunner.run(context -> {
            Consumer<?, ?> consumer = assignedConsumer(context, 2);
            var record = new ConsumerRecord<>("stats.user-actions.v1", 0, 0L, 1L, null);
            boolean recovered = context.getBean(DefaultErrorHandler.class).handleOne(
                    new IllegalArgumentException("Некорректная история"), record, consumer,
                    mock(ConcurrentMessageListenerContainer.class));

            assertThat(recovered).isTrue();
            verify(consumer, never()).commitSync(any(Map.class));
        });
    }

    @Test
    void shouldRetryTemporaryFailureWithoutCommittingTheRecord() {
        contextRunner.run(context -> {
            Consumer<?, ?> consumer = mock(Consumer.class);
            var record = new ConsumerRecord<>("stats.user-actions.v1", 0, 2L, 1L, null);
            boolean recovered = context.getBean(DefaultErrorHandler.class).handleOne(
                    new IllegalStateException("Брокер недоступен"), record, consumer,
                    mock(ConcurrentMessageListenerContainer.class));

            assertThat(recovered).isFalse();
            verify(consumer, never()).commitSync(any(Map.class));
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldSkipDeserializationFailureAndSeekToTheFollowingRecord(boolean key) {
        contextRunner.run(context -> {
            Consumer<?, ?> consumer = assignedConsumer(context, 2);
            var bad = new ConsumerRecord<>("stats.user-actions.v1", 0, 2L, 1L, null);
            var next = new ConsumerRecord<>("stats.user-actions.v1", 0, 3L, 1L, null);
            context.getBean(DefaultErrorHandler.class).handleRemaining(
                    new DeserializationException("Некорректное сообщение", new byte[]{1}, key,
                            new IllegalArgumentException()),
                    List.of(bad, next), consumer, mock(ConcurrentMessageListenerContainer.class));

            var partition = new TopicPartition(bad.topic(), 0);
            verify(consumer).commitSync(Map.of(partition, new OffsetAndMetadata(3L)));
            verify(consumer).seek(partition, 3L);
        });
    }

    @Test
    void shouldSkipHistoricalFailureAndSeekWithoutRewindingTheCommit() {
        contextRunner.run(context -> {
            Consumer<?, ?> consumer = assignedConsumer(context, 2);
            var bad = new ConsumerRecord<>("stats.user-actions.v1", 0, 0L, 1L, null);
            var next = new ConsumerRecord<>("stats.user-actions.v1", 0, 1L, 1L, null);
            context.getBean(DefaultErrorHandler.class).handleRemaining(
                    new IllegalArgumentException("Некорректная история"),
                    List.of(bad, next), consumer, mock(ConcurrentMessageListenerContainer.class));

            verify(consumer, never()).commitSync(any(Map.class));
            verify(consumer).seek(new TopicPartition(bad.topic(), 0), 1L);
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldRetryTechnicalWrapperEvenWhenItsCauseIsClassifiedAsInvalidData(boolean serializerFailure) {
        contextRunner.run(context -> {
            Consumer<?, ?> consumer = mock(Consumer.class);
            var record = new ConsumerRecord<>("stats.user-actions.v1", 0, 2L, 1L, null);
            RuntimeException cause = serializerFailure
                    ? new SerializationException("Неверный сериализатор ключа", new ClassCastException())
                    : new IllegalArgumentException();
            boolean recovered = context.getBean(DefaultErrorHandler.class).handleOne(
                    new IllegalStateException("Ошибка отправки", cause),
                    record, consumer, mock(ConcurrentMessageListenerContainer.class));

            assertThat(recovered).isFalse();
            verify(consumer, never()).commitSync(any(Map.class));
        });
    }

    @Test
    void shouldRetryRecoveryWhenOffsetCommitFails() {
        contextRunner.run(context -> {
            Consumer<?, ?> consumer = assignedConsumer(context, 2);
            var record = new ConsumerRecord<>("stats.user-actions.v1", 0, 2L, 1L, null);
            var offsets = Map.of(new TopicPartition(record.topic(), 0), new OffsetAndMetadata(3L));
            doThrow(new IllegalStateException("Фиксация недоступна")).doNothing()
                    .when(consumer).commitSync(offsets);
            var handler = context.getBean(DefaultErrorHandler.class);
            var container = mock(ConcurrentMessageListenerContainer.class);

            assertThat(handler.handleOne(new IllegalArgumentException(), record, consumer, container)).isFalse();
            assertThat(handler.handleOne(new IllegalArgumentException(), record, consumer, container)).isTrue();
            verify(consumer, times(2)).commitSync(offsets);
        });
    }

    private Consumer<?, ?> assignedConsumer(ApplicationContext context, long committed) {
        Consumer<?, ?> consumer = mock(Consumer.class);
        var partition = new TopicPartition("stats.user-actions.v1", 0);
        when(consumer.partitionsFor(partition.topic())).thenReturn(List.of(
                new PartitionInfo(partition.topic(), 0, null, new Node[0], new Node[0])));
        when(consumer.committed(Set.of(partition))).thenReturn(Map.of(partition, new OffsetAndMetadata(committed)));
        when(consumer.beginningOffsets(Set.of(partition))).thenReturn(Map.of(partition, 0L));
        when(consumer.endOffsets(Set.of(partition))).thenReturn(Map.of(partition, 100L));
        context.getBean(UserActionListener.class).onPartitionsAssigned(consumer, Set.of(partition));
        return consumer;
    }
}
