package ru.practicum.ewm.stats.analyzer;

import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.test.context.TestConstructor;
import ru.practicum.ewm.stats.analyzer.service.AnalyzerIngestionService;
import ru.practicum.ewm.stats.analyzer.service.RecommendationService;
import ru.practicum.ewm.stats.avro.ActionTypeAvro;
import ru.practicum.ewm.stats.avro.EventSimilarityAvro;
import ru.practicum.ewm.stats.avro.UserActionAvro;
import ru.practicum.ewm.stats.proto.RecommendedEventProto;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class AnalyzerPersistenceTest {
    private static final Instant TIME = Instant.parse("2026-10-09T00:00:00Z");
    private final AnalyzerIngestionService ingestion;
    private final RecommendationService recommendations;
    private final JdbcTemplate jdbc;
    private final Flyway flyway;
    private final EntityManagerFactory entityManagerFactory;
    private final KafkaListenerEndpointRegistry registry;

    AnalyzerPersistenceTest(AnalyzerIngestionService ingestion, RecommendationService recommendations,
                            JdbcTemplate jdbc, Flyway flyway, EntityManagerFactory entityManagerFactory,
                            KafkaListenerEndpointRegistry registry) {
        this.ingestion = ingestion;
        this.recommendations = recommendations;
        this.jdbc = jdbc;
        this.flyway = flyway;
        this.entityManagerFactory = entityManagerFactory;
        this.registry = registry;
    }

    @BeforeEach
    void cleanDatabase() {
        jdbc.update("DELETE FROM event_similarities");
        jdbc.update("DELETE FROM user_interactions");
    }

    @Test
    void weakNewActionUpdatesTimeWithoutReducingWeightAndOldStrongActionKeepsNewTime() {
        ingestion.saveAction(action(1, 10, ActionTypeAvro.REGISTER, TIME));
        ingestion.saveAction(action(1, 10, ActionTypeAvro.VIEW, TIME.plusSeconds(20)));
        ingestion.saveAction(action(1, 10, ActionTypeAvro.LIKE, TIME.minusSeconds(20)));
        ingestion.saveAction(action(1, 10, ActionTypeAvro.LIKE, TIME.minusSeconds(20)));
        ingestion.saveAction(action(2, 10, ActionTypeAvro.VIEW, TIME));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_interactions", Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT weight FROM user_interactions WHERE user_id = 1", Double.class))
                .isEqualTo(1);
        assertThat(jdbc.<Instant>queryForObject("SELECT last_interaction_time FROM user_interactions WHERE user_id = 1",
                (row, number) -> row.getTimestamp(1).toInstant())).isEqualTo(TIME.plusSeconds(20));
    }

    @Test
    void similarityUpdatesFollowDeliveryOrderEvenForOlderOriginalTimestamp() {
        ingestion.saveSimilarity(similarity(10, 20, 0.9, TIME));
        ingestion.saveSimilarity(similarity(10, 20, 0.3, TIME.minusSeconds(10)));
        ingestion.saveSimilarity(similarity(10, 20, 0.3, TIME.minusSeconds(10)));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM event_similarities", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT score FROM event_similarities", Double.class)).isEqualTo(0.3);
        assertThat(jdbc.<Instant>queryForObject("SELECT source_timestamp FROM event_similarities",
                (row, number) -> row.getTimestamp(1).toInstant())).isEqualTo(TIME.minusSeconds(10));
    }

    @Test
    void aggregationSumsMaximumWeightsOnceAndKeepsInputOrderWithMissingZero() {
        ingestion.saveAction(action(1, 10, ActionTypeAvro.VIEW, TIME));
        ingestion.saveAction(action(1, 10, ActionTypeAvro.LIKE, TIME));
        ingestion.saveAction(action(2, 10, ActionTypeAvro.REGISTER, TIME));
        ingestion.saveAction(action(3, 20, ActionTypeAvro.VIEW, TIME));

        List<RecommendedEventProto> result = recommendations.interactions(List.of(30L, 10L, 10L, 20L));

        assertThat(result).extracting(RecommendedEventProto::getEventId).containsExactly(30L, 10L, 20L);
        assertThat(result).extracting(RecommendedEventProto::getScore).containsExactly(0.0, 1.8, 0.4);
        assertThat(recommendations.interactions(List.of())).isEmpty();
    }

    @Test
    void similarExcludesEveryKnownEventAndQueriesBothPairSides() {
        ingestion.saveAction(action(1, 20, ActionTypeAvro.VIEW, TIME));
        ingestion.saveSimilarity(similarity(1, 10, 0.8, TIME));
        ingestion.saveSimilarity(similarity(10, 20, 1, TIME));
        ingestion.saveSimilarity(similarity(10, 30, 0.8, TIME));
        ingestion.saveSimilarity(similarity(10, 40, 0, TIME));

        assertThat(recommendations.similar(10, 1, 5)).extracting(RecommendedEventProto::getEventId)
                .containsExactly(1L, 30L);
    }

    @Test
    void personalUsesRecentForCandidatesButFullHistoryForPredictionWithoutNPlusOne() {
        ingestion.saveAction(action(1, 1, ActionTypeAvro.LIKE, TIME.minusSeconds(10)));
        ingestion.saveAction(action(1, 2, ActionTypeAvro.VIEW, TIME));
        ingestion.saveAction(action(1, 3, ActionTypeAvro.VIEW, TIME.plusSeconds(10)));
        ingestion.saveSimilarity(similarity(1, 2, 1, TIME));
        ingestion.saveSimilarity(similarity(3, 10, 0.7, TIME));
        ingestion.saveSimilarity(similarity(1, 10, 0.8, TIME));
        ingestion.saveSimilarity(similarity(2, 20, 0.9, TIME));
        ingestion.saveSimilarity(similarity(1, 30, 1, TIME));
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        List<RecommendedEventProto> result = recommendations.recommendations(1, 2);

        assertThat(result).extracting(RecommendedEventProto::getEventId).containsExactly(10L, 20L);
        assertThat(result.getFirst().getScore()).isCloseTo(0.72, within(1e-12));
        assertThat(statistics.getPrepareStatementCount()).isLessThanOrEqualTo(3);
    }

    @Test
    void candidateLimitIsAppliedBeforePredictionAndLastInteractionControlsRecentHistory() {
        ingestion.saveAction(action(1, 1, ActionTypeAvro.LIKE, TIME.minusSeconds(30)));
        ingestion.saveAction(action(1, 2, ActionTypeAvro.LIKE, TIME.minusSeconds(20)));
        ingestion.saveAction(action(1, 3, ActionTypeAvro.LIKE, TIME.minusSeconds(10)));
        ingestion.saveAction(action(1, 1, ActionTypeAvro.VIEW, TIME));
        ingestion.saveSimilarity(similarity(1, 10, 0.9, TIME));
        ingestion.saveSimilarity(similarity(3, 20, 0.8, TIME));
        ingestion.saveSimilarity(similarity(2, 30, 1, TIME));

        assertThat(recommendations.recommendations(1, 1)).extracting(RecommendedEventProto::getEventId)
                .containsExactly(10L);
    }

    @Test
    void missingHistoryAndSimilaritiesReturnEmptyWithoutQueryingEmptyInLists() {
        assertThat(recommendations.recommendations(99, 10)).isEmpty();
        assertThat(recommendations.similar(99, 99, 10)).isEmpty();
        ingestion.saveAction(action(1, 1, ActionTypeAvro.VIEW, TIME));
        assertThat(recommendations.recommendations(1, 10)).isEmpty();
    }

    @Test
    void constraintsRejectInvalidWeightsPairOrderingAndDuplicateInteraction() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO user_interactions VALUES (?, ?, ?, ?)", 1, 1, 2, TIME))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO event_similarities VALUES (?, ?, ?, ?)", 2, 1, 0.5, TIME))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO event_similarities VALUES (?, ?, ?, ?)", 1, 2, -1, TIME))
                .isInstanceOf(DataIntegrityViolationException.class);
        ingestion.saveAction(action(1, 1, ActionTypeAvro.VIEW, TIME));
        assertThatThrownBy(() -> jdbc.update("INSERT INTO user_interactions VALUES (?, ?, ?, ?)", 1, 1, 0.4, TIME))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void repeatedMigrationKeepsExistingData() {
        ingestion.saveAction(action(1, 1, ActionTypeAvro.VIEW, TIME));

        flyway.migrate();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_interactions", Long.class)).isEqualTo(1);
    }

    @Test
    void invalidActionsAndScoresAreRejectedBeforeDatabaseWrite() {
        assertThatThrownBy(() -> ingestion.saveAction(action(0, 1, ActionTypeAvro.VIEW, TIME)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ingestion.saveAction(action(1, 0, ActionTypeAvro.VIEW, TIME)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ingestion.saveSimilarity(similarity(1, 2, Double.NaN, TIME)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ingestion.saveSimilarity(similarity(1, 1, 0.8, TIME)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_interactions", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM event_similarities", Long.class)).isZero();
    }

    @Test
    void countsAndSimilarKeepQueryCountConstantForManyInputEvents() {
        for (long id = 1; id <= 12; id++) {
            ingestion.saveAction(action(1, id, ActionTypeAvro.VIEW, TIME));
            ingestion.saveSimilarity(similarity(id, 100, 0.5, TIME));
        }
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        assertThat(recommendations.interactions(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L, 12L)))
                .hasSize(12);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
        statistics.clear();
        assertThat(recommendations.similar(100, 2, 12)).hasSize(12);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
    }

    @Test
    void listenerContainersUseIndependentGroupsAndDoNotStartInTestProfile() {
        var actions = registry.getListenerContainer("analyzer-actions");
        var similarities = registry.getListenerContainer("analyzer-similarities");

        assertThat(actions).isNotNull();
        assertThat(similarities).isNotNull();
        assertThat(actions.getContainerProperties().getGroupId()).isEqualTo("analyzer-actions");
        assertThat(similarities.getContainerProperties().getGroupId()).isEqualTo("analyzer-similarities");
        assertThat(actions.isRunning()).isFalse();
        assertThat(similarities.isRunning()).isFalse();
    }

    private UserActionAvro action(long userId, long eventId, ActionTypeAvro type, Instant timestamp) {
        return new UserActionAvro(userId, eventId, type, timestamp);
    }

    private EventSimilarityAvro similarity(long first, long second, double score, Instant timestamp) {
        return new EventSimilarityAvro(first, second, score, timestamp);
    }
}
