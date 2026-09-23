package com.dbcompanion.common.db;

import com.dbcompanion.common.i18n.UiNotice;

import com.dbcompanion.model.MetadataEdit.Target;
import java.util.*;

/** Capability checks, not account-name or application-role checks. Oracle remains the final authority. */
public final class HistoryPermissions {
    private HistoryPermissions() {}
    public record Access(String user, Set<String> session, Set<String> ownerDirect,
                         Set<String> tracking, boolean ownerCanExecuteAudit, String ownerCheckError) {}
    public record Decision(boolean allowed, @com.fasterxml.jackson.annotation.JsonIgnore List<UiNotice> notices) {
        @com.fasterxml.jackson.annotation.JsonProperty("missing")
        public List<String> missing() { return notices.stream().map(UiNotice::render).toList(); }
        public UiNotice notice() { return allowed?UiNotice.raw(""):UiNotice.concat(UiNotice.message("ui.ef05200886bd","이력 관리 권한 확인: "),UiNotice.join(UiNotice.raw(", "),notices)); }
        public String message() { return notice().render(); }
    }
    public static boolean canReadTargetColumns(String schema, String owner, Set<String> direct, Set<String> targetGrants) {
        return schema.equals(owner) || direct != null && (direct.contains("SELECT ANY TABLE") || direct.contains("READ ANY TABLE")
                || targetGrants.contains("SELECT") || targetGrants.contains("READ"));
    }
    public static Decision evaluate(Target target, String owner, Access access, List<HistorySql.Asset> missing,
                                    boolean upgrade, boolean enabling) {
        return evaluate(target, owner, access, missing, upgrade, enabling, false);
    }
    public static Decision evaluate(Target target, String owner, Access access, List<HistorySql.Asset> missing,
                                    boolean upgrade, boolean enabling, boolean compileAuditBody) {
        var denied = new LinkedHashSet<UiNotice>();
        requireAny(denied, access.session(), "ADMINISTER DATABASE TRIGGER");
        if (!owner.equals(access.user())) requireAny(denied, access.session(), "ALTER ANY TRIGGER");
        if (!target.schema().equals(access.user())) {
            requireObject(denied, access, "UPDATE", "UPDATE ANY TABLE");
            if (enabling) requireObject(denied, access, "INSERT", "INSERT ANY TABLE");
        }
        if (enabling) {
            if (access.ownerDirect() == null) denied.add(UiNotice.concat(UiNotice.message("ui.753a9dad4b04", "트리거 소유자 직접 권한 조회 불가: "),UiNotice.raw(access.ownerCheckError())));
            else if (!access.ownerDirect().contains("ADMINISTER DATABASE TRIGGER")) denied.add(UiNotice.concat(UiNotice.raw(owner),UiNotice.message("ui.94bdb37f07e1", " 직접 권한: ADMINISTER DATABASE TRIGGER")));
            if (!access.ownerCanExecuteAudit()) denied.add(UiNotice.concat(UiNotice.raw(owner),UiNotice.message("ui.588f88290002", " 직접 권한: "),UiNotice.raw(target.schema()+".DBC_METADATA_AUDIT EXECUTE")));
            for (var asset : missing) {
                String kind = switch (asset.type()) {
                    case "PACKAGE", "PACKAGE BODY" -> "PROCEDURE";
                    default -> asset.type();
                };
                if (kind.equals("INDEX") && asset.owner().equals(access.user())) continue;
                if (asset.owner().equals(access.user())) requireAny(denied, access.session(), "CREATE " + kind, "CREATE ANY " + kind);
                else requireAny(denied, access.session(), "CREATE ANY " + kind);
            }
            if (upgrade && !target.schema().equals(access.user())) requireObject(denied, access, "ALTER", "ALTER ANY TABLE");
            if (compileAuditBody && !target.schema().equals(access.user())) requireAny(denied, access.session(), "ALTER ANY PROCEDURE");
        }
        return new Decision(denied.isEmpty(), List.copyOf(denied));
    }
    private static void requireObject(Set<UiNotice> denied, Access access, String objectPrivilege, String systemPrivilege) {
        if (!access.tracking().contains(objectPrivilege) && !access.session().contains(systemPrivilege))
            denied.add(UiNotice.concat(UiNotice.raw("DBC_METADATA_TRACKING "+objectPrivilege),UiNotice.message("ui.adaf1a7cf2d4", " 또는 "),UiNotice.raw(systemPrivilege)));
    }
    private static void requireAny(Set<UiNotice> denied, Set<String> available, String... alternatives) {
        if (Arrays.stream(alternatives).noneMatch(available::contains)) denied.add(UiNotice.join(UiNotice.message("ui.adaf1a7cf2d4", " 또는 "),Arrays.stream(alternatives).map(UiNotice::raw).toList()));
    }
}
