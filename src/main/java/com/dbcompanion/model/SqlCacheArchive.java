package com.dbcompanion.model;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Observations of cached cursors, not a lossless execution or AI response log. */
public final class SqlCacheArchive {
    private SqlCacheArchive() {}
    public enum Operation { INSTALL, CONFIGURE, PAUSE, RESUME, RUN }
    public enum Match { all, select_ai, generate, ai }
    public record Query(LocalDate from, LocalDate to, String sqlId, String text, Match match, int page) {
        public Query {
            if (from == null || to == null || to.isBefore(from) || ChronoUnit.DAYS.between(from,to)>30
                    || match == null || page<1 || page>1000) throw new IllegalArgumentException("Invalid archive filters");
            sqlId = sqlId == null ? "" : sqlId.strip(); text = text == null ? "" : text.strip();
            if ((!sqlId.isEmpty() && !sqlId.matches("[a-z0-9]{13}")) || text.length()>500)
                throw new IllegalArgumentException("Invalid archive filters");
        }
    }
    public record Status(String owner, String table, String job, boolean enabled, String state,
                         String interval, String nextRun, String lastRun, String lastStatus,
                         String lastError, String sourceAccess) {}
    public record Preview(String token, Operation operation, int seconds, String owner,
                          String fingerprint, Instant expires, List<String> statements) {
        public Preview { statements = List.copyOf(statements); }
        public static Preview of(Operation op, int seconds, Status status, List<String> sql) {
            return new Preview(UUID.randomUUID().toString(),op,seconds,status.owner(),SqlCacheArchive.fingerprint(status),
                    Instant.now().plusSeconds(300),sql);
        }
    }
    public record Page(List<Map<String,Object>> rows, int page, boolean more) {
        public Page { rows = List.copyOf(rows); }
    }
    public static int interval(int seconds) {
        if (seconds<10 || seconds>3600) throw new IllegalArgumentException("Interval must be 10–3600 seconds");
        return seconds;
    }
    public static String fingerprint(Status s){return s.owner()+"|"+s.table()+"|"+s.job()+"|"+s.enabled()+"|"+s.interval()+"|"+s.sourceAccess();}
    public static String key(String key) {
        if (key == null || !key.matches("[A-F0-9]{64}")) throw new IllegalArgumentException("Invalid archive key");
        return key;
    }
}
