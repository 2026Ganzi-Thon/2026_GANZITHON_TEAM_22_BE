package kr.ganzithon.backend.notification;

import java.util.UUID;

/** A successful ticket means Expo accepted the request, not that the phone displayed it. */
public interface PushSender {
    Result send(String expoPushToken, UUID sessionId);
    record Result(String status, String ticketId) {
        public static Result sent(String ticketId) { return new Result("SENT", ticketId); }
        public static Result failed() { return new Result("FAILED", null); }
        public static Result unknown() { return new Result("DELIVERY_UNKNOWN", null); }
    }
}
