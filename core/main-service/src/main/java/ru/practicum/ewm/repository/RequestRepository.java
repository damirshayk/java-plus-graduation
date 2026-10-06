package ru.practicum.ewm.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.practicum.ewm.model.ParticipationRequest;
import ru.practicum.ewm.model.RequestStatus;

import java.util.List;

@Repository
public interface RequestRepository extends JpaRepository<ParticipationRequest, Long> {


    List<ParticipationRequest> findAllByRequesterId(Long requesterId);


    List<ParticipationRequest> findAllByEventId(Long eventId);


    boolean existsByRequesterIdAndEventId(Long requesterId, Long eventId);


    long countByEventIdAndStatus(Long eventId, RequestStatus status);

    @Query("select r.event.id, count(r.id) from ParticipationRequest r " +
            "where r.event.id in :eventIds and r.status = :status group by r.event.id")
    List<Object[]> countByEventIdsAndStatus(@Param("eventIds") List<Long> eventIds,
                                            @Param("status") RequestStatus status);

    List<ParticipationRequest> findAllByIdIn(List<Long> requestIds);
}
