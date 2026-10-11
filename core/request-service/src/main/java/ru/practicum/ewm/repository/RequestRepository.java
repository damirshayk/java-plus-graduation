package ru.practicum.ewm.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.practicum.ewm.model.ParticipationRequest;
import ru.practicum.ewm.model.RequestStatus;

import java.util.List;
import java.util.Optional;

@Repository
public interface RequestRepository extends JpaRepository<ParticipationRequest, Long> {

    @Query("SELECT r FROM ParticipationRequest r WHERE r.requesterId = :requesterId ORDER BY r.id")
    List<ParticipationRequest> findAllByRequesterId(@Param("requesterId") Long requesterId);

    @Query("SELECT r FROM ParticipationRequest r WHERE r.eventId = :eventId ORDER BY r.id")
    List<ParticipationRequest> findAllByEventId(@Param("eventId") Long eventId);

    boolean existsByRequesterIdAndEventId(Long requesterId, Long eventId);

    boolean existsByRequesterIdAndEventIdAndStatus(Long requesterId, Long eventId, RequestStatus status);

    long countByEventIdAndStatus(Long eventId, RequestStatus status);

    @Query("""
            SELECT r.eventId, COUNT(r.id)
            FROM ParticipationRequest r
            WHERE r.eventId IN :eventIds AND r.status = :status
            GROUP BY r.eventId
            """)
    List<Object[]> countByEventIdsAndStatus(@Param("eventIds") List<Long> eventIds,
                                            @Param("status") RequestStatus status);

    @Query("SELECT r FROM ParticipationRequest r WHERE r.id IN :requestIds ORDER BY r.id")
    List<ParticipationRequest> findAllByIdIn(@Param("requestIds") List<Long> requestIds);

    @Query("SELECT r.eventId FROM ParticipationRequest r WHERE r.id = :requestId")
    Optional<Long> findEventIdByRequestId(@Param("requestId") Long requestId);
}
