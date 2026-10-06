package ru.practicum.ewm.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.practicum.ewm.model.Hit;

import java.time.LocalDateTime;
import java.util.List;

public interface HitRepository extends JpaRepository<Hit, Long> {

    // Статистика всех просмотров по всем URI за указанный период
    @Query("""
            SELECT h.app AS app, h.uri AS uri, COUNT(h.id) AS hits
            FROM Hit h
            WHERE h.timestamp BETWEEN :start AND :end
            GROUP BY h.app, h.uri
            ORDER BY COUNT(h.id) DESC, h.app ASC, h.uri ASC
            """)
    List<ViewStatsProjection> findStats(
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end
    );

    // Статистика уникальных просмотров по IP по всем URI за указанный период
    @Query("""
            SELECT h.app AS app, h.uri AS uri, COUNT(DISTINCT h.ip) AS hits
            FROM Hit h
            WHERE h.timestamp BETWEEN :start AND :end
            GROUP BY h.app, h.uri
            ORDER BY COUNT(DISTINCT h.ip) DESC, h.app ASC, h.uri ASC
            """)
    List<ViewStatsProjection> findUniqueStats(
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end
    );

    // Статистика всех просмотров по указанным URI за указанный период
    @Query("""
            SELECT h.app AS app, h.uri AS uri, COUNT(h.id) AS hits
            FROM Hit h
            WHERE h.timestamp BETWEEN :start AND :end
              AND h.uri IN :uris
            GROUP BY h.app, h.uri
            ORDER BY COUNT(h.id) DESC, h.app ASC, h.uri ASC
            """)
    List<ViewStatsProjection> findStatsByUris(
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end,
            @Param("uris") List<String> uris
    );

    // Статистика уникальных просмотров по IP по указанным URI за указанный период
    @Query("""
            SELECT h.app AS app, h.uri AS uri, COUNT(DISTINCT h.ip) AS hits
            FROM Hit h
            WHERE h.timestamp BETWEEN :start AND :end
              AND h.uri IN :uris
            GROUP BY h.app, h.uri
            ORDER BY COUNT(DISTINCT h.ip) DESC, h.app ASC, h.uri ASC
            """)
    List<ViewStatsProjection> findUniqueStatsByUris(
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end,
            @Param("uris") List<String> uris
    );
}
