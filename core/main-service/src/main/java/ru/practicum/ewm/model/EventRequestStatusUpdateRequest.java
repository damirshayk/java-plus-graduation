package ru.practicum.ewm.model;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class EventRequestStatusUpdateRequest {
    @NotEmpty(message = "Список идентификаторов заявок не может быть пустым")
    private List<Long> requestIds;

    @NotNull(message = "Статус не может быть null")
    private RequestUpdateStatus status;
}
