package ru.practicum.ewm.stats.analyzer.repository;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import ru.practicum.ewm.stats.analyzer.model.EventSimilarity;
import ru.practicum.ewm.stats.analyzer.model.SimilarityId;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface SimilarityRepository extends Repository<EventSimilarity, SimilarityId> {
    @Modifying
    @Query(value = """
            UPDATE event_similarities
            SET score = :score, source_timestamp = :timestamp
            WHERE event_a = :first AND event_b = :second
            """, nativeQuery = true)
    int update(@Param("first") long first, @Param("second") long second,
               @Param("score") double score, @Param("timestamp") Instant timestamp);

    @Modifying
    @Query(value = """
            INSERT INTO event_similarities (event_a, event_b, score, source_timestamp)
            VALUES (:first, :second, :score, :timestamp)
            """, nativeQuery = true)
    void insert(@Param("first") long first, @Param("second") long second,
                @Param("score") double score, @Param("timestamp") Instant timestamp);

    @Query("SELECT s FROM EventSimilarity s WHERE s.eventA = :eventId OR s.eventB = :eventId")
    List<EventSimilarity> findByEvent(@Param("eventId") long eventId);

    @Query("SELECT s FROM EventSimilarity s WHERE s.eventA IN :eventIds OR s.eventB IN :eventIds")
    List<EventSimilarity> findByEvents(@Param("eventIds") Collection<Long> eventIds);

    @Query("""
            SELECT s FROM EventSimilarity s
            WHERE (s.eventA IN :candidates AND s.eventB IN :knownEvents)
               OR (s.eventB IN :candidates AND s.eventA IN :knownEvents)
            """)
    List<EventSimilarity> findCandidateNeighbors(@Param("candidates") Collection<Long> candidates,
                                                 @Param("knownEvents") Collection<Long> knownEvents);
}
