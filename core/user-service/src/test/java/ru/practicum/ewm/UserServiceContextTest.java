package ru.practicum.ewm;

import feign.FeignException;
import feign.Request;
import feign.Response;
import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import ru.practicum.ewm.client.UserDataCleanupClient;
import ru.practicum.ewm.exception.ServiceUnavailableException;
import ru.practicum.ewm.model.User;
import ru.practicum.ewm.repository.UserRepository;
import ru.practicum.ewm.service.UserService;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

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

    @MockBean
    private UserDataCleanupClient cleanupClient;

    @Test
    void shouldUseOnlyUserEntitiesAndOwnMigration() {
        assertThat(entityManagerFactory.getMetamodel().getEntities())
                .extracting(entity -> entity.getJavaType().getName()).containsExactly(User.class.getName());
        assertThat(flyway.info().applied()).hasSize(1);
        assertThat(flyway.info().applied()[0].getScript()).isEqualTo("V1__create_users.sql");
        assertThat(jdbcTemplate.queryForList("""
                SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES
                WHERE TABLE_SCHEMA = 'PUBLIC' AND TABLE_TYPE = 'BASE TABLE'
                """, String.class)).containsExactlyInAnyOrder("USERS", "flyway_schema_history");
    }

    @Test
    void shouldRetainPersistedUserWhenMainIsUnavailable() {
        User user = createUser("retained@example.com");
        Request request = Request.create(Request.HttpMethod.DELETE,
                "http://event-service/internal/users/1/data", Map.of(), null, StandardCharsets.UTF_8, null);
        FeignException failure = FeignException.errorStatus("cleanup", Response.builder()
                .status(503).reason("Недоступен").headers(Map.of()).request(request).build());
        doThrow(failure).when(cleanupClient).deleteUserData(user.getId());

        assertThatThrownBy(() -> userService.deleteUser(user.getId()))
                .isInstanceOf(ServiceUnavailableException.class);
        assertThat(userRepository.findById(user.getId())).isPresent();
    }

    @Test
    void shouldNotHoldTransactionDuringCleanup() {
        User user = createUser("transaction@example.com");
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(userRepository.findById(user.getId())).isPresent();
            return null;
        }).when(cleanupClient).deleteUserData(user.getId());

        userService.deleteUser(user.getId());

        verify(cleanupClient).deleteUserData(user.getId());
        assertThat(userRepository.findById(user.getId())).isEmpty();
    }

    private User createUser(String email) {
        User user = new User();
        user.setName("Пользователь");
        user.setEmail(email);
        return userRepository.save(user);
    }
}
