package ru.practicum.ewm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ViewStatsTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldCreateDto() {
        ViewStats dto = ViewStats.builder()
                .app("ewm-main-service")
                .uri("/events/1")
                .hits(10L)
                .build();

        assertThat(dto.getApp()).isEqualTo("ewm-main-service");
        assertThat(dto.getUri()).isEqualTo("/events/1");
        assertThat(dto.getHits()).isEqualTo(10L);
    }

    @Test
    void shouldSerializeToJson() throws Exception {
        ViewStats dto = ViewStats.builder()
                .app("ewm-main-service")
                .uri("/events/1")
                .hits(10L)
                .build();

        String json = objectMapper.writeValueAsString(dto);

        assertThat(json).contains("\"app\":\"ewm-main-service\"");
        assertThat(json).contains("\"uri\":\"/events/1\"");
        assertThat(json).contains("\"hits\":10");
    }

    @Test
    void shouldDeserializeFromJson() throws Exception {
        String json = "{" +
                "    \"app\": \"ewm-main-service\"," +
                "    \"uri\": \"/events/1\"," +
                "    \"hits\": 10" +
                "}";

        ViewStats dto = objectMapper.readValue(json, ViewStats.class);

        assertThat(dto.getApp()).isEqualTo("ewm-main-service");
        assertThat(dto.getUri()).isEqualTo("/events/1");
        assertThat(dto.getHits()).isEqualTo(10L);
    }
}
