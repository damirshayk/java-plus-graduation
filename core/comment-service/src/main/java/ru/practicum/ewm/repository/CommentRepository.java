package ru.practicum.ewm.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.practicum.ewm.model.Comment;

import java.util.Optional;

@Repository
public interface CommentRepository extends JpaRepository<Comment, Long> {

    @Query("""
            SELECT c
            FROM Comment c
            WHERE c.eventId = :eventId AND c.authorId = :userId
            ORDER BY c.createdOn DESC, c.id DESC
            """)
    Page<Comment> findByEventIdAndUserId(@Param("eventId") Long eventId,
                                         @Param("userId") Long userId,
                                         Pageable pageable);

    @Query("""
            SELECT c
            FROM Comment c
            WHERE c.id = :commentId AND c.authorId = :userId AND c.eventId = :eventId
            """)
    Optional<Comment> findByCommentIdAndUserIdAndEventId(@Param("commentId") Long commentId,
                                                         @Param("userId") Long userId,
                                                         @Param("eventId") Long eventId);
}
