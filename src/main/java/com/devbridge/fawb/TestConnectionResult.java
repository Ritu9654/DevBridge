package com.devbridge.fawb;

/**
 * Outcome of probing one environment (sandbox or design) with the current token.
 * {@code statusCode} is 0 when the request never reached HTTP layer
 * (e.g., DNS failure, connection refused, malformed URL).
 */
public record TestConnectionResult(
        String env,
        boolean success,
        int statusCode,
        String message
) {
    public static TestConnectionResult ok(String env, int statusCode, String message) {
        return new TestConnectionResult(env, true, statusCode, message);
    }

    public static TestConnectionResult fail(String env, int statusCode, String message) {
        return new TestConnectionResult(env, false, statusCode, message);
    }
}
