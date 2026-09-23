package com.dbcompanion.service;

import com.dbcompanion.common.i18n.UiMessages;

import com.dbcompanion.common.db.*;
import com.dbcompanion.common.exception.MetadataEditException;
import com.dbcompanion.model.ProfileHistory.*;
import com.dbcompanion.repository.ProfileHistoryRepository;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ProfileHistoryService {
    private final SessionDataSource source;
    private final ProfileHistoryRepository repository;
    private final TransactionTemplate read, write;
    public ProfileHistoryService(SessionDataSource source, ProfileHistoryRepository repository) {
        this.source = source; this.repository = repository;
        var manager = new DataSourceTransactionManager(source);
        read = new TransactionTemplate(manager); read.setReadOnly(true); read.setTimeout(10);
        write = new TransactionTemplate(manager); write.setTimeout(20);
    }
    public static void validate(String login, String selected, Target target) {
        if (target == null || target.schema() == null || !target.schema().equals(selected))
            throw new MetadataEditException(400, "Schema changed", UiMessages.text("ui.5528873ed802", "선택 스키마가 변경되었습니다. 화면을 다시 열어 주세요."));
        if (!login.equals(selected) && !login.equals("ADMIN"))
            throw new MetadataEditException(403, "Profile schema restricted", UiMessages.text("ui.8d4ac3ceea1a", "프로필 소유자 또는 ADMIN 계정으로 해당 스키마를 선택해 주세요."));
        if (target.profile() != null && (target.profile().isBlank() || target.profile().length() > 128 || target.profile().indexOf('\0') >= 0))
            throw new MetadataEditException(400, "Invalid profile", UiMessages.text("ui.de2dfe445cba", "프로필명을 확인해 주세요."));
    }
    private <T> T bound(PoolSession session, Target target, Supplier<T> action) {
        synchronized (session) {
            validate(session.metadata().info().username(), session.metadata().selectedSchema(), target);
            source.bind(session.pool(), target.schema());
            try { return action.get(); } finally { source.clear(); }
        }
    }
    private String owner(PoolSession session) { return repository.packageOwner(session.metadata().info().username()); }
    public State state(PoolSession session, Target target) {
        return bound(session, target, () -> read.execute(s -> state(target.schema(), owner(session))));
    }
    private State state(String schema, String owner) {
        boolean canManage = repository.canManage();
        Boolean enabled = auditCapability(() -> repository.policyExists(schema, owner) && repository.enabled(schema));
        boolean installed;
        boolean configured;
        try { installed = repository.archiveReady(schema); configured=installed&&repository.auditConfigured(schema,owner); }
        catch (MetadataEditException ex) {
            // An archive mismatch must not prevent explicitly disabling a valid app-owned audit policy.
            return new State(false, enabled, Boolean.TRUE.equals(enabled) && canManage, false, ProfileHistorySql.policy(schema), ex.userMessage());
        }
        if (enabled == null)
            return new State(installed, null, false, false, ProfileHistorySql.policy(schema),
                    UiMessages.text("ui.1c363f6623a6", "감사 상태 확인 권한이 없습니다. 감사 설정·수집은 관리자 계정에서 확인해 주세요.")
                            + (installed ? UiMessages.text("ui.c5d2f67814e2", " 보관된 이력은 조회할 수 있습니다.") : UiMessages.text("ui.1ef95729ebfd", " 보관 테이블이 아직 설치되지 않았습니다.")));
        boolean readable = (installed || canManage) && Boolean.TRUE.equals(auditCapability(() -> { repository.auditReadable(); return true; }));
        String message = enabled ? UiMessages.text("ui.06140eeb0302", "계정 감사 ON · 이력 새로고침에서 보관합니다.") : UiMessages.text("ui.c935f89b42e5", "계정 감사 OFF · 기존 보관 이력은 유지됩니다.");
        if (!installed) message += UiMessages.text("ui.1ef95729ebfd", " 보관 테이블이 아직 설치되지 않았습니다.");
        if (!canManage) message += UiMessages.text("ui.5435efee2252", " 감사 설정은 관리자 계정에서 확인해 주세요.");
        if ((installed || canManage) && !readable) message += UiMessages.text("ui.eb264464d626", " 감사 원천 조회 권한이 없어 수집할 수 없습니다.");
        return new State(installed, enabled, canManage && (enabled || readable), configured && readable, ProfileHistorySql.policy(schema), message);
    }
    /** Only use around audit-system reads, not archive reads or arbitrary database operations. */
    public static <T> T auditCapability(Supplier<T> query) {
        try { return query.get(); }
        catch (RuntimeException ex) { if (auditAccessDenied(ex)) return null; throw ex; }
    }
    public static boolean auditAccessDenied(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.sql.SQLException sql) return sql.getErrorCode() == 942 || sql.getErrorCode() == 1031;
        }
        return false;
    }
    public Page history(PoolSession session, Target target, String scope, int page) {
        if (page < 1 || page > 1_000_000 || !Set.of("profile", "all", "unclassified").contains(scope))
            throw new MetadataEditException(400, "Invalid history query", UiMessages.text("ui.035e4490e438", "조회 범위와 페이지를 확인해 주세요."));
        return bound(session, target, () -> read.execute(s -> {
            // Viewing previously archived data does not require access to UNIFIED_AUDIT_TRAIL.
            return repository.page(target.schema(), scope.equals("all") ? null : target.profile(), scope, page);
        }));
    }
    public synchronized State toggle(PoolSession session, Toggle request) {
        Target target = new Target(request.schema(), null);
        return bound(session, target, () -> {
            String stage = UiMessages.text("ui.529607c4e1fc", "감사 설정 사전 확인");
            try {
                if (!read.execute(s -> repository.canManage()))
                    throw new MetadataEditException(403, "Audit privileges required", UiMessages.text("ui.216068ac3415", "AUDIT SYSTEM 또는 AUDIT_ADMIN 권한이 필요합니다. 자동 권한 부여는 하지 않습니다."));
                String owner = read.execute(s -> owner(session));
                boolean exists = read.execute(s -> repository.policyExists(target.schema(), owner));
                boolean enabled = exists && read.execute(s -> repository.enabled(target.schema()));
                if (request.enabled()) {
                    stage = UiMessages.text("ui.336342348ccb", "감사 원천 조회 권한 확인");
                    read.executeWithoutResult(s -> repository.auditReadable());
                    // Validate every existing asset before creating any missing one.
                    for (String name : List.of(ProfileHistorySql.CONFIG)) read.executeWithoutResult(s -> repository.table(target.schema(), name));
                    stage = UiMessages.text("ui.e9fc60192715", "공통 이력 준비·이전");
                    write.executeWithoutResult(s -> repository.installArchive(target.schema()));
                    for (var asset : Map.of(ProfileHistorySql.CONFIG,ProfileHistorySql.tables(target.schema()).get(ProfileHistorySql.CONFIG)).entrySet()) {
                        if (!read.execute(s -> repository.table(target.schema(), asset.getKey()))) {
                            stage = asset.getKey() + UiMessages.text("ui.b58dfde085db", " 생성");
                            write.executeWithoutResult(s -> repository.execute(asset.getValue()));
                            stage = asset.getKey() + UiMessages.text("ui.5712553e7890", " 버전 마커 기록");
                            write.executeWithoutResult(s -> repository.markTable(target.schema(), asset.getKey()));
                            read.executeWithoutResult(s -> repository.table(target.schema(), asset.getKey()));
                        }
                    }
                    stage = UiMessages.text("ui.944edd21956a", "보관 설정 기록");
                    write.executeWithoutResult(s -> repository.configure(target.schema(), owner));
                    if (!read.execute(s -> repository.configured(target.schema(), owner))) throw new IllegalStateException("Archive configuration not verified");
                    if (!exists) {
                        stage = UiMessages.text("ui.6955f8651475", "앱 전용 감사 정책 생성");
                        write.executeWithoutResult(s -> repository.execute(ProfileHistorySql.createPolicy(target.schema(), owner)));
                    }
                    read.executeWithoutResult(s -> repository.policyExists(target.schema(), owner));
                }
                if (enabled != request.enabled()) {
                    stage = request.enabled() ? UiMessages.text("ui.0d07df71e25c", "계정 감사 ON") : UiMessages.text("ui.727db0715c19", "계정 감사 OFF");
                    write.executeWithoutResult(s -> repository.execute(ProfileHistorySql.toggle(target.schema(), request.enabled())));
                }
                stage = UiMessages.text("ui.9175300cd271", "감사 상태 재확인");
                State result = read.execute(s -> state(target.schema(), owner));
                if (!Boolean.valueOf(request.enabled()).equals(result.enabled())) throw new IllegalStateException("Audit state did not match requested state");
                return result;
            } catch (RuntimeException ex) {
                throw failure(stage, ex, UiMessages.text("ui.1ec1f631cdf0", "앞 단계 DDL은 남아 있을 수 있습니다. 상태를 다시 확인해 주세요. 기존 이력은 삭제하지 않았습니다."));
            }
        });
    }
    public synchronized State install(PoolSession session,Target target) {
        return bound(session,target,()->{
            try {
                write.executeWithoutResult(s->repository.installArchive(target.schema()));
                return read.execute(s->state(target.schema(),owner(session)));
            }catch(RuntimeException ex){throw failure(UiMessages.text("ui.e9fc60192715", "공통 이력 준비·이전"),ex,UiMessages.text("ui.02560d860bca", "생성 객체는 남을 수 있습니다. 이전 원본은 삭제하지 않았으며 감사 정책·업무 설정은 변경하지 않았습니다."));}
        });
    }
    public synchronized Collected collect(PoolSession session, Target target) {
        return bound(session, target, () -> {
            try {
                return write.execute(s -> {
                    String owner = owner(session);
                    if (!repository.configured(target.schema(), owner))
                        throw new MetadataEditException(409, "Archive not installed", UiMessages.text("ui.32ea5e6469ea", "감사를 한 번 켜 보관 테이블을 설치한 뒤 수집할 수 있습니다."));
                    try {
                        repository.policyExists(target.schema(), owner); // Refuse an unrelated same-name policy.
                        repository.auditReadable();
                    } catch (RuntimeException ex) {
                        if (!auditAccessDenied(ex)) throw ex;
                        throw new MetadataEditException(403, "Audit read privileges required",
                                UiMessages.text("ui.aa40bc41db27", "감사 원천 조회 권한이 없습니다. 관리자 계정에서 수집해 주세요. 보관된 이력은 별도로 조회할 수 있습니다."));
                    }
                    repository.lock(target.schema());
                    var pending = repository.pending(target.schema(), owner);
                    int count = 0, entries = 0;
                    for (var audit : pending.stream().limit(100).toList()) {
                        var requests = audit.truncated() ? List.<RequestValue>of() : ProfileAuditParser.parse(audit.sql(), audit.binds(), owner);
                        var raw = new LinkedHashMap<String, Object>();
                        raw.put("sql", audit.sql()); raw.put("binds", audit.binds()); raw.put("returnCode", audit.returnCode());
                        raw.put("client", audit.client()); raw.put("captureTruncated", audit.truncated());
                        if (requests.isEmpty()) {
                            repository.insert(target.schema(), audit.key(), 0, null, audit.eventAt(), audit.actor(), "UNCLASSIFIED", raw); entries++;
                        } else {
                            int item = 0;
                            for (var request : requests) {
                                var payload = new LinkedHashMap<String, Object>(raw); payload.put("request", request);
                                repository.insert(target.schema(), audit.key(), item++, request.profile(), audit.eventAt(), audit.actor(), "REQUEST", payload); entries++;
                            }
                        }
                        count++;
                    }
                    String login = session.metadata().info().username();
                    boolean snapshot = target.profile() != null && repository.snapshot(target.schema(), target.profile(), login, login.equals(target.schema()));
                    return new Collected(count, entries, pending.size() > 100, snapshot);
                });
            } catch (RuntimeException ex) {
                throw failure(UiMessages.text("ui.128a139c13ca", "이력 수집/현재값 보관"), ex, UiMessages.text("ui.33e6345847f7", "이번 배치의 이력 저장은 롤백했습니다. 감사 설정과 업무 프로필은 변경하지 않았습니다."));
            }
        });
    }
    private static MetadataEditException failure(String stage, RuntimeException ex, String impact) {
        Throwable cause = ex; while (cause.getCause() != null && !(cause instanceof java.sql.SQLException)) cause = cause.getCause();
        String detail = ex instanceof MetadataEditException edit ? edit.userMessage()
                : cause instanceof java.sql.SQLException sql ? "Oracle code=" + sql.getErrorCode() + " · " + sql.getMessage() : cause.getClass().getSimpleName() + ": " + cause.getMessage();
        return new MetadataEditException(ex instanceof MetadataEditException edit ? edit.status() : 503, "Profile history stopped at " + stage, stage + UiMessages.text("ui.9aaf393719f7", "에서 중단했습니다. ") + detail + " " + impact);
    }
}
