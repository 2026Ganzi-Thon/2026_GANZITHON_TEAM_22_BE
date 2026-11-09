package kr.ganzithon.backend.common;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** JDBC conversions for CHAR(36) UUIDs and UTC DATETIME(6), including legacy H2 reads. */
public final class PortableJdbc {
    private final JdbcTemplate delegate;

    public PortableJdbc(JdbcTemplate delegate) { this.delegate = delegate; }

    public int update(String sql, Object... args) {
        return delegate.update(sql, adapt(args));
    }

    public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
        return delegate.query(sql, mapper, adapt(args));
    }

    public <T> List<T> queryForList(String sql, Class<T> type, Object... args) {
        if (type == UUID.class) {
            @SuppressWarnings("unchecked")
            List<T> uuids = (List<T>) delegate.query(sql, (rs, row) -> uuid(rs, 1), adapt(args));
            return uuids;
        }
        return delegate.queryForList(sql, type, adapt(args));
    }

    public <T> T queryForObject(String sql, Class<T> type, Object... args) {
        return delegate.queryForObject(sql, type, adapt(args));
    }

    private static Object[] adapt(Object[] args) {
        Object[] converted = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            Object arg = args[i];
            if (arg instanceof UUID id) converted[i] = id.toString();
            else if (arg instanceof OffsetDateTime time) converted[i] =
                    LocalDateTime.ofInstant(time.toInstant(), ZoneOffset.UTC);
            else if (arg instanceof Instant time) converted[i] =
                    LocalDateTime.ofInstant(time, ZoneOffset.UTC);
            else if (arg instanceof Timestamp time) converted[i] =
                    LocalDateTime.ofInstant(time.toInstant(), ZoneOffset.UTC);
            else converted[i] = arg;
        }
        return converted;
    }

    public static UUID uuid(ResultSet rs, String column) throws SQLException {
        return asUuid(rs.getObject(column));
    }

    public static UUID uuid(ResultSet rs, int column) throws SQLException {
        return asUuid(rs.getObject(column));
    }

    private static UUID asUuid(Object value) throws SQLException {
        if (value == null) return null;
        if (value instanceof UUID id) return id;
        if (value instanceof String text) {
            try { return UUID.fromString(text.strip()); }
            catch (IllegalArgumentException ex) { throw new SQLException("Invalid UUID column value", ex); }
        }
        throw new SQLException("Unsupported UUID column type: " + value.getClass().getName());
    }

    public static OffsetDateTime utc(ResultSet rs, String column) throws SQLException {
        return asUtc(rs.getObject(column));
    }

    public static OffsetDateTime utc(ResultSet rs, int column) throws SQLException {
        return asUtc(rs.getObject(column));
    }

    public static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = utc(rs, column);
        return value == null ? null : value.toInstant();
    }

    public static Instant instant(ResultSet rs, int column) throws SQLException {
        OffsetDateTime value = utc(rs, column);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime asUtc(Object value) throws SQLException {
        if (value == null) return null;
        if (value instanceof OffsetDateTime time) return time.withOffsetSameInstant(ZoneOffset.UTC);
        if (value instanceof LocalDateTime time) return time.atOffset(ZoneOffset.UTC);
        if (value instanceof Timestamp time) return time.toLocalDateTime().atOffset(ZoneOffset.UTC);
        throw new SQLException("Unsupported UTC datetime column type: " + value.getClass().getName());
    }
}
