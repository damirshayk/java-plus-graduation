package ru.practicum.ewm.model;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.util.List;

@Data
public class EventRequestStatusUpdateRequest {
    @NotEmpty(message = "Список идентификаторов заявок не может быть пустым")
    private List<@NotNull(message = "Идентификатор заявки не может быть null")
            @Positive(message = "Идентификатор заявки должен быть положительным") Long> requestIds;

    @NotNull(message = "Статус не может быть null")
    private RequestUpdateStatus status;
}
