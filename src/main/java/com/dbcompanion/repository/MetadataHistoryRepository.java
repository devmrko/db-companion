package com.dbcompanion.repository;

import com.dbcompanion.common.i18n.UiMessages;

import com.dbcompanion.common.db.HistorySql;
import com.dbcompanion.common.exception.MetadataEditException;
import com.dbcompanion.model.MetadataEdit.Target;
import com.dbcompanion.model.MetadataHistory.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MetadataHistoryRepository {
    private final JdbcTemplate jdbc;
    public MetadataHistoryRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    private record DbObject(String owner, String name, String type, String status) {}
    private List<DbObject> objects(Target target) {
        return jdbc.query("SELECT OWNER, OBJECT_NAME, OBJECT_TYPE, STATUS FROM SYS.ALL_OBJECTS WHERE OWNER = ? "
                + "AND OBJECT_NAME IN ('DBC_METADATA_TRACKING','DBC_METADATA_HISTORY','DBC_META_HIST_LOOKUP','DBC_METADATA_AUDIT')",
                (r, n) -> new DbObject(r.getString(1), r.getString(2), r.getString(3), r.getString(4)), target.schema());
    }
    private boolean exists(List<DbObject> objects, String name, String type) {
        return objects.stream().anyMatch(o -> o.name.equals(name) && o.type.equals(type));
    }
    public State state(Target target) {
        var objects = objects(target);
        var config = configuration(target, false);
        String before = HistorySql.triggerName(target, true), after = HistorySql.triggerName(target, false);
        if (objects.isEmpty()) return new State(false, false, true, com.dbcompanion.common.i18n.UiNotice.message("ui.c9b6b3d43ae9", "트리거 미설치"), before, after, null, false, "");
        if (!exists(objects, "DBC_METADATA_TRACKING", "TABLE"))
            return new State(false, false, false, com.dbcompanion.common.i18n.UiNotice.message("ui.0a6bc38ce800", "설정 테이블이 없습니다. 이력 객체 구성을 확인해 주세요."), before, after, config.triggerOwner(), false, "");
        boolean enabled = config.enabled();
        boolean baseReady = exists(objects, "DBC_METADATA_HISTORY", "TABLE")
                && exists(objects, "DBC_METADATA_AUDIT", "PACKAGE") && exists(objects, "DBC_METADATA_AUDIT", "PACKAGE BODY");
        boolean valid = objects.stream().allMatch(o -> "VALID".equals(o.status));
        var triggers = jdbc.queryForList("SELECT t.TRIGGER_NAME FROM SYS.ALL_TRIGGERS t JOIN SYS.ALL_OBJECTS o "
                + "ON o.OWNER=t.OWNER AND o.OBJECT_NAME=t.TRIGGER_NAME AND o.OBJECT_TYPE='TRIGGER' "
                + "WHERE t.OWNER = ? AND t.TRIGGER_NAME IN (?,?) AND t.STATUS = 'ENABLED' AND o.STATUS='VALID'",
                String.class, config.triggerOwner(), before, after);
        boolean healthy = baseReady && valid && (!enabled || triggers.size() == 2);
        int installedTriggers = jdbc.queryForObject("SELECT COUNT(*) FROM SYS.ALL_TRIGGERS WHERE OWNER=? AND TRIGGER_NAME IN (?,?)",Integer.class,config.triggerOwner(),before,after);
        if (enabled && healthy) healthy = currentTriggers(target, config.triggerOwner())
                && HistorySql.sourceMatches(HistorySql.auditBody(target, false), source(HistorySql.auditBody(target, false)));
        return new State(baseReady && installedTriggers == 2, enabled, healthy, enabled
                ? healthy ? com.dbcompanion.common.i18n.UiNotice.message("ui.6646678dcf45", "켜짐 · 변경 이력을 수집합니다.") : com.dbcompanion.common.i18n.UiNotice.message("ui.e4f0a092f019", "수집 중단 · 트리거 또는 관리 객체 상태를 확인해 주세요.")
                : installedTriggers < 2 ? com.dbcompanion.common.i18n.UiNotice.message("ui.e3411a26bf5a", "트리거 설치 필요") : healthy ? com.dbcompanion.common.i18n.UiNotice.message("ui.406325e31e14", "트리거 꺼짐 · 저장된 이력은 유지됩니다.") : com.dbcompanion.common.i18n.UiNotice.message("ui.f4bbcd66ee5f", "설치가 완료되지 않았습니다. 다시 켜기 전에 객체 상태를 확인해 주세요."), before, after, config.triggerOwner(), false, "");
    }
    public Configuration configuration(Target target) {
        return configuration(target, true);
    }
    public Configuration configuration(Target target, boolean validate) {
        boolean installed = exists(objects(target), "DBC_METADATA_TRACKING", "TABLE");
        boolean upgrade = false, enabled = false;
        String owner = null;
        if (installed) {
            if (validate) validateTable(target.schema(), "DBC_METADATA_TRACKING");
            upgrade = !jdbc.queryForList("SELECT COLUMN_NAME FROM SYS.ALL_TAB_COLUMNS WHERE OWNER=? AND TABLE_NAME='DBC_METADATA_TRACKING' AND COLUMN_NAME='TRIGGER_OWNER'",
                    String.class, target.schema()).contains("TRIGGER_OWNER");
            var rows = jdbc.query("SELECT ENABLED, " + (upgrade ? "CAST(NULL AS VARCHAR2(128))" : "TRIGGER_OWNER")
                    + " FROM " + HistorySql.qualified(target.schema(), "DBC_METADATA_TRACKING") + " WHERE TABLE_NAME=?",
                    (r,n) -> new Configuration(true, false, "Y".equals(r.getString(1)), r.getString(2)), target.table());
            if (!rows.isEmpty()) { enabled = rows.get(0).enabled(); owner = rows.get(0).triggerOwner(); }
        }
        var owners = jdbc.queryForList("SELECT DISTINCT OWNER FROM SYS.ALL_TRIGGERS WHERE TRIGGER_NAME IN (?,?)",
                String.class, HistorySql.triggerName(target, true), HistorySql.triggerName(target, false));
        owner = resolveOwner(owner, owners);
        if (enabled && owner == null) throw incompatible(target.table(), UiMessages.text("ui.ac354a17b665", "이력이 켜져 있지만 트리거 소유자를 확인할 수 없습니다."));
        return new Configuration(installed, upgrade, enabled, owner);
    }
    public static String resolveOwner(String recorded, List<String> visibleOwners) {
        var owners = new HashSet<>(visibleOwners);
        if (recorded != null) owners.add(recorded);
        if (owners.size() > 1) throw new MetadataEditException(409, "Conflicting history trigger owners", UiMessages.text("ui.e80b893ef8f6", "트리거 소유자가 설정과 다르거나 여러 계정에 설치되어 있습니다. 자동 변경하지 않습니다."));
        return owners.stream().findFirst().orElse(null);
    }
    public void requireHealthyIfTracked(Target target) {
        var objects = objects(target);
        if (objects.isEmpty()) return;
        if (!exists(objects, "DBC_METADATA_TRACKING", "TABLE")) throw incompatible("DBC_METADATA_TRACKING", UiMessages.text("ui.5f51e1e5cf20", "설정 테이블이 없습니다."));
        validateTable(target.schema(), "DBC_METADATA_TRACKING");
        var state = state(target);
        if (state.enabled() && !state.healthy()) throw incompatible(target.table(), UiMessages.text("ui.143220a1470b", "이력 수집이 중단되어 메타데이터 변경을 막았습니다."));
    }
    /** No overwrites: first inspect all existing assets before creating any missing asset. */
    public List<HistorySql.Asset> missingAssets(Target target, String triggerOwner) {
        return missingAssets(target, triggerOwner, true);
    }
    public List<HistorySql.Asset> missingAssets(Target target, String triggerOwner, boolean validate) {
        return missingAssets(target, triggerOwner, validate, false);
    }
    public List<HistorySql.Asset> missingAssets(Target target, String triggerOwner, boolean validate, boolean allowInvalidAuditBody) {
        return missingAssets(target, triggerOwner, validate, allowInvalidAuditBody, false);
    }
    public List<HistorySql.Asset> missingAssets(Target target, String triggerOwner, boolean validate, boolean allowInvalidAuditBody, boolean allowLegacyTriggers) {
        var objects = new ArrayList<>(objects(target));
        objects.addAll(jdbc.query("SELECT OWNER, OBJECT_NAME, OBJECT_TYPE, STATUS FROM SYS.ALL_OBJECTS WHERE OWNER = ? AND OBJECT_NAME IN (?,?)",
                (r,n) -> new DbObject(r.getString(1), r.getString(2), r.getString(3), r.getString(4)),
                triggerOwner, HistorySql.triggerName(target, true), HistorySql.triggerName(target, false)));
        var missing = new ArrayList<HistorySql.Asset>();
        for (var asset : HistorySql.assets(target, triggerOwner)) {
            if (objects.stream().anyMatch(o -> o.owner.equals(asset.owner()) && o.name.equals(asset.name()) && o.type.equals(asset.type()))) {
                if (validate) {
                    if (asset.type().equals("TRIGGER") && allowLegacyTriggers) validateKnownTrigger(target, asset, false);
                    else if (asset.type().equals("PACKAGE BODY") && allowLegacyTriggers) validateKnownAuditBody(target, !allowInvalidAuditBody);
                    else validateAsset(target, asset, !(allowInvalidAuditBody && asset.type().equals("PACKAGE BODY")));
                }
            }
            else {
                boolean incompatibleType = objects.stream().anyMatch(o -> o.owner.equals(asset.owner()) && o.name.equals(asset.name())
                        && !(asset.name().equals("DBC_METADATA_AUDIT") && (o.type.equals("PACKAGE") || o.type.equals("PACKAGE BODY"))));
                if (incompatibleType) throw incompatible(asset.name(), UiMessages.text("ui.3f55b8e9962d", "동명 객체의 종류가 다릅니다."));
                missing.add(asset);
            }
        }
        return missing;
    }
    public List<HistorySql.Asset> legacyTriggers(Target target, String owner) {
        var result = new ArrayList<HistorySql.Asset>();
        for (boolean before : new boolean[]{false, true}) {
            var asset = HistorySql.trigger(target, owner, before);
            if (HistorySql.triggerVersion(target, owner, before, source(asset)) == HistorySql.TriggerVersion.LEGACY) result.add(asset);
        }
        return List.copyOf(result);
    }
    public boolean currentTriggers(Target target, String owner) {
        if (owner == null) return false;
        for (boolean before : new boolean[]{false, true}) {
            var asset = HistorySql.trigger(target, owner, before);
            if (!HistorySql.sourceMatches(asset, source(asset))) return false;
        }
        return true;
    }
    public HistorySql.TriggerVersion validateKnownTrigger(Target target, HistorySql.Asset asset, boolean requireDisabled) {
        boolean before = asset.name().equals(HistorySql.triggerName(target, true));
        var version = HistorySql.triggerVersion(target, asset.owner(), before, source(asset));
        if (version == HistorySql.TriggerVersion.UNKNOWN) throw incompatible(asset.name(), UiMessages.text("ui.92cf858e877b", "확인되지 않은 트리거 원문입니다. 자동 교체하지 않습니다."));
        requireValid(asset.owner(), asset);
        if (requireDisabled) {
            var statuses = jdbc.queryForList("SELECT STATUS FROM SYS.ALL_TRIGGERS WHERE OWNER=? AND TRIGGER_NAME=?", String.class, asset.owner(), asset.name());
            if (!statuses.equals(List.of("DISABLED"))) throw incompatible(asset.name(), UiMessages.text("ui.c5432c1980e2", "이력을 OFF로 하고 두 트리거를 비활성화한 뒤 업데이트해 주세요."));
        }
        return version;
    }
    public void validateAsset(Target target, HistorySql.Asset asset) {
        validateAsset(target, asset, true);
    }
    public void validateAsset(Target target, HistorySql.Asset asset, boolean requireValid) {
        if (asset.type().equals("TABLE")) { validateTable(asset.owner(), asset.name()); return; }
        if (asset.type().equals("INDEX")) {
            var columns = jdbc.queryForList("SELECT COLUMN_NAME FROM SYS.ALL_IND_COLUMNS WHERE INDEX_OWNER = ? AND INDEX_NAME = ? AND TABLE_OWNER = ? AND TABLE_NAME = 'DBC_METADATA_HISTORY' ORDER BY COLUMN_POSITION",
                    String.class, asset.owner(), asset.name(), target.schema());
            if (!columns.equals(List.of("TABLE_NAME", "SEQ"))) throw incompatible(asset.name(), UiMessages.text("ui.72769fe79e88", "인덱스 구조가 다릅니다."));
            return;
        }
        if (!HistorySql.sourceMatches(asset, source(asset))) throw incompatible(asset.name(), UiMessages.text("ui.b9587399d360", "기존 코드가 앱 버전과 다릅니다. 자동 교체하지 않습니다."));
        if (requireValid) requireValid(asset.owner(), asset);
    }
    public boolean auditBodyInvalid(Target target) {
        return jdbc.queryForList("SELECT STATUS FROM SYS.ALL_OBJECTS WHERE OWNER=? AND OBJECT_NAME='DBC_METADATA_AUDIT' AND OBJECT_TYPE='PACKAGE BODY'",
                String.class, target.schema()).contains("INVALID");
    }
    public boolean auditBodyLegacy(Target target) {
        var legacy = HistorySql.auditBody(target, true);
        return HistorySql.legacyAuditBodyMatches(target, source(legacy));
    }
    public void validateKnownAuditBody(Target target, boolean valid) {
        var asset = HistorySql.auditBody(target, false);
        String actual = source(asset);
        if (!HistorySql.sourceMatches(asset, actual) && !HistorySql.legacyAuditBodyMatches(target, actual))
            throw incompatible(asset.name(), UiMessages.text("ui.9a141bd58275", "확인되지 않은 감사 패키지 원문입니다. 교체하지 않습니다."));
        if (valid) requireValid(target.schema(), asset);
    }
    public List<String> auditUpgradeBlockers(Target target) {
        validateTable(target.schema(), "DBC_METADATA_TRACKING");
        var blockers = new ArrayList<String>();
        jdbc.queryForList("SELECT TABLE_NAME FROM " + HistorySql.qualified(target.schema(), "DBC_METADATA_TRACKING")
                + " WHERE ENABLED <> 'N' FETCH FIRST 50 ROWS ONLY", String.class)
                .forEach(name -> blockers.add(UiMessages.text("ui.60b71ab5adca", "이력 ON: ") + name));
        // DBA views are necessary: ALL_* alone cannot prove that other owners' dependencies are inactive.
        jdbc.queryForList("SELECT DISTINCT d.OWNER || '.' || d.NAME FROM SYS.DBA_DEPENDENCIES d "
                + "LEFT JOIN SYS.DBA_TRIGGERS t ON t.OWNER=d.OWNER AND t.TRIGGER_NAME=d.NAME "
                + "WHERE d.REFERENCED_OWNER=? AND d.REFERENCED_NAME='DBC_METADATA_AUDIT' AND d.TYPE='TRIGGER' "
                + "AND (t.STATUS IS NULL OR t.STATUS <> 'DISABLED') FETCH FIRST 50 ROWS ONLY",
                String.class, target.schema()).forEach(name -> blockers.add(UiMessages.text("ui.46399405326b", "비활성을 확인하지 못한 의존 트리거: ") + name));
        jdbc.queryForList("SELECT DISTINCT OWNER || '.' || NAME || ' (' || TYPE || ')' FROM SYS.DBA_DEPENDENCIES "
                + "WHERE REFERENCED_OWNER=? AND REFERENCED_NAME='DBC_METADATA_AUDIT' AND TYPE <> 'TRIGGER' "
                + "AND NOT (OWNER=? AND NAME='DBC_METADATA_AUDIT') FETCH FIRST 50 ROWS ONLY",
                String.class, target.schema(), target.schema()).forEach(name -> blockers.add(UiMessages.text("ui.6827549dc124", "공유 패키지의 추가 의존 객체: ") + name));
        return List.copyOf(blockers);
    }
    public String source(HistorySql.Asset asset) {
        return String.join("", jdbc.queryForList("SELECT TEXT FROM SYS.ALL_SOURCE WHERE OWNER=? AND NAME=? AND TYPE=? ORDER BY LINE",
                String.class, asset.owner(), asset.name(), asset.type()));
    }
    private record Column(String name, String type, int length, String nullable, String identity) {}
    public void validateTable(String owner, String name) {
        var actual = jdbc.query("SELECT COLUMN_NAME, DATA_TYPE, DATA_LENGTH, NULLABLE, IDENTITY_COLUMN FROM SYS.ALL_TAB_COLUMNS WHERE OWNER = ? AND TABLE_NAME = ? ORDER BY COLUMN_ID",
                (r, n) -> new Column(r.getString(1), r.getString(2), r.getInt(3), r.getString(4), r.getString(5)), owner, name);
        var comparable = actual.stream().map(c -> c.name + ":" + c.type + ":"
                + (Set.of("VARCHAR2", "CHAR", "RAW").contains(c.type) ? c.length : 0) + ":" + c.nullable + ":" + c.identity).toList();
        if (!compatibleColumns(name, comparable)) throw incompatible(name, UiMessages.text("ui.e2584758c6ba", "컬럼 구조가 앱 버전과 다릅니다."));
        String pk = name.equals("DBC_METADATA_TRACKING") ? "DBC_META_TRACK_PK" : "DBC_META_HIST_PK";
        var keys = jdbc.queryForList("SELECT COLUMN_NAME FROM SYS.ALL_CONS_COLUMNS WHERE OWNER = ? AND TABLE_NAME = ? AND CONSTRAINT_NAME = ? ORDER BY POSITION",
                String.class, owner, name, pk);
        if (!keys.equals(List.of(name.equals("DBC_METADATA_TRACKING") ? "TABLE_NAME" : "SEQ"))) throw incompatible(name, UiMessages.text("ui.5e46c7447dc2", "기본키가 다릅니다."));
        var constraints = jdbc.queryForList("SELECT CONSTRAINT_NAME FROM SYS.ALL_CONSTRAINTS WHERE OWNER = ? AND TABLE_NAME = ? AND STATUS = 'ENABLED' AND VALIDATED = 'VALIDATED'", String.class, owner, name);
        if (!constraints.contains(pk)) throw incompatible(name, UiMessages.text("ui.af4b4b950190", "기본키가 활성화되어 있지 않습니다."));
        var checks = jdbc.queryForList("SELECT SEARCH_CONDITION_VC FROM SYS.ALL_CONSTRAINTS WHERE OWNER = ? AND TABLE_NAME = ? AND CONSTRAINT_TYPE = 'C' AND STATUS = 'ENABLED' AND VALIDATED = 'VALIDATED'", String.class, owner, name)
                .stream().map(s -> s == null ? "" : s.replaceAll("[\\s\"()]", "").toUpperCase(Locale.ROOT)).toList();
        var required = name.equals("DBC_METADATA_TRACKING") ? List.of("ENABLEDIN'Y','N'") : List.of("BEFORE_JSONISJSON", "AFTER_JSONISJSON");
        if (!checks.containsAll(required)) throw incompatible(name, UiMessages.text("ui.a99e9c61c530", "검증 제약조건이 다릅니다."));
        if (name.equals("DBC_METADATA_HISTORY")) {
            var identity = jdbc.queryForList("SELECT GENERATION_TYPE FROM SYS.ALL_TAB_IDENTITY_COLS WHERE OWNER = ? AND TABLE_NAME = ? AND COLUMN_NAME = 'SEQ'", String.class, owner, name);
            if (!identity.equals(List.of("ALWAYS"))) throw incompatible(name, UiMessages.text("ui.51cec98f2413", "SEQ identity 생성 방식이 다릅니다."));
        }
    }
    public static boolean compatibleColumns(String name, List<String> actual) {
        var expected = switch (name) {
            case "DBC_METADATA_TRACKING" -> List.of("TABLE_NAME:VARCHAR2:128:N:NO", "ENABLED:CHAR:1:N:NO", "CHANGED_AT:TIMESTAMP(6) WITH TIME ZONE:0:N:NO", "CHANGED_BY:VARCHAR2:128:N:NO");
            case "DBC_METADATA_HISTORY" -> List.of("SEQ:NUMBER:0:N:YES", "EVENT_ID:RAW:16:N:NO", "CHANGED_AT:TIMESTAMP(6) WITH TIME ZONE:0:N:NO", "CHANGED_BY:VARCHAR2:128:N:NO", "SCHEMA_NAME:VARCHAR2:128:N:NO", "TABLE_NAME:VARCHAR2:128:N:NO", "COLUMN_NAME:VARCHAR2:128:Y:NO", "CHANGE_KIND:VARCHAR2:16:N:NO", "ANNOTATION_NAME:VARCHAR2:4000:Y:NO", "BEFORE_JSON:CLOB:0:N:NO", "AFTER_JSON:CLOB:0:N:NO");
            default -> List.<String>of();
        };
        if (expected.isEmpty()) return false;
        if (actual.equals(expected)) return true;
        var upgraded = new ArrayList<>(expected);
        if (name.equals("DBC_METADATA_TRACKING")) upgraded.add("TRIGGER_OWNER:VARCHAR2:128:Y:NO");
        return actual.equals(upgraded);
    }
    public void requireValid(String owner, HistorySql.Asset asset) {
        var errors = jdbc.query("SELECT LINE, POSITION, TEXT FROM SYS.ALL_ERRORS WHERE OWNER = ? AND NAME = ? AND TYPE = ? AND ATTRIBUTE = 'ERROR' ORDER BY SEQUENCE",
                (r, n) -> r.getInt(1) + ":" + r.getInt(2) + " " + r.getString(3), owner, asset.name(), asset.type());
        if (!errors.isEmpty()) throw incompatible(asset.name(), UiMessages.text("ui.a3b4b1763a57", "컴파일 오류: ") + String.join(" | ", errors));
        var valid = jdbc.queryForList("SELECT STATUS FROM SYS.ALL_OBJECTS WHERE OWNER = ? AND OBJECT_NAME = ? AND OBJECT_TYPE = ?", String.class, owner, asset.name(), asset.type());
        if (!valid.equals(List.of("VALID"))) throw incompatible(asset.name(), UiMessages.text("ui.956c37b32547", "객체가 VALID 상태가 아닙니다."));
    }
    public void execute(String sql) { jdbc.execute(sql); }
    public void claimOwner(Target target, String owner) {
        jdbc.update("MERGE INTO " + HistorySql.qualified(target.schema(), "DBC_METADATA_TRACKING") + " t USING (SELECT ? TABLE_NAME FROM SYS.DUAL) s ON (t.TABLE_NAME = s.TABLE_NAME) "
                + "WHEN MATCHED THEN UPDATE SET TRIGGER_OWNER = ?, CHANGED_AT = SYSTIMESTAMP, CHANGED_BY = SYS_CONTEXT('USERENV','SESSION_USER') WHERE t.TRIGGER_OWNER IS NULL "
                + "WHEN NOT MATCHED THEN INSERT (TABLE_NAME, ENABLED, CHANGED_AT, CHANGED_BY, TRIGGER_OWNER) VALUES (s.TABLE_NAME, 'N', SYSTIMESTAMP, SYS_CONTEXT('USERENV','SESSION_USER'), ?)",
                target.table(), owner, owner);
    }
    public void setEnabled(Target target, String owner, boolean enabled, boolean legacy) {
        if (legacy && enabled) throw incompatible(target.table(), UiMessages.text("ui.d7914ef8a8bc", "설정 테이블 업그레이드가 필요합니다."));
        String sql = "UPDATE " + HistorySql.qualified(target.schema(), "DBC_METADATA_TRACKING")
                + " SET ENABLED=?, CHANGED_AT=SYSTIMESTAMP, CHANGED_BY=SYS_CONTEXT('USERENV','SESSION_USER') WHERE TABLE_NAME=?";
        int updated = legacy ? jdbc.update(sql, "N", target.table())
                : jdbc.update(sql + (enabled ? " AND TRIGGER_OWNER=?" : " AND (TRIGGER_OWNER=? OR TRIGGER_OWNER IS NULL)"),
                        enabled ? "Y" : "N", target.table(), owner);
        if (updated != 1) throw incompatible(target.table(), UiMessages.text("ui.26788bc93f9e", "트리거 소유자 설정이 변경되었습니다. 다시 확인해 주세요."));
    }
    public Page history(Target target, int page) {
        if (!exists(objects(target), "DBC_METADATA_HISTORY", "TABLE")) return new Page(List.of(), page, false);
        validateTable(target.schema(), "DBC_METADATA_HISTORY");
        var rows = jdbc.query("SELECT SEQ, RAWTOHEX(EVENT_ID), TO_CHAR(CHANGED_AT, 'YYYY-MM-DD HH24:MI:SS.FF3 TZH:TZM'), CHANGED_BY, SCHEMA_NAME, TABLE_NAME, COLUMN_NAME, CHANGE_KIND, ANNOTATION_NAME, BEFORE_JSON, AFTER_JSON "
                + "FROM " + HistorySql.qualified(target.schema(), "DBC_METADATA_HISTORY") + " WHERE SCHEMA_NAME = ? AND TABLE_NAME = ? ORDER BY SEQ DESC OFFSET ? ROWS FETCH NEXT 11 ROWS ONLY",
                (r, n) -> new Change(r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5), r.getString(6), r.getString(7), r.getString(8), r.getString(9), r.getString(10), r.getString(11)),
                target.schema(), target.table(), (page - 1) * 10);
        return new Page(List.copyOf(rows.subList(0, Math.min(10, rows.size()))), page, rows.size() > 10);
    }
    private MetadataEditException incompatible(String name, String detail) {
        return new MetadataEditException(409, "Incompatible or unhealthy history asset: " + name, name + " · " + detail);
    }
}
