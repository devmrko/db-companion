package com.dbcompanion.model;

/** Existing AI request fingerprint, not a version of external dependencies. */
public record SetupProfileVersion(String scheme,String owner,String profile,String value) {
    public static final String SCHEME="AI_PROFILE_SHA256_V1";
    public static SetupProfileVersion captured(AiAssistant.Profile profile){
        return new SetupProfileVersion(SCHEME,profile.selection().owner(),profile.selection().name(),profile.version());
    }
    public static boolean valid(String value){return value!=null&&value.matches("[0-9a-f]{64}");}
    public String compare(String owner,String profile,String current){
        if(!SCHEME.equals(scheme)||!java.util.Objects.equals(this.owner,owner)
                ||!java.util.Objects.equals(this.profile,profile)||!valid(value))return "UNAVAILABLE";
        if(!valid(current))return "CURRENT_UNKNOWN";
        return value.equals(current)?"MATCH":"CHANGED";
    }
}
