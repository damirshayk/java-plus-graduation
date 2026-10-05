package ru.practicum.ewm.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "events", indexes = {
        @Index(name = "idx_events_initiator_id", columnList = "initiator_id"),
        @Index(name = "idx_events_category_id", columnList = "category_id"),
        @Index(name = "idx_events_state_event_date", columnList = "state,event_date")
})
@NamedEntityGraph(
        name = "Event.withInitiatorAndCategory",
        attributeNodes = {
                @NamedAttributeNode("initiator"),
                @NamedAttributeNode("category")
        }
)
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class Event {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 2000)
    private String annotation;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    @Column(name = "created_on", nullable = false)
    private LocalDateTime createdOn;

    @Column(nullable = false, length = 7000)
    private String description;

    @Column(name = "event_date", nullable = false)
    private LocalDateTime eventDate;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "initiator_id", nullable = false)
    private User initiator;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "lat", column = @Column(name = "location_lat", nullable = false)),
            @AttributeOverride(name = "lon", column = @Column(name = "location_lon", nullable = false))
    })
    private Location location;

    @Column(name = "paid", nullable = false)
    private boolean isPaid;

    @Column(name = "participant_limit", nullable = false)
    private int participantLimit;

    @Column(name = "published_on")
    private LocalDateTime publishedOn;

    @Column(name = "request_moderation", nullable = false)
    private boolean isRequestModeration = true;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EventState state = EventState.PENDING;

    @Column(nullable = false, length = 120)
    private String title;
}
