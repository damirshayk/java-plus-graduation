package ru.practicum.ewm.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.practicum.ewm.model.User;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM User user WHERE user.id = :id")
    int deleteUserById(@Param("id") Long id);

    boolean existsByEmail(String email);

    Optional<User> findByEmail(String email);

    List<User> findAllByIdIn(List<Long> ids);
}
