package com.dbcompanion.service;

import com.dbcompanion.common.i18n.UiMessages;
import com.dbcompanion.common.i18n.UiNotice;

import com.dbcompanion.common.db.*;
import com.dbcompanion.common.exception.MetadataEditException;
import com.dbcompanion.model.MetadataEdit.Target;
import com.dbcompanion.model.MetadataHistory.*;
import com.dbcompanion.repository.*;
import java.util.function.Supplier;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class MetadataHistoryService {
    private final SessionDataSource source;
    private final MetadataRepository metadata;
    private final MetadataHistoryRepository repository;
    private final HistoryReadinessRepository readiness;
    private final TransactionTemplate read, write;
    public MetadataHistoryService(SessionDataSource source, MetadataRepository metadata, MetadataHistoryRepository repository, HistoryReadinessRepository readiness) {
        this.source = source; this.metadata = metadata; this.repository = repository; this.readiness = readiness;
        var manager = new DataSourceTransactionManager(source);
        read = new TransactionTemplate(manager); read.setReadOnly(true); read.setTimeout(10);
        write = new TransactionTemplate(manager); write.setTimeout(10);
    }
    public State state(PoolSession session, Target target) { return state(session,target,false); }
    public State state(PoolSession session, Target target,boolean refresh) {
        synchronized(session) {
            validateScope(session,target);
            return session.metadata().historyState(target.schema(),target.table(),refresh,
                    ()->bound(session,target,()->read.execute(s->stateWithAccess(target))));
        }
    }
    private State remember(PoolSession session,Target target,State state) {
        return session.metadata().rememberHistoryState(target.schema(),target.table(),state);
    }
    private State stateWithAccess(Target target) {
        var state = repository.state(target);
        if (state.setup() != null) return state;
        try {
            var ready = readiness.prepare(target, !state.enabled(), false);
            var decision = ready.decision();
            var access = ready.access() != null && ready.access().ownerCheckError() != null ? SetupAccess.UNCONFIRMED
                    : decision.allowed() ? SetupAccess.ALLOWED : SetupAccess.REQUIRED;
            if (!state.enabled() && ready.auditUpgradeRequired())
                return state.withSetup(SetupStep.AUDIT_UPDATE, access).withAccess(false, UiNotice.concat(
                        UiNotice.message("ui.b4eb38fa061b", "설치 확인에서 감사 패키지 업데이트가 필요합니다."),
                        decision.allowed() ? UiNotice.raw("") : UiNotice.concat(UiNotice.raw(" · "), decision.notice())));
            if (!state.enabled() && !ready.legacyTriggers().isEmpty())
                return state.withSetup(SetupStep.TRIGGER_UPDATE, access).withAccess(false, UiNotice.concat(
                        UiNotice.message("ui.634bda175ae5", "설치 확인에서 트리거 업데이트가 필요합니다."),
                        decision.allowed() ? UiNotice.raw("") : UiNotice.concat(UiNotice.raw(" · "), decision.notice())));
            var step = !state.healthy() ? SetupStep.INSPECT : !state.installed() ? SetupStep.INSTALL
                    : state.enabled() ? SetupStep.NONE : SetupStep.ENABLE;
            return state.withSetup(step, access).withAccess(decision.allowed(), decision.notice());
        } catch (MetadataEditException ex) {
            return state.withSetup(SetupStep.INSPECT, SetupAccess.UNCONFIRMED).withAccess(false, ex.userMessage());
        } catch (org.springframework.dao.DataAccessException ex) {
            Throwable cause = ex;
            while (cause.getCause() != null && !(cause instanceof java.sql.SQLException)) cause = cause.getCause();
            return state.withSetup(SetupStep.INSPECT, SetupAccess.UNCONFIRMED).withAccess(false,UiNotice.concat(UiNotice.message("ui.de1e3f73010f", "관리 권한 조회 오류: "),UiNotice.raw(cause instanceof java.sql.SQLException sql ? sql.getMessage() : cause.getClass().getSimpleName())));
        }
    }
    public Page history(PoolSession session, Target target, int page) {
        if (page < 1 || page > 1_000_000) throw new MetadataEditException(400, "Invalid history page", UiMessages.text("ui.3d052faf2928", "페이지 번호를 확인해 주세요."));
        return bound(session, target, () -> read.execute(s -> repository.history(target, page)));
    }
    // Serialize bootstrap/toggles in this app. DDL and tracking DML have separate, explicit commit boundaries.
    public synchronized State toggle(PoolSession session, Target target, boolean enabled) {
        return bound(session, target, () -> {
            String stage = UiMessages.text("ui.f3b7242cce41", "설치 전 확인");
            try {
                var delegated = read.execute(s -> repository.state(target));
                if (delegated.setup() != null) {
                    if (!delegated.canManage()) throw HistoryAccessRepository.denied();
                    stage = UiMessages.text("history.access.switch", "위임된 이력 수집 설정 변경");
                    write.executeWithoutResult(s -> repository.delegatedToggle(target,enabled));
                    return remember(session,target,read.execute(s -> stateWithAccess(target)));
                }
                var ready = read.execute(s -> readiness.prepare(target, enabled));
                if (enabled && (ready.auditUpgradeRequired() || !ready.legacyTriggers().isEmpty()))
                    throw new MetadataEditException(409, "Legacy history code requires an explicit update", UiMessages.text("ui.dba2a54339c9", "설치 확인에서 이력 코드를 업데이트한 뒤 이력을 켜 주세요. DB 객체는 변경하지 않았습니다."));
                if (!ready.decision().allowed())
                    throw new MetadataEditException(403, "History management privileges missing", ready.decision().message() + UiMessages.text("ui.585826ceba98", ". DB 객체는 변경하지 않았습니다."));
                String owner = ready.triggerOwner();
                var missing = ready.missing();
                if (!enabled && !ready.configuration().installed()) return remember(session,target,read.execute(s -> stateWithAccess(target)));
                if (enabled) {
                    for (var asset : missing.stream().filter(a -> !a.type().equals("TRIGGER")).toList()) {
                        stage = asset.owner() + "." + asset.name() + " " + asset.type() + UiMessages.text("ui.b58dfde085db", " 생성");
                        write.executeWithoutResult(s -> repository.execute(asset.sql()));
                        read.executeWithoutResult(s -> repository.requireValid(asset.owner(), asset));
                    }
                    if (ready.configuration().needsUpgrade()) {
                        stage = UiMessages.text("ui.563d486c27d4", "이력 설정 테이블 TRIGGER_OWNER 컬럼 추가");
                        write.executeWithoutResult(s -> repository.execute(HistorySql.upgradeTracking(target)));
                        read.executeWithoutResult(s -> repository.validateTable(target.schema(), "DBC_METADATA_TRACKING"));
                    }
                    if (ready.auditCompileRequired()) {
                        stage = UiMessages.text("ui.1316954aeb88", "감사 패키지 본문 재컴파일");
                        var body = HistorySql.assets(target, owner).stream().filter(a -> a.type().equals("PACKAGE BODY")).findFirst().orElseThrow();
                        read.executeWithoutResult(s -> repository.validateAsset(target, body, false));
                        write.executeWithoutResult(s -> repository.execute(HistorySql.compileAuditBody(target)));
                        read.executeWithoutResult(s -> repository.validateAsset(target, body));
                    }
                    stage = UiMessages.text("ui.3b66d4b9adf8", "트리거 소유자 기록");
                    write.executeWithoutResult(s -> repository.claimOwner(target, owner));
                    var claimed = read.execute(s -> repository.configuration(target));
                    if (!owner.equals(claimed.triggerOwner()))
                        throw new MetadataEditException(409, "History owner changed during installation", UiMessages.text("ui.4c4f3749ed5b", "다른 계정이 먼저 설치자를 기록했습니다. 트리거를 만들지 않았습니다. 상태를 다시 확인해 주세요."));
                    for (var asset : missing.stream().filter(a -> a.type().equals("TRIGGER")).toList()) {
                        stage = asset.owner() + "." + asset.name() + UiMessages.text("ui.4754af8cd00d", " TRIGGER 생성");
                        write.executeWithoutResult(s -> repository.execute(asset.sql()));
                        read.executeWithoutResult(s -> repository.requireValid(asset.owner(), asset));
                    }
                    stage = UiMessages.text("ui.a4cb8d5fa08a", "전용 트리거 활성화");
                    for (boolean before : new boolean[]{false, true}) {
                        var trigger = HistorySql.trigger(target, owner, before);
                        read.executeWithoutResult(s -> repository.validateAsset(target, trigger));
                        write.executeWithoutResult(s -> repository.execute(HistorySql.switchTrigger(target, owner, before, true)));
                    }
                    stage = UiMessages.text("ui.2ca1eb04b624", "테이블 이력 설정 ON");
                    write.executeWithoutResult(s -> repository.setEnabled(target, owner, true, false));
                } else {
                    stage = UiMessages.text("ui.1ad8909d8f25", "테이블 이력 설정 OFF");
                    write.executeWithoutResult(s -> repository.setEnabled(target, owner, false, ready.configuration().needsUpgrade()));
                    stage = UiMessages.text("ui.618777ee127b", "전용 트리거 비활성화");
                    for (boolean before : new boolean[]{true, false}) {
                        var trigger = HistorySql.trigger(target, owner, before);
                        if (missing.stream().noneMatch(a -> a.equals(trigger)))
                            write.executeWithoutResult(s -> repository.execute(HistorySql.switchTrigger(target, owner, before, false)));
                    }
                }
                stage = UiMessages.text("ui.938d00c35fb2", "최종 상태 확인");
                return remember(session,target,read.execute(s -> stateWithAccess(target)));
            } catch (MetadataEditException ex) {
                session.metadata().uncertainHistoryState(target.schema(),target.table());
                if (stage.equals(UiMessages.text("ui.f3b7242cce41", "설치 전 확인"))) throw ex;
                throw new MetadataEditException(ex.status(), "History operation stopped at " + stage + ": " + ex.getMessage(),
                        stage + UiMessages.text("ui.88aa33a0b2a6", " 단계에서 중단했습니다. 앞 단계의 변경은 남아 있을 수 있습니다. ") + ex.userMessage());
            }
            catch (RuntimeException ex) {
                session.metadata().uncertainHistoryState(target.schema(),target.table());
                Throwable cause = ex;
                while (cause.getCause() != null && !(cause instanceof java.sql.SQLException)) cause = cause.getCause();
                String detail = cause instanceof java.sql.SQLException sql ? sql.getMessage() : cause.getClass().getSimpleName();
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("History operation stopped at {}: {}", stage, detail);
                throw new MetadataEditException(503, "History operation failed at " + stage,
                        stage + UiMessages.text("ui.459b9efc9836", " 단계에서 중단했습니다. 앞서 생성한 객체는 남아 있을 수 있습니다. 상태를 다시 확인해 주세요. ") + detail);
            }
        });
    }
    /** Explicit schema-wide body migration: no selected-table trigger requirement or mutation. */
    public synchronized State upgradeAudit(PoolSession session, Target target) {
        return bound(session, target, () -> {
            boolean attempted = false;
            try {
                var initial = read.execute(s -> readiness.prepareAuditUpgrade(target));
                if (!initial.allowed()) throw new MetadataEditException(409, "Shared audit update not allowed",
                        String.join(" · ", initial.blockers()) + " " + UiMessages.text("history.code.noChange", "공통 패키지를 변경하지 않았습니다. 설치 확인에서 현재 상태를 확인해 주세요."));
                read.executeWithoutResult(s -> {
                    var current = readiness.prepareAuditUpgrade(target);
                    if (!current.allowed() || current.version() != initial.version())
                        throw new MetadataEditException(409, "Shared audit state changed", UiMessages.text("history.code.changed", "패키지 또는 의존 객체 상태가 변경되었습니다. 다시 확인해 주세요."));
                });
                attempted = true;
                write.executeWithoutResult(s -> repository.execute(HistorySql.replaceAuditBody(target)));
                read.executeWithoutResult(s -> repository.validateAsset(target, HistorySql.auditBody(target, false)));
                return read.execute(s -> stateWithAccess(target));
            } catch (MetadataEditException ex) {
                if (attempted) throw new MetadataEditException(ex.status(), "Shared audit update not verified",
                        ex.userMessage() + " " + UiMessages.text("history.code.uncertain", "공통 패키지 업데이트 결과를 확인하지 못했습니다. 설치 확인에서 다시 확인해 주세요. 트리거와 이력 ON/OFF 설정은 변경하지 않았습니다."));
                throw ex;
            } catch (RuntimeException ex) {
                throw new MetadataEditException(503, "Shared audit update verification failed",
                        UiMessages.text("history.code.uncertain", "공통 패키지 업데이트 결과를 확인하지 못했습니다. 설치 확인에서 다시 확인해 주세요. 트리거와 이력 ON/OFF 설정은 변경하지 않았습니다."));
            } finally {
                // A body is shared by all tracked objects in this schema; do not retain their old cached status.
                session.metadata().forgetHistorySchema(target.schema());
            }
        });
    }
    /** Selected-table trigger migration only. GET/ON never replaces existing code. */
    public synchronized State upgradeCode(PoolSession session, Target target) {
        return bound(session, target, () -> {
            String stage = UiMessages.text("ui.a95dcfa4ce35", "이력 코드 업데이트 사전 검사");
            try {
                var ready = read.execute(s -> readiness.prepare(target, true));
                if (ready.auditUpgradeRequired()) throw new MetadataEditException(409, "Shared audit update required",
                        UiMessages.text("history.code.auditFirst", "먼저 공통 패키지를 업데이트한 뒤 이 테이블·뷰의 트리거를 업데이트해 주세요."));
                if (!ready.configuration().installed() || ready.configuration().enabled() || ready.configuration().needsUpgrade()
                        || !ready.missing().isEmpty() || ready.auditCompileRequired())
                    throw new MetadataEditException(409, "History update requires an intact OFF installation",
                            UiMessages.text("ui.e2706765a6de", "설치가 정상이고 이력이 OFF일 때만 이력 코드를 업데이트할 수 있습니다."));
                if (!ready.decision().allowed()) throw new MetadataEditException(403, "History update privileges missing", ready.decision().message());
                String owner = ready.triggerOwner();
                for (boolean before : new boolean[]{false, true})
                    read.executeWithoutResult(s -> repository.validateKnownTrigger(target, HistorySql.trigger(target, owner, before), true));
                for (boolean before : new boolean[]{false, true}) {
                    var trigger = HistorySql.trigger(target, owner, before);
                    stage = trigger.owner() + "." + trigger.name() + UiMessages.text("ui.172c816f2bde", " 원문 재검사 및 교체");
                    var config = read.execute(s -> repository.configuration(target));
                    if (config.enabled() || !owner.equals(config.triggerOwner()))
                        throw new MetadataEditException(409, "History state changed during update", UiMessages.text("ui.3fe6f17ac822", "이력 설정 또는 소유자가 변경되어 중단했습니다."));
                    var version = read.execute(s -> repository.validateKnownTrigger(target, trigger, true));
                    if (version == HistorySql.TriggerVersion.LEGACY)
                        write.executeWithoutResult(s -> repository.execute(HistorySql.replaceTrigger(target, owner, before)));
                    read.executeWithoutResult(s -> {
                        repository.validateAsset(target, trigger);
                        repository.validateKnownTrigger(target, trigger, true);
                    });
                }
                return remember(session,target,read.execute(s -> stateWithAccess(target)));
            } catch (MetadataEditException ex) {
                session.metadata().uncertainHistoryState(target.schema(),target.table());
                throw new MetadataEditException(ex.status(), "History trigger update stopped at " + stage,
                        stage + UiMessages.text("ui.9aaf393719f7", "에서 중단했습니다. ") + ex.userMessage() + UiMessages.text("ui.f9d2a7c54db8", " 앞서 교체한 이력 코드는 남아 있을 수 있으며 자동 활성화하지 않습니다."));
            } catch (RuntimeException ex) {
                session.metadata().uncertainHistoryState(target.schema(),target.table());
                Throwable cause = ex;
                while (cause.getCause() != null && !(cause instanceof java.sql.SQLException)) cause = cause.getCause();
                String detail = cause instanceof java.sql.SQLException sql ? "Oracle code=" + sql.getErrorCode() + " " + sql.getMessage() : cause.toString();
                throw new MetadataEditException(503, "History trigger update stopped at " + stage,
                        stage + UiMessages.text("ui.2636fd1123aa", "에서 중단했습니다. 앞서 교체한 이력 코드는 남아 있을 수 있으며 자동 활성화하지 않습니다. ") + detail);
            }
        });
    }
    private <T> T bound(PoolSession session, Target target, Supplier<T> work) {
        synchronized (session) {
            validateScope(session,target);
            source.bind(session.pool(), session.connectionSchema());
            try { read.executeWithoutResult(s -> metadata.requireTarget(target)); return work.get(); }
            finally { source.clear(); }
        }
    }
    private void validateScope(PoolSession session,Target target) {
        if (!session.metadata().selectedSchema().equals(target.schema())) throw new MetadataEditException(409, "Selected schema changed", UiMessages.text("ui.dde600c5989c", "스키마가 변경되었습니다. 상세 화면을 다시 열어 주세요."));
        if (target.table().startsWith("DBC_METADATA_") || target.table().startsWith("DBC_META_"))
            throw new MetadataEditException(400, "History assets cannot track themselves", UiMessages.text("ui.d522b6d8da50", "이력 관리용 테이블은 수집 대상으로 지정할 수 없습니다."));
    }
}
