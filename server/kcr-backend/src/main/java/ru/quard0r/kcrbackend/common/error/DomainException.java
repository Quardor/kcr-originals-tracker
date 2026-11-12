package ru.quard0r.kcrbackend.common.error;

/**
 * Единственное доменное исключение: HTTP-статус и сообщение несёт ErrorCode,
 * отдельный класс на каждый статус не заводится (см. §6 ARCHITECTURE.md).
 */
public class DomainException extends RuntimeException {

    private final ErrorCode errorCode;

    public DomainException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
