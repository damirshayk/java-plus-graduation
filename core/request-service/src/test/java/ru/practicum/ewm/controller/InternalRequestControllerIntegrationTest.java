package ru.practicum.ewm.controller;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
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
import ru.practicum.ewm.RequestServiceApplication;
import ru.practicum.ewm.client.event.EventClient;
import ru.practicum.ewm.client.user.UserClient;
import ru.practicum.ewm.service.ConfirmedRequestCounter;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = RequestServiceApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:internal-request-contract;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.show-sql=false",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "ewm.events.min-start-delay-hours=2"
})
@AutoConfigureMockMvc
class InternalRequestControllerIntegrationTest {
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MockMvc mvc;
    @Autowired
    private EntityManagerFactory entityManagerFactory;
    @SpyBean
    private ConfirmedRequestCounter counter;
    @MockBean
    private UserClient users;
    @MockBean
    private EventClient events;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM requests");

        jdbc.update("""
                INSERT INTO requests(id, event_id, requester_id, status, created)
                VALUES (100, 10, 1, 'CONFIRMED', CURRENT_TIMESTAMP),
                       (101, 10, 2, 'CONFIRMED', CURRENT_TIMESTAMP),
                       (102, 10, 3, 'PENDING', CURRENT_TIMESTAMP),
                       (103, 10, 4, 'REJECTED', CURRENT_TIMESTAMP),
                       (104, 10, 5, 'CANCELED', CURRENT_TIMESTAMP),
                       (200, 20, 1, 'CONFIRMED', CURRENT_TIMESTAMP),
                       (201, 20, 2, 'PENDING', CURRENT_TIMESTAMP),
                       (202, 20, 3, 'REJECTED', CURRENT_TIMESTAMP),
                       (203, 20, 4, 'CANCELED', CURRENT_TIMESTAMP),
                       (300, 30, 1, 'PENDING', CURRENT_TIMESTAMP)
                """);
        statistics().clear();
    }

    @Test
    void confirmedCountsMustBeSparseAndIgnoreOtherStatusesUnknownIdsAndDuplicates() throws Exception {
        mvc.perform(post("/internal/requests/confirmed-counts")
                        .contentType(MediaType.APPLICATION_JSON).content("[10,20,10,30,999,20]"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"10\":2,\"20\":1}", true));

        verify(counter).countAll(List.of(10L, 20L, 30L, 999L));
        assertOneGroupedQuery();
        verifyNoInteractions(users, events);
    }

    @Test
    void emptyIdsMustReturnEmptyMapWithoutSql() throws Exception {
        mvc.perform(post("/internal/requests/confirmed-counts")
                        .contentType(MediaType.APPLICATION_JSON).content("[]"))
                .andExpect(status().isOk())
                .andExpect(content().json("{}", true));

        verify(counter).countAll(List.of());
        assertThat(statistics().getPrepareStatementCount()).isZero();
        verifyNoInteractions(users, events);
    }

    @Test
    void invalidBodyAndNullOrNonPositiveIdsMustReturn400WithoutSql() throws Exception {
        for (String body : new String[]{"", "null", "[null]", "[0]", "[-1]", "[10,null]", "[10,0]", "[10,-1]",
                "{}", "[\"bad\"]", "[10,]"}) {
            mvc.perform(post("/internal/requests/confirmed-counts")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }

        assertThat(statistics().getPrepareStatementCount()).isZero();
        verifyNoInteractions(counter, users, events);
    }

    @Test
    void oneAndFiftyOneEventIdsMustEachUseOneGroupedSqlQuery() throws Exception {
        mvc.perform(post("/internal/requests/confirmed-counts")
                        .contentType(MediaType.APPLICATION_JSON).content("[10]"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"10\":2}", true));
        assertOneGroupedQuery();
        statistics().clear();

        String ids = LongStream.rangeClosed(10, 60).mapToObj(Long::toString)
                .collect(Collectors.joining(",", "[", "]"));
        mvc.perform(post("/internal/requests/confirmed-counts")
                        .contentType(MediaType.APPLICATION_JSON).content(ids))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"10\":2,\"20\":1}", true));

        assertOneGroupedQuery();
        verifyNoInteractions(users, events);
    }

    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    private void assertOneGroupedQuery() {
        assertThat(statistics().getPrepareStatementCount()).isEqualTo(1);
        assertThat(statistics().getQueries()).hasSize(1);
        assertThat(statistics().getQueries()[0]).contains("GROUP BY");
    }
}
