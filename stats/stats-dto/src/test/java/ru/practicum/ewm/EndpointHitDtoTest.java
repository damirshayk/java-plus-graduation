package ru.practicum.ewm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class EndpointHitDtoTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    @Test
    void shouldCreateDto() {
        LocalDateTime timestamp = LocalDateTime.now();

        EndpointHitDto dto = EndpointHitDto.builder()
                .id(1L)
                .app("ewm-main-service")
                .uri("/events/1")
                .ip("192.168.0.1")
                .timestamp(timestamp)
                .build();

        assertThat(dto.getId()).isEqualTo(1L);
        assertThat(dto.getApp()).isEqualTo("ewm-main-service");
        assertThat(dto.getUri()).isEqualTo("/events/1");
        assertThat(dto.getIp()).isEqualTo("192.168.0.1");
        assertThat(dto.getTimestamp()).isEqualTo(timestamp);
    }

    @Test
    void shouldSerializeToJson() throws Exception {
        LocalDateTime timestamp = LocalDateTime.of(2024, 1, 1, 12, 0, 0);

        EndpointHitDto dto = EndpointHitDto.builder()
                .id(1L)
                .app("ewm-main-service")
                .uri("/events/1")
                .ip("192.168.0.1")
                .timestamp(timestamp)
                .build();

        String json = objectMapper.writeValueAsString(dto);

        assertThat(json).contains("\"id\":1");
        assertThat(json).contains("\"app\":\"ewm-main-service\"");
        assertThat(json).contains("\"uri\":\"/events/1\"");
        assertThat(json).contains("\"ip\":\"192.168.0.1\"");
        assertThat(json).contains("\"timestamp\":\"2024-01-01 12:00:00\"");
    }

    @Test
    void shouldDeserializeFromJson() throws Exception {
        String json = "{" +
                "    \"id\": 1," +
                "    \"app\": \"ewm-main-service\"," +
                "    \"uri\": \"/events/1\"," +
                "    \"ip\": \"192.168.0.1\"," +
                "    \"timestamp\": \"2024-01-01 12:00:00\"" +
                "}";

        EndpointHitDto dto = objectMapper.readValue(json, EndpointHitDto.class);

        assertThat(dto.getId()).isEqualTo(1L);
        assertThat(dto.getApp()).isEqualTo("ewm-main-service");
        assertThat(dto.getUri()).isEqualTo("/events/1");
        assertThat(dto.getIp()).isEqualTo("192.168.0.1");
        assertThat(dto.getTimestamp()).isEqualTo(LocalDateTime.of(2024, 1, 1, 12, 0, 0));
    }
}
