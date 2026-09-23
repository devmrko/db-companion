package com.dbcompanion.common.db;

/** Fixed diagnostic statements only; neither SQL nor names are accepted from HTTP input. */
public final class ProfileAuditProbeSql {
    private ProfileAuditProbeSql() {}
    public record Run(String id) {
        public Run {
            if (id == null || !id.matches("[0-9A-F]{16}")) throw new IllegalArgumentException("Invalid diagnostic run ID");
        }
        public String policy() { return "DBC_PA_" + id; }
        public String profile() { return "DBC_AP_" + id; }
        public String client() { return "DBC_PROFILE_AUDIT_" + id; }
        public String description() { return "DB Companion temporary audit probe " + id; }
    }
    public static String literal(String value) {
        if (value == null || value.indexOf('\0') >= 0) throw new IllegalArgumentException("Invalid diagnostic literal");
        return "'" + value.replace("'", "''") + "'";
    }
    public static String api(String owner) { return MetadataSql.identifier(owner) + ".\"DBMS_CLOUD_AI\""; }
    public static String condition(Run run, String sessionId, String instanceId) {
        if (sessionId == null || instanceId == null || !sessionId.matches("[0-9]+") || !instanceId.matches("[0-9]+"))
            throw new IllegalArgumentException("Invalid database session identity");
        return "SYS_CONTEXT('USERENV','CLIENT_IDENTIFIER') = " + literal(run.client())
                + " AND SYS_CONTEXT('USERENV','SESSIONID') = " + literal(sessionId)
                + " AND SYS_CONTEXT('USERENV','INSTANCE') = " + literal(instanceId);
    }
    public static String createPolicy(Run run, String owner, String sessionId, String instanceId) {
        return "CREATE AUDIT POLICY " + MetadataSql.identifier(run.policy()) + " ACTIONS EXECUTE ON " + api(owner)
                + " WHEN " + literal(condition(run, sessionId, instanceId)) + " EVALUATE PER STATEMENT";
    }
    public static String audit(Run run, String user, boolean enabled) {
        return (enabled ? "AUDIT" : "NOAUDIT") + " POLICY " + MetadataSql.identifier(run.policy()) + " BY " + MetadataSql.identifier(user);
    }
    public static String dropPolicy(Run run) { return "DROP AUDIT POLICY " + MetadataSql.identifier(run.policy()); }
    public static String createProfile(Run run, String owner) {
        return "BEGIN " + api(owner) + ".CREATE_PROFILE(profile_name => " + literal(run.profile())
                + ", attributes => '{\"provider\":\"oci\",\"comments\":false}', status => 'disabled', description => "
                + literal(run.description()) + "); END;";
    }
    public static String setComments(Run run, String owner, boolean value) {
        return "BEGIN " + api(owner) + ".SET_ATTRIBUTE(profile_name => " + literal(run.profile())
                + ", attribute_name => 'comments', attribute_value => " + (value ? "TRUE" : "FALSE") + "); END;";
    }
    public static String boundComments(String owner) {
        return "DECLARE v_profile VARCHAR2(128) := ?; v_value VARCHAR2(32) := ?; BEGIN " + api(owner)
                + ".SET_ATTRIBUTE(profile_name => v_profile, attribute_name => 'comments', attribute_value => v_value); END;";
    }
    public static String consecutiveComments(Run run, String owner) {
        String call = api(owner) + ".SET_ATTRIBUTE(profile_name => " + literal(run.profile()) + ", attribute_name => 'comments', attribute_value => ";
        return "BEGIN " + call + "TRUE); " + call + "FALSE); END;";
    }
    public static String dropProfile(Run run, String owner) {
        return "BEGIN " + api(owner) + ".DROP_PROFILE(profile_name => " + literal(run.profile()) + "); END;";
    }
}
