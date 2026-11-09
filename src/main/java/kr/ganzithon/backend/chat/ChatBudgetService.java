package kr.ganzithon.backend.chat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import kr.ganzithon.backend.common.ApiException;
import kr.ganzithon.backend.common.PortableJdbc;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Durable, conservative application spending estimates. These are not the provider's billing balance. */
@Service
public class ChatBudgetService {
    private static final BigDecimal NANOS_PER_USD = new BigDecimal("1000000000");
    // The response deadline is at most 120 seconds. Allow extra time for provider and DB cleanup.
    private static final Duration RESERVATION_TIMEOUT = Duration.ofMinutes(5);
    private final PortableJdbc jdbc;
    private final Clock clock;
    private final String scope;
    private final long dailyLimit;
    private final long monthlyLimit;
    private final int userDailyLimit;
    private final long billingEpoch;

    public ChatBudgetService(JdbcTemplate jdbc, Clock clock,
            @Value("${ganzi.chat.budget.scope:openai}") String scope,
            @Value("${ganzi.chat.budget.daily-usd:1}") BigDecimal dailyUsd,
            @Value("${ganzi.chat.budget.monthly-usd:10}") BigDecimal monthlyUsd,
            @Value("${ganzi.chat.budget.user-daily-requests:100}") int userDailyLimit,
            @Value("${ganzi.chat.budget.billing-epoch:1}") long billingEpoch) {
        this.jdbc = new PortableJdbc(jdbc);
        this.clock = clock;
        if (!scope.matches("[A-Za-z0-9_-]{1,32}") || userDailyLimit < 1 || userDailyLimit > 100000
                || billingEpoch < 1) throw new IllegalArgumentException("Invalid chat budget settings");
        this.scope = scope;
        this.dailyLimit = money(dailyUsd);
        this.monthlyLimit = money(monthlyUsd);
        this.userDailyLimit = userDailyLimit;
        this.billingEpoch = billingEpoch;
    }

    public record Reservation(UUID id) { }
    public record PeriodUsage(LocalDate startsOn, long requests, long inputTokens,
                              long cachedInputTokens, long outputTokens, BigDecimal estimatedUsd,
                              BigDecimal reservedUsd) { }
    public record UsageView(String timeZone, PeriodUsage daily, PeriodUsage monthly,
                            int dailyRequestLimit, String availability) { }
    private record Event(UUID id, LocalDate day, LocalDate month, long reserved,
                         long inputRate, long outputRate, String status) { }
    private record AdmittedRequest(String hash, String status, Instant createdAt) { }

    @Transactional
    public Reservation reserve(UUID userId, UUID requestId, String requestHash, ChatProvider.CostQuote quote) {
        // Use the same owner lock order as account deletion and other owned writes.
        requireOwner(userId, true);
        lockBillingState();
        List<AdmittedRequest> admitted = jdbc.query("SELECT request_hash,status,created_at FROM chat_usage_events "
                        + "WHERE scope=? AND user_id=? AND client_request_id=?",
                (rs, row) -> new AdmittedRequest(rs.getString("request_hash"), rs.getString("status"),
                        PortableJdbc.instant(rs, "created_at")), scope, userId, requestId);
        if (!admitted.isEmpty()) {
            AdmittedRequest previous = admitted.getFirst();
            if (!requestHash.equals(previous.hash())) throw new ApiException(409, "CLIENT_REQUEST_ID_REUSED",
                    "This request ID was used with different input");
            if ("RESERVED".equals(previous.status()) && previous.createdAt().isAfter(reservationCutoff())) {
                throw new ApiException(409, "REQUEST_IN_PROGRESS", "This exchange is still processing");
            }
            throw new ApiException(409, "CHAT_RESULT_UNAVAILABLE",
                    "This request was already admitted; look for a saved response");
        }
        String blockedReason = blockedReason();
        if (blockedReason != null) throw failure(blockedReason);
        long inputRate = switch (quote.model()) {
            case "gpt-6-luna" -> 250; // Standard input, including a conservative 2.5x cache-write allowance.
            case "gpt-6.1-terra" -> 2500;
            default -> throw failure("AI_MODEL_PRICE_NOT_CONFIGURED");
        };
        long outputRate = quote.model().equals("gpt-6-luna") ? 500 : 5000;
        if (quote.maxInputTokens() < 1 || quote.maxInputTokens() > 200000
                || quote.maxOutputTokens() < 1 || quote.maxOutputTokens() > 4096) {
            throw failure("AI_REQUEST_TOO_LARGE");
        }
        LocalDate day = today();
        LocalDate month = day.withDayOfMonth(1);
        long reserved = Math.addExact(Math.multiplyExact(quote.maxInputTokens(), inputRate),
                Math.multiplyExact(quote.maxOutputTokens(), outputRate));
        ensurePeriod("MONTH", month);
        ensurePeriod("DAY", day);
        if (total("MONTH", month) > monthlyLimit - reserved || total("DAY", day) > dailyLimit - reserved) {
            throw failure("AI_BUDGET_EXCEEDED");
        }
        Long requests = jdbc.queryForObject("SELECT COUNT(*) FROM chat_usage_events "
                + "WHERE scope=? AND user_id=? AND day_start=?", Long.class, scope, userId, day);
        if (requests >= userDailyLimit) throw failure("AI_DAILY_USAGE_EXCEEDED");
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO chat_usage_events (id,scope,user_id,client_request_id,request_hash,model,"
                + "day_start,month_start,reserved_nano_usd,input_nano_rate,output_nano_rate,max_input_tokens,"
                + "max_output_tokens,status,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,'RESERVED',?)",
                id, scope, userId, requestId, requestHash, quote.model(), day, month, reserved,
                inputRate, outputRate, quote.maxInputTokens(), quote.maxOutputTokens(), clock.instant());
        adjust("MONTH", month, 0, reserved);
        adjust("DAY", day, 0, reserved);
        return new Reservation(id);
    }

