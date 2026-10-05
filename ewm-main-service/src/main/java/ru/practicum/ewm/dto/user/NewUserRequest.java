package ru.practicum.ewm.dto.user;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class NewUserRequest {
    // Имя пользователя. Длина имени не менее 2 и не более 250 символов.
    @NotBlank
    @Size(min = 2, max = 250)
    private String name;

    // Электронная почта пользователя. Длина почты не менее 6 и не более 254 символов.
    @NotBlank
    @Email
    @Size(min = 6, max = 254)
    private String email;
}
