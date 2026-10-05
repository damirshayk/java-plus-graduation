package ru.practicum.ewm.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;
import ru.practicum.ewm.model.User;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

@DataJpaTest
@ActiveProfiles("test")
class UserRepositoryTest {

    @Autowired
    private UserRepository userRepository;

    private User user;

    @BeforeEach
    void setUp() {
        user = new User();
        user.setName("Test User");
        user.setEmail("test@example.com");
        user = userRepository.save(user);
    }

    @Test
    void shouldSaveAndFindUser() {
        User found = userRepository.findById(user.getId()).orElse(null);

        assertThat(found).isNotNull();
        assertThat(found.getName()).isEqualTo("Test User");
        assertThat(found.getEmail()).isEqualTo("test@example.com");
    }

    @Test
    void shouldCheckExistsByEmail() {
        assertThat(userRepository.existsByEmail("test@example.com")).isTrue();
        assertThat(userRepository.existsByEmail("nonexistent@example.com")).isFalse();
    }

    @Test
    void shouldFindByEmail() {
        Optional<User> found = userRepository.findByEmail("test@example.com");
        assertThat(found).isPresent();
        assertThat(found.get().getName()).isEqualTo("Test User");

        Optional<User> notFound = userRepository.findByEmail("nonexistent@example.com");
        assertThat(notFound).isEmpty();
    }

    @Test
    void shouldFindAllByIdIn() {
        User user2 = new User();
        user2.setName("User 2");
        user2.setEmail("user2@example.com");
        user2 = userRepository.save(user2);

        User user3 = new User();
        user3.setName("User 3");
        user3.setEmail("user3@example.com");
        user3 = userRepository.save(user3);

        List<Long> ids = List.of(user.getId(), user2.getId());
        List<User> users = userRepository.findAllByIdIn(ids);

        assertThat(users).hasSize(2);
        assertThat(users).extracting(User::getId).containsExactlyInAnyOrder(user.getId(), user2.getId());
        assertThat(users).extracting(User::getName).containsExactlyInAnyOrder("Test User", "User 2");
    }

    @Test
    void shouldFindAllWithPagination() {
        for (int i = 1; i <= 5; i++) {
            User newUser = new User();
            newUser.setName("User " + i);
            newUser.setEmail("user" + i + "@example.com");
            userRepository.save(newUser);
        }

        Pageable pageable = PageRequest.of(0, 3);
        var page = userRepository.findAll(pageable);

        assertThat(page.getContent()).hasSize(3);
        assertThat(page.getTotalElements()).isGreaterThanOrEqualTo(6);
    }

    @Test
    void shouldDeleteUser() {
        userRepository.deleteById(user.getId());
        Optional<User> found = userRepository.findById(user.getId());
        assertThat(found).isEmpty();
    }

    @Test
    void shouldEnforceUniqueEmailConstraint() {
        User duplicate = new User();
        duplicate.setName("Duplicate User");
        duplicate.setEmail("test@example.com");

        assertThatExceptionOfType(org.springframework.dao.DataIntegrityViolationException.class)
                .isThrownBy(() -> userRepository.save(duplicate));
    }

    @Test
    void shouldSaveUserWithMinimalFields() {
        User minimalUser = new User();
        minimalUser.setName("Minimal");
        minimalUser.setEmail("minimal@example.com");

        User saved = userRepository.save(minimalUser);
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getName()).isEqualTo("Minimal");
        assertThat(saved.getEmail()).isEqualTo("minimal@example.com");
    }

    private void assertThatThrownBy(Runnable runnable) {
        try {
            runnable.run();
        } catch (Exception e) {
            assertThat(e).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        }
    }
}