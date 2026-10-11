package ru.practicum.ewm.stats.analyzer.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import ru.practicum.ewm.stats.analyzer.repository.InteractionRepository;
import ru.practicum.ewm.stats.analyzer.repository.SimilarityRepository;
import ru.practicum.ewm.stats.avro.ActionTypeAvro;
import ru.practicum.ewm.stats.avro.UserActionAvro;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AnalyzerIngestionConfigurationTest {
    private static final List<String> WEIGHTS = List.of(
            "stats.weights.view=0.2", "stats.weights.register=0.6", "stats.weights.like=0.9");

    @ParameterizedTest
    @ValueSource(strings = {"stats.weights.view", "stats.weights.register", "stats.weights.like"})
    void everyActionWeightMustBeProvidedByConfiguration(String missingProperty) {
        String[] properties = WEIGHTS.stream()
                .filter(property -> !property.startsWith(missingProperty + "="))
                .toArray(String[]::new);

        context().withPropertyValues(properties).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining(missingProperty);
        });
    }

    @Test
    void configuredWeightsAreUsedForPersistedActions() {
        Instant timestamp = Instant.parse("2026-10-09T00:00:00Z");
        context().withPropertyValues(WEIGHTS.toArray(String[]::new)).run(context -> {
            assertThat(context).hasNotFailed();
            AnalyzerIngestionService ingestion = context.getBean(AnalyzerIngestionService.class);
            InteractionRepository interactions = context.getBean(InteractionRepository.class);

            ingestion.saveAction(new UserActionAvro(1L, 10L, ActionTypeAvro.VIEW, timestamp));
            ingestion.saveAction(new UserActionAvro(1L, 20L, ActionTypeAvro.REGISTER, timestamp));
            ingestion.saveAction(new UserActionAvro(1L, 30L, ActionTypeAvro.LIKE, timestamp));

            verify(interactions).updateMaximum(1L, 10L, 0.2, timestamp);
            verify(interactions).updateMaximum(1L, 20L, 0.6, timestamp);
            verify(interactions).updateMaximum(1L, 30L, 0.9, timestamp);
        });
    }

    private ApplicationContextRunner context() {
        return new ApplicationContextRunner()
                .withBean(PropertySourcesPlaceholderConfigurer.class)
                .withBean(InteractionRepository.class, () -> mock(InteractionRepository.class))
                .withBean(SimilarityRepository.class, () -> mock(SimilarityRepository.class))
                .withUserConfiguration(AnalyzerIngestionService.class);
    }
}
