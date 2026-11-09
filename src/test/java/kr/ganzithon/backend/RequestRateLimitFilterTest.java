package kr.ganzithon.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import kr.ganzithon.backend.common.RequestRateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ContextConfiguration(initializers = TestDatabaseGuard.class)
@Import(RequestRateLimitFilterTest.ClockConfig.class)
@TestPropertySource(properties = {
        "ganzi.rate-limit.enabled=true",
        "ganzi.rate-limit.global-per-minute=3",
        "ganzi.rate-limit.guest-per-hour=2",
        "ganzi.rate-limit.auth-per-minute=1",
        "ganzi.rate-limit.max-entries=32"
})
class RequestRateLimitFilterTest {
    private static final Instant START = Instant.parse("2026-11-03T00:00:00Z");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;

    @BeforeEach void resetClock() { clock.set(START); }

    @Test void guestLimitBlocksBeforeCreatingMoreUsersAndRecoversAfterWindow() throws Exception {
        String ip = "203.0.113.11";
        int before = userCount();
        mvc.perform(options("/api/v1/guest-sessions")
                .header("Origin", "http://127.0.0.1:7891")
                .header("Access-Control-Request-Method", "POST")
                .with(request -> { request.setRemoteAddr(ip); return request; }))
                .andExpect(status().isOk());
        guest(ip, 201);
        guest(ip, 201);
        assertEquals(before + 2, userCount());

        MvcResult denied = guest(ip, 429);
        assertEquals(before + 2, userCount());
        JsonNode body = mapper.readTree(denied.getResponse().getContentAsString());
        assertEquals("RATE_LIMITED", body.path("code").asText());
        assertTrue(body.path("retryable").asBoolean());
        assertEquals(denied.getResponse().getHeader("X-Request-ID"), body.path("requestId").asText());
        assertEquals("no-store", denied.getResponse().getHeader("Cache-Control"));
        assertTrue(Long.parseLong(denied.getResponse().getHeader("Retry-After")) > 0);
        assertTrue(denied.getResponse().getHeader("Access-Control-Expose-Headers").contains("Retry-After"));

        clock.advanceSeconds(3600);
        guest(ip, 201);
        assertEquals(before + 3, userCount());
    }

    @Test void forwardedHeadersCannotMoveAClientToANewBucket() throws Exception {
        String token = mapper.readTree(guest("203.0.113.12", 201).getResponse().getContentAsString())
                .path("accessToken").asText();
        String actualIp = "198.51.100.42";
        for (int i = 0; i < 3; i++) {
            mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token)
                    .header("X-Forwarded-For", "192.0.2." + (i + 1))
                    .with(request -> { request.setRemoteAddr(actualIp); return request; }))
                    .andExpect(status().isOk());
        }
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token)
                .header("X-Forwarded-For", "192.0.2.99")
                .with(request -> { request.setRemoteAddr(actualIp); return request; }))
                .andExpect(status().isTooManyRequests());
    }

    @Test void authLimitAppliesToLoginAndResetsAfterMinute() throws Exception {
        String ip = "203.0.113.13";
        MvcResult first = login(ip);
        assertNotEquals(429, first.getResponse().getStatus());
        MvcResult denied = login(ip);
        assertEquals(429, denied.getResponse().getStatus());
        assertTrue(Long.parseLong(denied.getResponse().getHeader("Retry-After")) > 0);
        clock.advanceSeconds(60);
        assertNotEquals(429, login(ip).getResponse().getStatus());
    }

    @Test void capacityDeniesNewKeysWithoutErasingExistingCounters() {
        MutableClock localClock = new MutableClock();
        localClock.set(START);
        RequestRateLimiter limiter = new RequestRateLimiter(localClock, 2, 2, 2, 2);
        assertTrue(limiter.allow("198.51.100.1", RequestRateLimiter.Category.GENERAL).allowed());
        assertTrue(limiter.allow("198.51.100.2", RequestRateLimiter.Category.GENERAL).allowed());
        assertFalse(limiter.allow("198.51.100.3", RequestRateLimiter.Category.GENERAL).allowed());
        assertTrue(limiter.allow("198.51.100.1", RequestRateLimiter.Category.GENERAL).allowed());
        assertFalse(limiter.allow("198.51.100.1", RequestRateLimiter.Category.GENERAL).allowed());
        localClock.advanceSeconds(60);
        assertTrue(limiter.allow("198.51.100.3", RequestRateLimiter.Category.GENERAL).allowed());
    }

    @Test void rejectsNonPositiveConfiguration() {
        assertThrows(IllegalArgumentException.class,
                () -> new RequestRateLimiter(clock, 0, 2, 2, 32));
        assertThrows(IllegalArgumentException.class,
                () -> new RequestRateLimiter(clock, 2, 2, 2, 0));
    }

    private MvcResult guest(String ip, int expected) throws Exception {
        return mvc.perform(post("/api/v1/guest-sessions")
                .header("Origin", "http://127.0.0.1:7891")
                .with(request -> { request.setRemoteAddr(ip); return request; }))
                .andExpect(status().is(expected)).andReturn();
    }

    private MvcResult login(String ip) throws Exception {
        return mvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"missing@example.com\",\"password\":\"invalid-pass\"}")
                .with(request -> { request.setRemoteAddr(ip); return request; }))
                .andReturn();
    }

    private int userCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class);
    }

    @TestConfiguration static class ClockConfig {
        @Bean @Primary MutableClock rateLimitTestClock() { return new MutableClock(); }
    }

    static class MutableClock extends Clock {
        private Instant instant = START;
        synchronized void set(Instant value) { instant = value; }
        synchronized void advanceSeconds(long seconds) { instant = instant.plusSeconds(seconds); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public synchronized Instant instant() { return instant; }
    }
}
