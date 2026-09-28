package com.dbcompanion.model;

import java.time.Instant;
import java.util.List;

/** Read-only, point-in-time profile checks. UNKNOWN never means an object is absent. */
public final class ProfilePreflight {
    private ProfilePreflight() {}
    public enum Tone { GREEN, YELLOW, RED, GREY }
    public record Check(String key, Tone tone, String summary, String reason, String method) {}
    public record Result(String schema, String profile, String profileModified, String fingerprint, boolean stale,
                         Instant checkedAt, List<Check> checks) {
        public Result { checks = List.copyOf(checks); }
    }
}
