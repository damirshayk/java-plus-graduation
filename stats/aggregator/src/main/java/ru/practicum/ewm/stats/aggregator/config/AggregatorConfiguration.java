package ru.practicum.ewm.stats.aggregator.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ContainerCustomizer;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ConsumerAwareRecordRecoverer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;
import ru.practicum.ewm.stats.aggregator.kafka.UserActionListener;
import ru.practicum.ewm.stats.aggregator.service.SimilarityCalculator;
import ru.practicum.ewm.stats.serialization.ActionWeights;

@Configuration
public class AggregatorConfiguration {

    @Bean
    public ActionWeights actionWeights(@Value("${stats.weights.view}") double view,
                                       @Value("${stats.weights.register}") double register,
                                       @Value("${stats.weights.like}") double like) {
        return new ActionWeights(view, register, like);
    }

    @Bean
    public SimilarityCalculator similarityCalculator(ActionWeights weights) {
        return new SimilarityCalculator(weights);
    }

    @Bean
    public DefaultErrorHandler aggregatorKafkaErrorHandler(
            UserActionListener listener,
            @Value("${stats.kafka.retry-backoff-ms}") long retryBackoffMs) {
        ConsumerAwareRecordRecoverer recoverer = listener::recover;
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer,
                new FixedBackOff(retryBackoffMs, FixedBackOff.UNLIMITED_ATTEMPTS));
        handler.addNotRetryableExceptions(IllegalArgumentException.class);
        // Технические ошибки отправки и фиксации повторяются, даже если причина похожа на ошибку данных.
        handler.addRetryableExceptions(IllegalStateException.class);
        handler.setAckAfterHandle(false);
        handler.setCommitRecovered(false);
        return handler;
    }

    @Bean
    public ContainerCustomizer<Object, Object, ConcurrentMessageListenerContainer<Object, Object>>
            aggregatorContainerCustomizer() {
        return container -> container.getContainerProperties()
                .setAssignmentCommitOption(ContainerProperties.AssignmentCommitOption.NEVER);
    }
}
