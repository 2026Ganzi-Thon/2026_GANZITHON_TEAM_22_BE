package kr.ganzithon.backend.common;

import java.util.UUID;
import org.slf4j.Logger;

/** Records enough location information to diagnose failures without logging user or provider data. */
public final class SafeExceptionLogger {
    private static final int MAX_FRAMES = 12;

    private SafeExceptionLogger() { }

    public static void log(Logger logger, String requestId, Throwable failure) {
        log(logger, requestId, "INTERNAL_ERROR", failure);
    }

    public static void log(Logger logger, String requestId, String code, Throwable failure) {
        if (logger == null || failure == null) return;
        logger.error("requestId={} code={} exception={} frames={}", safeRequestId(requestId), safeCode(code),
            failure.getClass().getName(), safeFrames(failure.getStackTrace()));
    }

    /** A single diagnostic line for known failures; never pass messages, prompts, or exception objects. */
    public static void event(Logger logger, String requestId, String code) {
        if (logger == null) return;
        logger.warn("requestId={} code={}", safeRequestId(requestId), safeCode(code));
    }

    private static String safeCode(String code) {
        return code != null && code.matches("[A-Z][A-Z0-9_]{0,63}") ? code : "UNKNOWN_ERROR";
    }

    private static String safeRequestId(String requestId) {
        try {
            return UUID.fromString(requestId).toString().equals(requestId) ? requestId : "unknown";
        } catch (IllegalArgumentException | NullPointerException ignored) {
            return "unknown";
        }
    }

    private static String safeFrames(StackTraceElement[] frames) {
        if (frames == null || frames.length == 0) return "[]";
        var result = new StringBuilder("[");
        for (int i = 0; i < Math.min(MAX_FRAMES, frames.length); i++) {
            if (i > 0) result.append(", ");
            StackTraceElement frame = frames[i];
            result.append(safeSymbol(frame.getClassName())).append('.')
                .append(safeSymbol(frame.getMethodName())).append(':')
                .append(Math.max(-1, frame.getLineNumber()));
        }
        if (frames.length > MAX_FRAMES) result.append(", ...");
        return result.append(']').toString();
    }

    private static String safeSymbol(String symbol) {
        if (symbol == null) return "unknown";
        StringBuilder safe = new StringBuilder();
        for (int i = 0; i < symbol.length() && safe.length() < 120; i++) {
            char c = symbol.charAt(i);
            if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z'
                    || c >= '0' && c <= '9' || c == '_' || c == '$' || c == '.') safe.append(c);
            else safe.append('_');
        }
        return safe.toString();
    }
}
