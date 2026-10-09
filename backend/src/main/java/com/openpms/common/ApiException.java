package com.openpms.common;

import java.util.List;

/**
 * An expected failure with an HTTP status, rendered as RFC 9457 problem details (architecture.md §4.1).
 * The frontend's ApiError reads {@code title}, {@code detail}, {@code errors} and, for 409, {@code current}.
 */
public class ApiException extends RuntimeException {
    public record FieldError(String field, String message) {}

    private final int status;
    private final String title;
    private final String detail;
    private final List<FieldError> errors;
    private final Object current;

    public ApiException(int status, String title, String detail, List<FieldError> errors, Object current) {
        super(detail != null ? detail : title);
        this.status = status;
        this.title = title;
        this.detail = detail;
        this.errors = errors == null ? List.of() : errors;
        this.current = current;
    }

    public ApiException(int status, String title) {
        this(status, title, null, null, null);
    }

    public ApiException(int status, String title, String detail) {
        this(status, title, detail, null, null);
    }

    public static ApiException notFound(String title) {
        return new ApiException(404, title);
    }

    public static ApiException forbidden(String title) {
        return new ApiException(403, title);
    }

    public static ApiException unprocessable(String title, String detail) {
        return new ApiException(422, title, detail);
    }

    /** 422 for one input field; the form shows the message next to that field. */
    public static ApiException field(String field, String message) {
        return new ApiException(422, "입력값을 확인하세요.", message, List.of(new FieldError(field, message)), null);
    }

    public static ApiException conflict(String title, String detail, Object current) {
        return new ApiException(409, title, detail, null, current);
    }

    public int status() {
        return status;
    }

    public String title() {
        return title;
    }

    public String detail() {
        return detail;
    }

    public List<FieldError> errors() {
        return errors;
    }

    public Object current() {
        return current;
    }
}
