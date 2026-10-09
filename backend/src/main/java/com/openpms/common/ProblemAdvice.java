package com.openpms.common;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Every error leaves the API as application/problem+json (architecture.md §4.1). */
@RestControllerAdvice
public class ProblemAdvice {
    private static final Logger log = LoggerFactory.getLogger(ProblemAdvice.class);
    private static final MediaType PROBLEM = MediaType.parseMediaType("application/problem+json");

    public static Map<String, Object> body(int status, String title, String detail, List<ApiException.FieldError> errors, Object current) {
        var m = new LinkedHashMap<String, Object>();
        m.put("type", "about:blank");
        m.put("title", title);
        m.put("status", status);
        if (detail != null) m.put("detail", detail);
        if (errors != null && !errors.isEmpty()) m.put("errors", errors);
        if (current != null) m.put("current", current);
        return m;
    }

    private static ResponseEntity<Object> respond(int status, String title, String detail, List<ApiException.FieldError> errors, Object current) {
        return ResponseEntity.status(status).contentType(PROBLEM).body(body(status, title, detail, errors, current));
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Object> api(ApiException e) {
        return respond(e.status(), e.title(), e.detail(), e.errors(), e.current());
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class})
    public ResponseEntity<Object> badRequest(Exception e) {
        return respond(400, "요청 형식이 올바르지 않습니다.", null, null, null);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Object> noResource(NoResourceFoundException e) {
        return respond(404, "찾을 수 없습니다.", null, null, null);
    }

    /** A constraint the service did not check first (two requests racing, usually). */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Object> integrity(DataIntegrityViolationException e) {
        log.info("constraint violation: {}", e.getMostSpecificCause().getMessage());
        return respond(409, "다른 데이터와 충돌합니다.", "같은 내용이 이미 있거나 다른 곳에서 쓰고 있습니다. 새로 고친 뒤 다시 시도하세요.", null, null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> unexpected(Exception e) {
        log.error("unexpected error", e);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR.value(), "서버 오류가 났습니다.", "잠시 뒤 다시 시도하세요. 계속되면 관리자에게 알려 주세요.", null, null);
    }
}
