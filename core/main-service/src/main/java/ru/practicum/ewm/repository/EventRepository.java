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

import java.util.List;
import java.util.Optional;

public interface EventRepository extends JpaRepository<Event, Long>, JpaSpecificationExecutor<Event> {
    @Override
    @EntityGraph(value = "Event.withInitiatorAndCategory")
    Optional<Event> findById(Long id);

    @EntityGraph(value = "Event.withInitiatorAndCategory")
    List<Event> findAllByInitiatorIdOrderByIdAsc(Long initiatorId, Pageable pageable);

    @Override
    @EntityGraph(value = "Event.withInitiatorAndCategory")
    Page<Event> findAll(@Nullable Specification<Event> spec, Pageable pageable);

    @Override
    @EntityGraph(value = "Event.withInitiatorAndCategory")
    List<Event> findAll(@Nullable Specification<Event> spec, Sort sort);

    boolean existsByCategoryId(Long categoryId);
}
