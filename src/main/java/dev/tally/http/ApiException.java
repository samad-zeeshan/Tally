package dev.tally.http;

/**
 * A client-facing failure the kernel renders as an error envelope. field is nullable.
 */
public final class ApiException extends RuntimeException {
    public final ErrorCode code;
    public final String field;

    public ApiException(ErrorCode code, String message) {
        this(code, null, message);
    }

    public ApiException(ErrorCode code, String field, String message) {
        super(message);
        this.code = code;
        this.field = field;
    }
}
