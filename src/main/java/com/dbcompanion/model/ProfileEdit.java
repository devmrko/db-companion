package com.dbcompanion.model;

public final class ProfileEdit {
    private ProfileEdit() {}
    public record Target(String schema, String profile, String attribute) {}
    public record Form(Target target, String value, String version, int maxBytes,
                       java.util.List<String> choices, java.util.List<String> schemas) {}
    public record ObjectChoice(String name, String type) {}
    public record ObjectChoices(java.util.List<ObjectChoice> objects, boolean more) {}
    public record SaveRequest(String schema, String profile, String attribute, String value, String version) {
        public Target target() { return new Target(schema, profile, attribute); }
        @com.fasterxml.jackson.annotation.JsonAnySetter
        public void rejectUnknown(String name, Object ignored) {
            throw new IllegalArgumentException("Unexpected profile edit field: " + name);
        }
    }
    public record SaveResult(boolean verified, String message) {}
}
