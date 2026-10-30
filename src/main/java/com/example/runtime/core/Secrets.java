package com.example.runtime.core;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class Secrets {
    private final Environment env;
    public Secrets(Environment env) { this.env = env; }
    public String get(String reference) {
        if (reference == null || !reference.matches("RUNTIME_SECRET_[A-Z0-9_]{1,100}"))
            throw Problem.bad("Credential reference must use RUNTIME_SECRET_*");
        String value = env.getProperty(reference);
        if (value == null || value.isBlank()) throw Problem.unavailable("Required credential is not configured");
        return value;
    }
}
