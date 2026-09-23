package com.dbcompanion.repository;

import com.dbcompanion.common.i18n.UiMessages;

import com.dbcompanion.model.MetadataEdit.Target;
import com.dbcompanion.common.db.HistoryPermissions;
import com.dbcompanion.common.db.HistorySql;
import com.dbcompanion.model.MetadataHistory.Configuration;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class HistoryReadinessRepository {
    private final JdbcTemplate jdbc;
    private final MetadataHistoryRepository history;
    public HistoryReadinessRepository(JdbcTemplate jdbc, MetadataHistoryRepository history) { this.jdbc = jdbc; this.history = history; }
    public record Preparation(Configuration configuration, String triggerOwner, List<HistorySql.Asset> missing,
                              HistoryPermissions.Access access, HistoryPermissions.Decision decision, boolean auditCompileRequired,
                              List<HistorySql.Asset> legacyTriggers, boolean auditUpgradeRequired) {}
    public Preparation prepare(Target target, boolean enabling) {
        return prepare(target, enabling, true);
    }
    public Preparation prepare(Target target, boolean enabling, boolean validate) {
        var config = history.configuration(target, validate);
        String user = jdbc.queryForObject("SELECT SYS_CONTEXT('USERENV','SESSION_USER') FROM SYS.DUAL", String.class);
        String owner = config.triggerOwner() == null ? user : config.triggerOwner();
        var missing = history.missingAssets(target, owner, validate, true, true);
        var legacy = history.legacyTriggers(target, owner);
        boolean auditUpgrade = history.auditBodyLegacy(target);
        boolean compile = enabling && (config.needsUpgrade() || history.auditBodyInvalid(target));
        var access = access(target, user, owner, enabling);
        var creationChecks = new ArrayList<>(missing);
        if (enabling) creationChecks.addAll(legacy);
        if (enabling && auditUpgrade) creationChecks.add(HistorySql.auditBody(target, false));
        var decision = HistoryPermissions.evaluate(target, owner, access, creationChecks, config.needsUpgrade(), enabling, compile);
        if (enabling && !canReadTargetColumns(target, owner, access)) {
            var denied = new ArrayList<>(decision.notices());
            denied.add(com.dbcompanion.common.i18n.UiNotice.concat(com.dbcompanion.common.i18n.UiNotice.raw(owner),com.dbcompanion.common.i18n.UiNotice.message("ui.3e376f0711d6", " 직접 권한: 대상 테이블 SELECT/READ 또는 SELECT ANY TABLE/READ ANY TABLE")));
            decision = new HistoryPermissions.Decision(false, List.copyOf(denied));
        }
        return new Preparation(config, owner, missing, access, decision, compile, legacy, auditUpgrade);
    }
    private boolean canReadTargetColumns(Target target, String owner, HistoryPermissions.Access access) {
        if (HistoryPermissions.canReadTargetColumns(target.schema(), owner, access.ownerDirect(), Set.of())) return true;
        if (access.ownerDirect() == null) return false;
        var grants = new HashSet<>(owner.equals(access.user())
                ? jdbc.queryForList("SELECT PRIVILEGE FROM SYS.ALL_TAB_PRIVS WHERE TABLE_SCHEMA=? AND TABLE_NAME=? AND GRANTEE IN (?, 'PUBLIC') AND PRIVILEGE IN ('SELECT','READ')",
                    String.class, target.schema(), target.table(), owner)
                : jdbc.queryForList("SELECT PRIVILEGE FROM SYS.DBA_TAB_PRIVS WHERE OWNER=? AND TABLE_NAME=? AND GRANTEE IN (?, 'PUBLIC') AND PRIVILEGE IN ('SELECT','READ')",
                    String.class, target.schema(), target.table(), owner));
        return HistoryPermissions.canReadTargetColumns(target.schema(), owner, access.ownerDirect(), grants);
    }
    private HistoryPermissions.Access access(Target target, String user, String owner, boolean enabling) {
        var session = new HashSet<>(jdbc.queryForList("SELECT PRIVILEGE FROM SESSION_PRIVS", String.class));
        Set<String> direct = null;
        String checkError = null;
        boolean canExecute = owner.equals(target.schema());
        if (enabling) {
            try {
                direct = new HashSet<>(owner.equals(user)
                        ? jdbc.queryForList("SELECT PRIVILEGE FROM USER_SYS_PRIVS", String.class)
                        : jdbc.queryForList("SELECT PRIVILEGE FROM SYS.DBA_SYS_PRIVS WHERE GRANTEE=?", String.class, owner));
                if (!canExecute) canExecute = direct.contains("EXECUTE ANY PROCEDURE") || !(owner.equals(user)
                        ? jdbc.queryForList("SELECT PRIVILEGE FROM SYS.ALL_TAB_PRIVS WHERE TABLE_SCHEMA=? AND TABLE_NAME='DBC_METADATA_AUDIT' AND GRANTEE IN (?, 'PUBLIC') AND PRIVILEGE='EXECUTE'", String.class, target.schema(), owner)
                        : jdbc.queryForList("SELECT PRIVILEGE FROM SYS.DBA_TAB_PRIVS WHERE OWNER=? AND TABLE_NAME='DBC_METADATA_AUDIT' AND GRANTEE IN (?, 'PUBLIC') AND PRIVILEGE='EXECUTE'", String.class, target.schema(), owner)).isEmpty();
            } catch (org.springframework.dao.DataAccessException ex) {
                direct = null; checkError = databaseError(ex);
            }
        }
        var tracking = new HashSet<>(jdbc.queryForList("SELECT DISTINCT PRIVILEGE FROM SYS.ALL_TAB_PRIVS WHERE TABLE_SCHEMA=? AND TABLE_NAME='DBC_METADATA_TRACKING' "
                + "AND (GRANTEE IN (?, 'PUBLIC') OR GRANTEE IN (SELECT ROLE FROM SESSION_ROLES))", String.class, target.schema(), user));
        return new HistoryPermissions.Access(user, Set.copyOf(session), direct == null ? null : Set.copyOf(direct), Set.copyOf(tracking), canExecute, checkError);
    }
    public Map<String, Object> readiness(Target target) {
        var config = history.configuration(target, false);
        var ready = prepare(target, !config.enabled(), false);
        var result = new LinkedHashMap<String, Object>();
        result.put("schema", target.schema()); result.put("table", target.table());
        result.put("loginUser", ready.access().user()); result.put("triggerOwner", ready.triggerOwner());
        result.put("ownerEstablished", config.triggerOwner() != null); result.put("trackingUpgradeRequired", config.needsUpgrade());
        result.put("operation", config.enabled() ? "OFF" : "ON");
        result.put("trackingEnabled", config.enabled());
        result.put("auditCompileRequired", ready.auditCompileRequired());
        result.put("auditUpgradeRequired", ready.auditUpgradeRequired());
        List<String> blockers = List.of();
        if (ready.auditUpgradeRequired()) {
            try { blockers = history.auditUpgradeBlockers(target); }
            catch (RuntimeException ex) { blockers = List.of(UiMessages.text("ui.6c2235ac1f35", "공유 패키지 교체 조건 조회: ") + databaseError(ex)); }
        }
        result.put("auditUpgradeBlockers", blockers);
        result.put("triggerUpgradeRequired", !ready.legacyTriggers().isEmpty());
        result.put("triggerUpgradeAllowed", !config.enabled() && !config.needsUpgrade() && !ready.auditCompileRequired()
                && ready.missing().isEmpty() && !ready.legacyTriggers().isEmpty() && ready.decision().allowed());
        result.put("codeUpgradeAllowed", !config.enabled() && !config.needsUpgrade() && !ready.auditCompileRequired()
                && ready.missing().isEmpty() && (!ready.legacyTriggers().isEmpty() || ready.auditUpgradeRequired())
                && blockers.isEmpty() && ready.decision().allowed());
        result.put("targetObjects", jdbc.queryForList("SELECT OBJECT_TYPE, STATUS FROM SYS.ALL_OBJECTS WHERE OWNER=? "
                + "AND OBJECT_NAME=? AND OBJECT_TYPE IN ('TABLE','VIEW','MATERIALIZED VIEW') ORDER BY OBJECT_TYPE", target.schema(), target.table()));
        result.put("targetCommentSources", jdbc.queryForList("SELECT 'ALL_TAB_COMMENTS' SOURCE_VIEW, TABLE_TYPE OBJECT_TYPE FROM SYS.ALL_TAB_COMMENTS "
                + "WHERE OWNER=? AND TABLE_NAME=? UNION ALL SELECT 'ALL_MVIEW_COMMENTS', 'MATERIALIZED VIEW' FROM SYS.ALL_MVIEW_COMMENTS WHERE OWNER=? AND MVIEW_NAME=?",
                target.schema(), target.table(), target.schema(), target.table()));
        result.put("privilegesAvailable", ready.decision().allowed()); result.put("missingPrivileges", ready.decision().missing());
        result.put("ownerDirectPrivileges", ready.access().ownerDirect() == null ? null : ready.access().ownerDirect().stream()
                .filter(p -> Set.of("ADMINISTER DATABASE TRIGGER", "CREATE TRIGGER", "CREATE ANY TRIGGER", "ALTER ANY TRIGGER", "EXECUTE ANY PROCEDURE").contains(p)).sorted().toList());
        result.put("ownerCanExecuteAudit", ready.access().ownerCanExecuteAudit());
        if (ready.access().ownerCheckError() != null) result.put("directPrivilegeCheckError", ready.access().ownerCheckError());
        result.put("missingAssets", ready.missing().stream().map(a -> a.owner() + "." + a.name() + " " + a.type()).toList());
        result.put("objects", jdbc.queryForList("SELECT OWNER, OBJECT_NAME, OBJECT_TYPE, STATUS FROM SYS.ALL_OBJECTS WHERE OWNER=? "
                + "AND OBJECT_NAME IN ('DBC_METADATA_HISTORY','DBC_METADATA_TRACKING','DBC_METADATA_AUDIT','DBC_META_HIST_LOOKUP') ORDER BY OBJECT_NAME, OBJECT_TYPE", target.schema()));
        result.put("tableTriggers", jdbc.queryForList("SELECT t.OWNER, t.TRIGGER_NAME, t.STATUS, t.TRIGGERING_EVENT, t.BASE_OBJECT_TYPE, o.STATUS COMPILE_STATUS FROM SYS.ALL_TRIGGERS t "
                + "JOIN SYS.ALL_OBJECTS o ON o.OWNER=t.OWNER AND o.OBJECT_NAME=t.TRIGGER_NAME AND o.OBJECT_TYPE='TRIGGER' WHERE t.TRIGGER_NAME IN (?,?) ORDER BY t.OWNER, t.TRIGGER_NAME",
                HistorySql.triggerName(target, true), HistorySql.triggerName(target, false)));
        var validation = new ArrayList<Map<String, Object>>();
        for (var asset : HistorySql.assets(target, ready.triggerOwner())) {
            if (ready.missing().contains(asset)) continue;
            var item = new LinkedHashMap<String, Object>();
            item.put("owner", asset.owner()); item.put("name", asset.name()); item.put("type", asset.type());
            try { history.validateAsset(target, asset); item.put("compatible", true); }
            catch (com.dbcompanion.common.exception.MetadataEditException ex) {
                item.put("compatible", false); item.put("error", ex.userMessage());
            } catch (org.springframework.dao.DataAccessException ex) {
                item.put("compatible", false); item.put("error", databaseError(ex));
            }
            if (asset.type().equals("TRIGGER")) {
                item.put("expectedSource", asset.sql()); item.put("actualSource", history.source(asset));
                item.put("knownLegacy", ready.legacyTriggers().contains(asset));
            }
            if (asset.type().equals("PACKAGE BODY")) item.put("knownLegacy", ready.auditUpgradeRequired());
            validation.add(item);
        }
        result.put("assetValidation", validation);
        result.put("note", UiMessages.text("ui.6eb710ed6e53", "읽기 전용 사전 검사입니다. 실제 작업은 Oracle이 권한을 집행하며 자동 권한 부여는 하지 않습니다."));
        return result;
    }
    private static String databaseError(Throwable ex) {
        while (ex.getCause() != null && !(ex instanceof java.sql.SQLException)) ex = ex.getCause();
        return ex instanceof java.sql.SQLException sql ? sql.getMessage() : ex.getClass().getSimpleName();
    }
}
