package ru.quard0r.kcrbackend.clients.dto;

/**
 * Переназначение ответственного (§11.4, админ). Эндпоинт одноцелевой: тело всегда
 * применяется целиком — assigneeId задаёт нового ответственного, null освобождает
 * клиента (в отличие от null-skip PATCH карточки).
 */
public record AssignmentUpdateRequest (
        Long assigneeId
){
}
