package kr.ganzithon.backend.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** One-process, bounded fixed-window limiter. Neither keys nor logs contain a plain IP address. */
@Component
public class RequestRateLimiter {
    private static final long MINUTE_MS = 60_000L;
    private static final long HOUR_MS = 3_600_000L;
    private final Clock clock;
    private final int globalPerMinute;
    private final int guestPerHour;
    private final int authPerMinute;
    private final int maxEntries;
    private final byte[] processSalt = new byte[32];
    private final Map<Key, Bucket> buckets = new HashMap<>();
    private long nextSweepMs;

    public RequestRateLimiter(Clock clock,
                              @Value("${ganzi.rate-limit.global-per-minute}") int globalPerMinute,
                              @Value("${ganzi.rate-limit.guest-per-hour}") int guestPerHour,
                              @Value("${ganzi.rate-limit.auth-per-minute}") int authPerMinute,
                              @Value("${ganzi.rate-limit.max-entries}") int maxEntries) {
        if (globalPerMinute <= 0 || guestPerHour <= 0 || authPerMinute <= 0 || maxEntries <= 0) {
            throw new IllegalArgumentException("Rate limits and max-entries must be positive");
        }
        this.clock = clock;
        this.globalPerMinute = globalPerMinute;
        this.guestPerHour = guestPerHour;
        this.authPerMinute = authPerMinute;
        this.maxEntries = maxEntries;
        new SecureRandom().nextBytes(processSalt);
    }

    public synchronized Decision allow(String remoteAddress, Category category) {
        long now = clock.millis();
        if (now >= nextSweepMs) {
            pruneExpired(now);
            nextSweepMs = now + MINUTE_MS;
        }
        String addressHash = hash(remoteAddress == null ? "" : remoteAddress);
        Key global = new Key(addressHash, Scope.GLOBAL);
        Key narrower = category == Category.GUEST ? new Key(addressHash, Scope.GUEST)
                : category == Category.AUTH ? new Key(addressHash, Scope.AUTH) : null;
        Bucket globalBucket = current(global, now);
        Bucket narrowerBucket = narrower == null ? null : current(narrower, now);

        long blockedUntil = 0;
        if (globalBucket != null && globalBucket.count >= globalPerMinute) {
            blockedUntil = globalBucket.expiresAt;
        }
        if (narrowerBucket != null && narrowerBucket.count >= limitFor(narrower.scope)) {
            blockedUntil = Math.max(blockedUntil, narrowerBucket.expiresAt);
        }
        if (blockedUntil > now) return Decision.deny(secondsUntil(blockedUntil, now));

        int newEntries = missingEntries(global, narrower);
        if (buckets.size() + newEntries > maxEntries) {
            pruneExpired(now);
            newEntries = missingEntries(global, narrower);
            if (buckets.size() + newEntries > maxEntries) {
                long earliestExpiry = buckets.values().stream().mapToLong(b -> b.expiresAt).min()
                        .orElse(now + MINUTE_MS);
                return Decision.deny(secondsUntil(earliestExpiry, now));
            }
        }

        buckets.put(global, increase(globalBucket, Scope.GLOBAL, now));
        if (narrower != null) buckets.put(narrower, increase(narrowerBucket, narrower.scope, now));
        return Decision.ALLOWED;
    }

    synchronized int trackedEntries() { return buckets.size(); }

    private int missingEntries(Key global, Key narrower) {
        return (buckets.containsKey(global) ? 0 : 1)
                + (narrower != null && !buckets.containsKey(narrower) ? 1 : 0);
    }

    private Bucket current(Key key, long now) {
        Bucket found = buckets.get(key);
        return found != null && found.expiresAt > now ? found : null;
    }

    private Bucket increase(Bucket existing, Scope scope, long now) {
        if (existing != null) return new Bucket(existing.count + 1, existing.expiresAt);
        long window = scope == Scope.GUEST ? HOUR_MS : MINUTE_MS;
        long expiresAt = Math.floorDiv(now, window) * window + window;
        return new Bucket(1, expiresAt);
    }

    private int limitFor(Scope scope) {
        return scope == Scope.GUEST ? guestPerHour : authPerMinute;
    }

    private void pruneExpired(long now) {
        for (Iterator<Bucket> iterator = buckets.values().iterator(); iterator.hasNext();) {
            if (iterator.next().expiresAt <= now) iterator.remove();
        }
    }

    private String hash(String remoteAddress) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(processSalt);
            return HexFormat.of().formatHex(digest.digest(remoteAddress.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static long secondsUntil(long expiresAt, long now) {
        return Math.max(1, Math.floorDiv(expiresAt - now + 999, 1000));
    }

    public enum Category { GENERAL, GUEST, AUTH }
    private enum Scope { GLOBAL, GUEST, AUTH }
    private record Key(String addressHash, Scope scope) {}
    private record Bucket(int count, long expiresAt) {}
    public record Decision(boolean allowed, long retryAfterSeconds) {
        static final Decision ALLOWED = new Decision(true, 0);
        static Decision deny(long seconds) { return new Decision(false, seconds); }
    }
}
