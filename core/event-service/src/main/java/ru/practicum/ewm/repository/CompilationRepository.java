package ru.practicum.ewm.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.practicum.ewm.model.Compilation;

import java.util.List;
import java.util.Optional;

public interface CompilationRepository extends JpaRepository<Compilation, Long> {

    boolean existsByTitle(String title);

    List<Compilation> findAllByIsPinned(Boolean isPinned, Pageable pageable);

    @Query("""
            SELECT DISTINCT c
            FROM Compilation c
            LEFT JOIN FETCH c.events e
            LEFT JOIN FETCH e.category
            WHERE c.id = :id
            """)
    Optional<Compilation> findByIdWithEvents(@Param("id") Long id);

    @Query("""
            SELECT DISTINCT c
            FROM Compilation c
            LEFT JOIN FETCH c.events e
            LEFT JOIN FETCH e.category
            WHERE c.id IN :ids
            """)
    List<Compilation> findAllWithEventsByIdIn(@Param("ids") List<Long> ids);
}
