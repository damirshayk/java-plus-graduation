package ru.practicum.ewm.stats.analyzer.repository;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import ru.practicum.ewm.stats.analyzer.model.Interaction;
import ru.practicum.ewm.stats.analyzer.model.InteractionId;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface InteractionRepository extends Repository<Interaction, InteractionId> {

    /**
     * Сохраняет максимальный вес и наиболее позднее время взаимодействия независимо друг от друга.
     *
     * @param userId    идентификатор пользователя
     * @param eventId   идентификатор события
     * @param weight    новый вес взаимодействия
     * @param timestamp новое время последнего взаимодействия
     * @return 1, если взаимодействие найдено, иначе 0
     */
    @Modifying
    @Query(value = """
            UPDATE user_interactions
            SET weight = GREATEST(weight, :weight),
                last_interaction_time = GREATEST(last_interaction_time, :timestamp)
            WHERE user_id = :userId AND event_id = :eventId
            """, nativeQuery = true)
    int updateMaximum(@Param("userId") long userId, @Param("eventId") long eventId,
                      @Param("weight") double weight, @Param("timestamp") Instant timestamp);

    /**
     * Вставляет новое взаимодействие пользователя с событием.
     *
     * @param userId    идентификатор пользователя
     * @param eventId   идентификатор события
     * @param weight    вес взаимодействия
     * @param timestamp время последнего взаимодействия
     */
    @Modifying
    @Query(value = """
            INSERT INTO user_interactions (user_id, event_id, weight, last_interaction_time)
            VALUES (:userId, :eventId, :weight, :timestamp)
            """, nativeQuery = true)
    void insert(@Param("userId") long userId, @Param("eventId") long eventId,
                @Param("weight") double weight, @Param("timestamp") Instant timestamp);

    /**
     * Находит все взаимодействия пользователя, отсортированные по времени последнего взаимодействия (по убыванию)
     * и идентификатору события (по возрастанию).
     *
     * @param userId идентификатор пользователя
     * @return список взаимодействий пользователя
     */
    List<Interaction> findByUserIdOrderByLastInteractionTimeDescEventIdAsc(long userId);

    /**
     * Находит идентификаторы событий, с которыми взаимодействовал пользователь.
     *
     * @param userId идентификатор пользователя
     * @return список идентификаторов событий
     */
    @Query("SELECT i.eventId FROM Interaction i WHERE i.userId = :userId")
    List<Long> findEventIdsByUserId(@Param("userId") long userId);

    /**
     * Вычисляет рейтинг каждого события как сумму максимальных весов взаимодействий всех пользователей.
     *
     * @param eventIds коллекция идентификаторов событий
     * @return список объектов EventWeight, содержащих идентификатор события и сумму весов
     */
    @Query("""
            SELECT i.eventId AS eventId, SUM(i.weight) AS weight
            FROM Interaction i
            WHERE i.eventId IN :eventIds
            GROUP BY i.eventId
            """)
    List<EventWeight> sumWeights(@Param("eventIds") Collection<Long> eventIds);

    interface EventWeight {
        Long getEventId();

        Double getWeight();
    }
}