    /** Settle by reservation ID even if the account was deleted while the provider was running. */
    @Transactional
    public void settle(Reservation reservation, ChatProvider.Usage usage, boolean chargeableUnknown,
                       String failureCode) {
        lockBillingState();
        List<Event> events = jdbc.query("SELECT id,day_start,month_start,reserved_nano_usd,input_nano_rate,"
                + "output_nano_rate,status FROM chat_usage_events WHERE scope=? AND id=? FOR UPDATE",
                (rs, row) -> new Event(PortableJdbc.uuid(rs, "id"), rs.getObject("day_start", LocalDate.class),
                    rs.getObject("month_start", LocalDate.class), rs.getLong("reserved_nano_usd"),
                    rs.getLong("input_nano_rate"), rs.getLong("output_nano_rate"), rs.getString("status")),
                scope, reservation.id());
        if (events.isEmpty()) throw new IllegalStateException("Missing AI spending reservation");
        Event event = events.getFirst();
        if (!event.status().equals("RESERVED")) return;
        settleEvent(event, usage, chargeableUnknown, failureCode);
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    @Transactional
    public void settleAbandonedReservations() {
        Instant cutoff = reservationCutoff();
        String selection = "FROM chat_usage_events WHERE scope=? AND status='RESERVED' AND created_at<=?";
        if (jdbc.queryForList("SELECT id " + selection + " LIMIT 1", UUID.class, scope, cutoff).isEmpty()) return;
        lockBillingState();
        List<Event> abandoned = jdbc.query("SELECT id,day_start,month_start,reserved_nano_usd,input_nano_rate,"
                        + "output_nano_rate,status " + selection + " ORDER BY created_at,id LIMIT 100 FOR UPDATE",
                (rs, row) -> new Event(PortableJdbc.uuid(rs, "id"), rs.getObject("day_start", LocalDate.class),
                    rs.getObject("month_start", LocalDate.class), rs.getLong("reserved_nano_usd"),
                    rs.getLong("input_nano_rate"), rs.getLong("output_nano_rate"), rs.getString("status")), scope, cutoff);
        for (Event event : abandoned) settleEvent(event, null, true, "CHAT_RESULT_UNAVAILABLE");
    }

    private Instant reservationCutoff() { return clock.instant().minus(RESERVATION_TIMEOUT); }

    private void settleEvent(Event event, ChatProvider.Usage usage, boolean chargeableUnknown, String failureCode) {
        boolean validUsage = usage != null && usage.inputTokens() >= 0 && usage.outputTokens() >= 0
                && usage.cachedInputTokens() >= 0 && usage.cachedInputTokens() <= usage.inputTokens();
        if (usage != null && !validUsage) chargeableUnknown = true;
        long charge = chargeableUnknown ? event.reserved() : 0;
        if (validUsage) {
            try {
                charge = Math.addExact(Math.multiplyExact(usage.inputTokens(), event.inputRate()),
                        Math.multiplyExact(usage.outputTokens(), event.outputRate()));
            } catch (ArithmeticException ex) {
                // Malformed usage must never release an existing reservation.
                validUsage = false;
                chargeableUnknown = true;
                charge = event.reserved();
            }
        }
        String status = validUsage ? "SETTLED" : chargeableUnknown ? "UNKNOWN" : "REJECTED";
        jdbc.update("UPDATE chat_usage_events SET charged_nano_usd=?,input_tokens=?,cached_input_tokens=?,"
                + "output_tokens=?,status=?,failure_code=?,finished_at=? WHERE scope=? AND id=?",
                charge, validUsage ? usage.inputTokens() : null, validUsage ? usage.cachedInputTokens() : null,
                validUsage ? usage.outputTokens() : null, status, failureCode, clock.instant(), scope, event.id());
        adjust("MONTH", event.month(), charge, -event.reserved());
        adjust("DAY", event.day(), charge, -event.reserved());
        if ("AI_CREDIT_EXHAUSTED".equals(failureCode) || "AI_UPSTREAM_QUOTA_EXCEEDED".equals(failureCode)) {
            block(failureCode);
        } else if (charge > event.reserved()) {
            block("AI_USAGE_EXCEEDED_RESERVATION");
        }
    }

    @Transactional(readOnly = true)
    public UsageView usage(UUID userId) {
        requireOwner(userId, false);
        LocalDate day = today();
        String reason = blockedReason();
        String availability = reason == null ? "AVAILABLE" : reason;
        if (availability.equals("AVAILABLE") && (total("DAY", day) >= dailyLimit
                || total("MONTH", day.withDayOfMonth(1)) >= monthlyLimit)) availability = "AI_BUDGET_EXCEEDED";
        PeriodUsage daily = usagePeriod(userId, "day_start", day);
        if (availability.equals("AVAILABLE") && daily.requests() >= userDailyLimit) availability = "AI_DAILY_USAGE_EXCEEDED";
        return new UsageView("UTC", daily, usagePeriod(userId, "month_start", day.withDayOfMonth(1)),
                userDailyLimit, availability);
    }

    private PeriodUsage usagePeriod(UUID userId, String column, LocalDate start) {
        return jdbc.query("SELECT COUNT(*) AS requests,COALESCE(SUM(input_tokens),0) AS inputs,"
                + "COALESCE(SUM(cached_input_tokens),0) AS cached,COALESCE(SUM(output_tokens),0) AS outputs,"
                + "COALESCE(SUM(charged_nano_usd),0) AS charged,"
                + "COALESCE(SUM(CASE WHEN status='RESERVED' THEN reserved_nano_usd ELSE 0 END),0) AS reserved "
                + "FROM chat_usage_events WHERE scope=? AND user_id=? AND " + column + "=?",
                (rs, row) -> new PeriodUsage(start, rs.getLong("requests"), rs.getLong("inputs"),
                    rs.getLong("cached"), rs.getLong("outputs"), usd(rs.getLong("charged")),
                    usd(rs.getLong("reserved"))), scope, userId, start).getFirst();
    }

    private void lockBillingState() {
        jdbc.update("INSERT INTO chat_billing_state(scope,updated_at) VALUES (?,?) "
                + "ON DUPLICATE KEY UPDATE scope=VALUES(scope)", scope, clock.instant());
        jdbc.queryForList("SELECT scope FROM chat_billing_state WHERE scope=? FOR UPDATE", String.class, scope);
    }

    private String blockedReason() {
        List<String> reasons = jdbc.queryForList("SELECT reason FROM chat_billing_state "
                + "WHERE scope=? AND blocked_epoch=?", String.class, scope, billingEpoch);
        return reasons.isEmpty() ? null : reasons.getFirst();
    }

    private void block(String reason) {
        jdbc.update("UPDATE chat_billing_state SET blocked_epoch=?,reason=?,updated_at=? WHERE scope=?",
                billingEpoch, reason, clock.instant(), scope);
    }

    private void ensurePeriod(String kind, LocalDate start) {
        jdbc.update("INSERT INTO chat_spend_periods(scope,period_kind,period_start,charged_nano_usd,reserved_nano_usd) "
                + "VALUES (?,?,?,0,0) ON DUPLICATE KEY UPDATE scope=VALUES(scope)", scope, kind, start);
    }

    private long total(String kind, LocalDate start) {
        List<Long> rows = jdbc.queryForList("SELECT charged_nano_usd+reserved_nano_usd FROM chat_spend_periods "
                + "WHERE scope=? AND period_kind=? AND period_start=?", Long.class, scope, kind, start);
        return rows.isEmpty() ? 0 : rows.getFirst();
    }

    private void adjust(String kind, LocalDate start, long charged, long reserved) {
        jdbc.update("UPDATE chat_spend_periods SET charged_nano_usd=charged_nano_usd+?,"
                + "reserved_nano_usd=reserved_nano_usd+? WHERE scope=? AND period_kind=? AND period_start=?",
                charged, reserved, scope, kind, start);
    }

    private void requireOwner(UUID userId, boolean lock) {
        if (jdbc.queryForList("SELECT id FROM users WHERE id=?" + (lock ? " FOR UPDATE" : ""),
                UUID.class, userId).isEmpty()) throw new ApiException(401, "AUTH_REQUIRED", "Unknown user");
    }

    private LocalDate today() { return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC); }
    private static BigDecimal usd(long nanos) { return BigDecimal.valueOf(nanos).divide(NANOS_PER_USD, 9, RoundingMode.UNNECESSARY); }
    private static long money(BigDecimal usd) {
        if (usd == null || usd.signum() < 0 || usd.compareTo(new BigDecimal("1000000")) > 0) {
            throw new IllegalArgumentException("Invalid chat budget amount");
        }
        return usd.multiply(NANOS_PER_USD).longValueExact();
    }
    private static ApiException failure(String code) { return new ApiException(429, code, "AI usage is currently limited"); }
}
