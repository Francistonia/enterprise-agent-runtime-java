package com.example.runtime.core;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.converter.HttpMessageNotReadableException;

@RestControllerAdvice
public class ApiErrors {
    private static final Logger LOG = LoggerFactory.getLogger(ApiErrors.class);
    @ExceptionHandler(Problem.class)
    ResponseEntity<?> problem(Problem e) {
        return ResponseEntity.status(e.status()).body(Map.of("error", e.getMessage()));
    }
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class, IllegalArgumentException.class})
    ResponseEntity<?> invalid(Exception e) {
        return ResponseEntity.badRequest().body(Map.of("error", "Invalid request"));
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<?> other(Exception e) {
        // Exceptions may include remote headers or SQL values; never echo or log their messages.
        LOG.error("Request failed: {}", e.getClass().getSimpleName());
        return ResponseEntity.internalServerError().body(Map.of("error", "Internal operation failed"));
    }
}
