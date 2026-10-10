package ru.practicum.ewm.stats.aggregator.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import ru.practicum.ewm.stats.aggregator.kafka.UserActionListener;
import ru.practicum.ewm.stats.aggregator.service.SimilarityCalculator;
import ru.practicum.ewm.stats.avro.ActionTypeAvro;
import ru.practicum.ewm.stats.avro.UserActionAvro;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

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
}
