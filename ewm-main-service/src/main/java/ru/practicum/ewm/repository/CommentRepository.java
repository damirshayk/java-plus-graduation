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
            JOIN FETCH c.author
            JOIN FETCH c.event
            WHERE c.event.id = :eventId AND c.author.id = :userId
            ORDER BY c.createdOn DESC, c.id DESC
            """)
    Page<Comment> findByEventIdAndUserId(@Param("eventId") Long eventId,
                                         @Param("userId") Long userId,
                                         Pageable pageable);

    @Query("""
            SELECT c
            FROM Comment c
            JOIN FETCH c.author
            WHERE c.id = :commentId AND c.author.id = :userId
            """)
    Optional<Comment> findByCommentIdAndUserId(@Param("commentId") Long commentId,
                                               @Param("userId") Long userId);
}