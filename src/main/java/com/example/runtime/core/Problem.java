package com.example.runtime.core;

import org.springframework.http.HttpStatus;

public class Problem extends RuntimeException {
    private final HttpStatus status;
    public Problem(HttpStatus status, String message) { super(message); this.status = status; }
    public HttpStatus status() { return status; }
    public static Problem bad(String message) { return new Problem(HttpStatus.BAD_REQUEST, message); }
    public static Problem forbidden() { return new Problem(HttpStatus.FORBIDDEN, "Access denied"); }
    public static Problem missing() { return new Problem(HttpStatus.NOT_FOUND, "Resource not found"); }
    public static Problem conflict(String message) { return new Problem(HttpStatus.CONFLICT, message); }
    public static Problem unavailable(String message) { return new Problem(HttpStatus.SERVICE_UNAVAILABLE, message); }
}
