package kr.ganzithon.backend.common;

public record ApiError(String code, String message, String requestId, boolean retryable) {}
