package ru.practicum.ewm.service;

import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.practicum.ewm.RequestServiceApplication;
import ru.practicum.ewm.client.event.EventClient;
import ru.practicum.ewm.client.user.UserClient;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = RequestServiceApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:request-cleanup;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000",
        "spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate"
})
@AutoConfigureMockMvc
class RequestDataCleanupIntegrationTest {
    @SpyBean
    private JdbcTemplate jdbc;
    @Autowired
    private MockMvc mvc;
    @Autowired
    private Flyway flyway;
    @Autowired
    private EntityManagerFactory entityManagerFactory;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @MockBean
    private UserClient users;
    @MockBean
    private EventClient events;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM requests");
        jdbc.update("DELETE FROM request_user_guard");
        jdbc.update("DELETE FROM request_event_guard");
        jdbc.update("""
                INSERT INTO requests(id, event_id, requester_id, status, created)
                VALUES (100, 10, 2, 'PENDING', CURRENT_TIMESTAMP),
                       (200, 20, 1, 'PENDING', CURRENT_TIMESTAMP),
                       (300, 20, 2, 'PENDING', CURRENT_TIMESTAMP),
                       (400, 30, 3, 'CANCELED', CURRENT_TIMESTAMP)
                """);
    }

    @Test
    void ownSchemaMustContainOnlyRequestEntityWithoutForeignServiceTables() {
        assertThat(flyway.info().applied()).hasSize(1);
        assertThat(entityManagerFactory.getMetamodel().getEntities())
                .extracting(entity -> entity.getJavaType().getSimpleName()).containsExactly("ParticipationRequest");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = 'PUBLIC' AND table_name IN ('EVENTS', 'USERS', 'COMMENTS', 'CATEGORIES')
                """, Long.class)).isZero();
        flyway.validate();
    }

    @Test
    void cleanupMustDeleteRequesterAndAllRequestsOnOwnedEvents() throws Exception {
        cleanup(1L, "[10]");
        assertThat(ids()).containsExactly(300L, 400L);
        assertThat(deleting("request_user_guard", "user_id", 1L)).isTrue();
        assertThat(deleting("request_event_guard", "event_id", 10L)).isTrue();
        assertThat(deleting("request_event_guard", "event_id", 20L)).isFalse();
        verifyNoInteractions(users, events);
    }

    @Test
    void emptyOwnedEventsMustLockForeignAuthoredEventsWithoutFreezingThem() throws Exception {
        cleanup(1L, "[]");
        assertThat(ids()).containsExactly(100L, 300L, 400L);
        assertThat(deleting("request_event_guard", "event_id", 20L)).isFalse();
        assertThat(jdbc.queryForList("SELECT event_id FROM request_event_guard", Long.class)).containsExactly(20L);
    }

    @Test
    void duplicateOwnedEventsAndRepeatedCleanupMustBeSafe() throws Exception {
        cleanup(1L, "[20,10,20,10]");
        cleanup(1L, "[10,20]");
        assertThat(ids()).containsExactly(400L);
        assertThat(jdbc.queryForList("SELECT event_id FROM request_event_guard ORDER BY event_id", Long.class))
                .containsExactly(10L, 20L);
        assertThat(deleting("request_event_guard", "event_id", 10L)).isTrue();
        assertThat(deleting("request_event_guard", "event_id", 20L)).isTrue();
    }

    @Test
    void invalidBodiesAndNonPositiveUserIdsMustNotWrite() throws Exception {
        for (String body : new String[]{"", "null", "[null]", "[0]", "[-1]", "[10,null]", "{}", "[10,]"}) {
            mvc.perform(delete("/internal/requests/users/1").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        for (long id : new long[]{0, -1}) {
            mvc.perform(delete("/internal/requests/users/{id}", id).contentType(MediaType.APPLICATION_JSON).content("[]"))
                    .andExpect(status().isBadRequest());
        }
        assertThat(ids()).containsExactly(100L, 200L, 300L, 400L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM request_user_guard", Long.class)).isZero();
    }

    @Test
    void cleanupSqlCountMustBeFixedForOneAndFiftyOneOwnedEvents() throws Exception {
        clearInvocations(jdbc);
        cleanup(11L, "[110]");
        long small = sqlCalls();
        clearInvocations(jdbc);
        cleanup(12L, LongStream.rangeClosed(210, 260).mapToObj(Long::toString)
                .collect(Collectors.joining(",", "[", "]")));
        assertThat(sqlCalls()).isEqualTo(small).isEqualTo(8);
        verifyNoInteractions(users, events);
    }

    @Test
    void cleanupLocksMustUseSortedUnionBeforeInsertAndNotOnlyOwnedIds() throws Exception {
        jdbc.update("INSERT INTO requests(event_id, requester_id, status, created) VALUES (5, 1, 'PENDING', CURRENT_TIMESTAMP)");
        clearInvocations(jdbc);
        cleanup(1L, "[30,10,30]");
        var calls = mockingDetails(jdbc).getInvocations();
        var insert = calls.stream().filter(call -> call.getMethod().getName().equals("update")
                && call.getArgument(0) instanceof String
                && call.<String>getArgument(0).startsWith("INSERT INTO request_event_guard")).findFirst().orElseThrow();
        assertThat((Object[]) insert.getRawArguments()[1]).containsExactly(5L, 10L, 20L, 30L);
        var select = calls.stream().filter(call -> call.getMethod().getName().equals("queryForList")
                && call.getArgument(0) instanceof String
                && call.<String>getArgument(0).contains("FOR UPDATE")).findFirst().orElseThrow();
        assertThat((Object[]) select.getRawArguments()[2]).containsExactly(5L, 10L, 20L, 30L);
        assertThat(deleting("request_event_guard", "event_id", 5L)).isFalse();
        assertThat(deleting("request_event_guard", "event_id", 20L)).isFalse();
    }

    @Test
    void localRollbackMustUndoRequestsAndPermanentMarkers() {
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            try {
                cleanup(1L, "[10]");
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
            throw new IllegalStateException("Проверка отката");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(ids()).containsExactly(100L, 200L, 300L, 400L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM request_user_guard", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM request_event_guard", Long.class)).isZero();
    }

    @Test
    void databaseMustRejectDuplicatePairIncludingCanceledAndInvalidStatus() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO requests(event_id, requester_id, status, created)
                VALUES (30, 3, 'PENDING', CURRENT_TIMESTAMP)
                """)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO requests(event_id, requester_id, status, created)
                VALUES (31, 3, 'UNKNOWN', CURRENT_TIMESTAMP)
                """)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(ids()).containsExactly(100L, 200L, 300L, 400L);
    }

    private void cleanup(Long userId, String body) throws Exception {
        mvc.perform(delete("/internal/requests/users/{id}", userId).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNoContent());
    }

    private List<Long> ids() {
        return jdbc.queryForList("SELECT id FROM requests ORDER BY id", Long.class);
    }

    private boolean deleting(String table, String column, Long id) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT deleting FROM " + table + " WHERE " + column + " = ?", Boolean.class, id));
    }

    private long sqlCalls() {
        Set<String> methods = Set.of("update", "queryForList", "queryForObject");
        return mockingDetails(jdbc).getInvocations().stream().filter(call -> {
            var method = call.getMethod();
            var parameters = method.getParameterTypes();
            return methods.contains(method.getName()) && parameters.length > 0
                    && parameters[parameters.length - 1] == Object[].class;
        }).count();
    }
}
