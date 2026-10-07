package ru.practicum.ewm.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.lang.Nullable;
import ru.practicum.ewm.model.Event;
import ru.practicum.ewm.dto.event.EventInfoDto;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.time.LocalDateTime;

public interface EventRepository extends JpaRepository<Event, Long>, JpaSpecificationExecutor<Event> {
    @Query("""
            SELECT new ru.practicum.ewm.dto.event.EventInfoDto(
                e.id, e.initiatorId, e.state, e.participantLimit, e.isRequestModeration)
            FROM Event e
            WHERE e.id = :id
            """)
    Optional<EventInfoDto> findInfoById(@Param("id") Long id);

    @Query("SELECT e.eventDate FROM Event e WHERE e.id = :id")
    Optional<LocalDateTime> findEventDateById(@Param("id") Long id);

    @Override
    @EntityGraph(value = "Event.withCategory")
    Optional<Event> findById(Long id);

    @EntityGraph(value = "Event.withCategory")
    List<Event> findAllByInitiatorIdOrderByIdAsc(Long initiatorId, Pageable pageable);

    @Override
    @EntityGraph(value = "Event.withCategory")
    Page<Event> findAll(@Nullable Specification<Event> spec, Pageable pageable);

    @Override
    @EntityGraph(value = "Event.withCategory")
    List<Event> findAll(@Nullable Specification<Event> spec, Sort sort);

    boolean existsByCategoryId(Long categoryId);

    @Override
    @EntityGraph(value = "Event.withCategory")
    List<Event> findAllById(Iterable<Long> ids);
}
