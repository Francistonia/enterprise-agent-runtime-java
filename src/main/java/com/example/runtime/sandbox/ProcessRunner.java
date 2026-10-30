package com.example.runtime.sandbox;

import java.time.Duration;
import java.util.List;

public interface ProcessRunner {
    record Result(int exitCode,String stdout,String stderr,boolean truncated,boolean timedOut) {}
    Result run(List<String> argv,Duration timeout,int maxBytes);
    default Result run(List<String> argv,Duration timeout,int maxBytes,String stdin) {
        throw new UnsupportedOperationException("This runner does not accept standard input");
    }
}
