package ru.practicum.ewm;

import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import ru.practicum.ewm.model.Event;
import ru.practicum.ewm.client.user.UserClient;
import ru.practicum.ewm.client.CommentCleanupClient;
import ru.practicum.ewm.client.RequestClient;
import ru.practicum.ewm.client.user.UserDirectory;
import ru.practicum.ewm.repository.EventRepository;
import ru.practicum.ewm.service.ConfirmedRequestCounter;
import ru.practicum.ewm.service.EventService;
import ru.practicum.ewm.service.UserDataGuard;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = EwmEventServiceApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:event-context;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.show-sql=false",
        "ewm.events.min-start-delay-hours=2"
})
@ActiveProfiles("test")
class EventServiceContextTest {

    @MockBean
    private UserClient userClient;
    @MockBean
    private CommentCleanupClient commentCleanupClient;

    @MockBean
    private RequestClient requestClient;

    @Autowired
    private JdbcTemplate jdbc;

    private final ApplicationContext context;
    private final EntityManagerFactory entityManagerFactory;
    private final Flyway flyway;

    @Autowired
    EventServiceContextTest(ApplicationContext context, EntityManagerFactory entityManagerFactory, Flyway flyway) {
        this.context = context;
        this.entityManagerFactory = entityManagerFactory;
        this.flyway = flyway;
    }

    @Test
    @DisplayName("Сервис событий содержит только свои сущности и применяет одну миграцию")
    void shouldLoadAllDomainModules() {
        assertThat(context.getBean(UserDirectory.class)).isNotNull();
        assertThat(context.getBean(UserDataGuard.class)).isNotNull();
        assertThat(context.containsBean("userServiceImpl")).isFalse();
        assertThat(context.containsBean("userRepository")).isFalse();
        assertThat(context.getBean(EventService.class)).isNotNull();
        assertThat(context.containsBean("requestServiceImpl")).isFalse();
        assertThat(context.containsBean("requestServiceApplication")).isFalse();
        assertThat(context.containsBean("commentServiceImpl")).isFalse();
        assertThat(context.getBean(ConfirmedRequestCounter.class)).isNotNull();
        assertThat(context.getBean(StatsClient.class)).isNotNull();

        assertThat(context.getBean(EventRepository.class)).isNotNull();
        assertThat(context.containsBean("requestRepository")).isFalse();
        assertThat(context.containsBean("commentRepository")).isFalse();
        assertThat(context.containsBean("eventDirectory")).isFalse();
        assertThat(context.containsBean("ru.practicum.ewm.client.event.EventClient")).isFalse();
        assertThat(jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'PUBLIC' AND table_name IN ('USERS', 'COMMENTS', 'REQUESTS')
                """, String.class)).isEmpty();

        assertThat(entityManagerFactory.getMetamodel().getEntities()).extracting(entity -> entity.getJavaType().getSimpleName())
                .doesNotContain("User", "Comment", "ParticipationRequest");
        assertThat(entityManagerFactory.getMetamodel().entity(Event.class)).isNotNull();
        assertThat(context.getBean(ConfirmedRequestCounter.class).getClass().getSimpleName())
                .contains("RemoteConfirmedRequestCounter");

        assertThat(flyway.info().applied())
                .extracting(migration -> migration.getVersion().getVersion())
                .containsExactly("1");
    }
}
