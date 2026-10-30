package com.example.runtime;

/** Separate JVM process used to test native pipe handling without requiring Docker in CI. */
public class ProcessFixture {
    public static void main(String[] args) throws Exception {
        switch(args[0]) {
            case "echo" -> System.out.write(System.in.readAllBytes());
            case "flood" -> {
                System.out.write(new byte[100_000]);
                System.err.write(new byte[100_000]);
            }
            case "sleep" -> Thread.sleep(10_000);
            default -> throw new IllegalArgumentException();
        }
    }
}
