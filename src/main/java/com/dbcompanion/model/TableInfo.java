package com.dbcompanion.model;

public record TableInfo(String name, String description) {
    public String shortName() { return shorten(name); }

    private static String shorten(String value) {
        if (value == null || value.isBlank()) return "—";
        if (value.codePointCount(0, value.length()) <= 30) return value;
        return value.substring(0, value.offsetByCodePoints(0, 30)) + "…";
    }
}
