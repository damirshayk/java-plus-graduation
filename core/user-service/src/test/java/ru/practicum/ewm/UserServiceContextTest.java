package ru.practicum.ewm;

import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import ru.practicum.ewm.cleanup.CleanupEventCodec;
import ru.practicum.ewm.cleanup.CleanupEventType;
import ru.practicum.ewm.cleanup.SharedOutboxJdbc;
import ru.practicum.ewm.exception.NotFoundException;
import ru.practicum.ewm.model.User;
import ru.practicum.ewm.repository.UserRepository;
import ru.practicum.ewm.service.UserService;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest
class UserServiceContextTest {

    @Autowired
    private EntityManagerFactory entityManagerFactory;
    @Autowired
    private Flyway flyway;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private UserService userService;
    @Autowired
    private CleanupEventCodec codec;
    @SpyBean
    private SharedOutboxJdbc outbox;
    @MockBean
    private KafkaTemplate<String, String> kafka;

    @BeforeEach
    void clearData() {
        clearInvocations(kafka);
        jdbcTemplate.update("DELETE FROM cleanup_outbox");
        jdbcTemplate.update("DELETE FROM users");
    }

    @Test
    void shouldUseOnlyUserEntitiesAndOwnMigration() {
        assertThat(entityManagerFactory.getMetamodel().getEntities())
                .extracting(entity -> entity.getJavaType().getName()).containsExactly(User.class.getName());
        assertThat(flyway.info().applied()).hasSize(2);
        assertThat(flyway.info().applied()[0].getScript()).isEqualTo("V1__create_users.sql");
        assertThat(jdbcTemplate.queryForList("""
                SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES
                WHERE TABLE_SCHEMA = 'PUBLIC' AND TABLE_TYPE = 'BASE TABLE'
                """, String.class)).containsExactlyInAnyOrder("USERS", "CLEANUP_OUTBOX", "flyway_schema_history");
    }

    @Test
    void shouldDeleteLocallyAndSaveDurableNotificationWithoutBroker() {
        User user = createUser("deleted@example.com");

        userService.deleteUser(user.getId());

        assertThat(userRepository.findById(user.getId())).isEmpty();
        var payload = jdbcTemplate.queryForObject("SELECT payload FROM cleanup_outbox", String.class);
        var event = codec.decode(payload);
        assertThat(event.userId()).isEqualTo(user.getId());
        assertThat(event.type()).isEqualTo(CleanupEventType.USER_DELETED);
        assertThat(event.eventIds()).isEmpty();
        verifyNoInteractions(kafka);
        assertThatThrownBy(() -> userService.deleteUser(user.getId())).isInstanceOf(NotFoundException.class);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM cleanup_outbox", Long.class)).isEqualTo(1);
    }

    @Test
    void outboxFailureMustRollbackUserDeletion() {
        User user = createUser("rollback@example.com");
        doThrow(new IllegalStateException("Сбой outbox")).when(outbox).enqueue(any());

        assertThatThrownBy(() -> userService.deleteUser(user.getId())).isInstanceOf(IllegalStateException.class);

        assertThat(userRepository.findById(user.getId())).isPresent();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM cleanup_outbox", Long.class)).isZero();
        verifyNoInteractions(kafka);
    }

    @Test
    void enqueueMustShareTheUserDeletionTransaction() {
        User user = createUser("transaction@example.com");
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(userRepository.findById(user.getId())).isEmpty();
            return invocation.callRealMethod();
        }).when(outbox).enqueue(any());

        userService.deleteUser(user.getId());

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM cleanup_outbox", Long.class)).isEqualTo(1);
    }

    private User createUser(String email) {
        User user = new User();
        user.setName("Пользователь");
        user.setEmail(email);
        return userRepository.save(user);
    }
}
