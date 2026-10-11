package ru.practicum.ewm.stats.analyzer.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "event_similarities")
@IdClass(SimilarityId.class)
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class EventSimilarity {
    @Id
    @Column(name = "event_a")
    private Long eventA;
    @Id
    @Column(name = "event_b")
    private Long eventB;
    @Column(name = "score", nullable = false)
    private double score;
    @Column(name = "source_timestamp", nullable = false)
    private Instant sourceTimestamp;
}
