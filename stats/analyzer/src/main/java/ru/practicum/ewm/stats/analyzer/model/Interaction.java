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
@Table(name = "user_interactions")
@IdClass(InteractionId.class)
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class Interaction {
    @Id
    @Column(name = "user_id")
    private Long userId;
    @Id
    @Column(name = "event_id")
    private Long eventId;
    @Column(name = "weight", nullable = false)
    private double weight;
    @Column(name = "last_interaction_time", nullable = false)
    private Instant lastInteractionTime;
}
