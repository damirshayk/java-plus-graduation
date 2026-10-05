package ru.practicum.ewm.exception;

import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.LocalDateTime;
import java.util.Collections;

@Slf4j
@RestControllerAdvice
public class ErrorHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError handleMethodArgumentNotValid(MethodArgumentNotValidException exception) {
        log.warn("Ошибка проверки тела запроса: {}", exception.getMessage());
        return ApiError.builder()
                .errors(exception.getBindingResult().getFieldErrors().stream()
                        .map(error -> error.getField() + ": " + error.getDefaultMessage())
                        .toList())
                .status(HttpStatus.BAD_REQUEST.name())
                .reason("Некорректный запрос.")
                .message("Проверка тела запроса завершилась ошибкой")
                .timestamp(LocalDateTime.now())
                .build();
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError handleUnreadableMessage(HttpMessageNotReadableException exception) {
        log.warn("Не удалось прочитать тело запроса: {}", exception.getMessage());
        return ApiError.builder()
                .errors(Collections.emptyList())
                .status(HttpStatus.BAD_REQUEST.name())
                .reason("Некорректный запрос.")
                .message("Тело запроса содержит данные неверного формата")
                .timestamp(LocalDateTime.now())
                .build();
    }

    @ExceptionHandler(ConstraintViolationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError handleConstraintViolation(ConstraintViolationException exception) {
        log.warn("Ошибка параметров запроса: {}", exception.getMessage());
        return ApiError.builder()
                .errors(exception.getConstraintViolations().stream()
                        .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage())
                        .toList())
                .status(HttpStatus.BAD_REQUEST.name())
                .reason("Некорректный запрос.")
                .message("Параметры запроса не прошли проверку")
                .timestamp(LocalDateTime.now())
                .build();
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError handleTypeMismatch(MethodArgumentTypeMismatchException exception) {
        log.warn("Неверный формат параметра {}: {}", exception.getName(), exception.getValue());
        return ApiError.builder()
                .errors(Collections.emptyList())
                .status(HttpStatus.BAD_REQUEST.name())
                .reason("Некорректный запрос.")
                .message("Параметр " + exception.getName() + " содержит значение неверного формата")
                .timestamp(LocalDateTime.now())
                .build();
    }

    @ExceptionHandler(ValidationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError handleValidation(ValidationException exception) {
        log.warn("Ошибка валидации: {}", exception.getMessage());
        return ApiError.builder()
                .errors(Collections.emptyList())
                .status(HttpStatus.BAD_REQUEST.name())
                .reason("Некорректный запрос.")
                .message(exception.getMessage())
                .timestamp(LocalDateTime.now())
                .build();
    }

    @ExceptionHandler(ConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiError handleConflict(ConflictException exception) {
        log.warn("Ошибка целостности данных: {}", exception.getMessage());
        return ApiError.builder()
                .errors(Collections.emptyList())
                .status(HttpStatus.CONFLICT.name())
                .reason("Нарушено ограничение целостности данных.")
                .message(exception.getMessage())
                .timestamp(LocalDateTime.now())
                .build();
    }

    @ExceptionHandler(NotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiError handleNotFound(NotFoundException exception) {
        log.warn("Данные не найдены или недоступны: {}", exception.getMessage());
        return ApiError.builder()
                .errors(Collections.emptyList())
                .status(HttpStatus.NOT_FOUND.name())
                .reason("Требуемый объект не найден.")
                .message(exception.getMessage())
                .timestamp(LocalDateTime.now())
                .build();
    }
}
