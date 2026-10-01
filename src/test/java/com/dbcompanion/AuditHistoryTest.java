package com.dbcompanion;

import com.dbcompanion.common.db.AuditHistorySql;
import com.dbcompanion.model.AuditHistory.*;
import com.dbcompanion.repository.AuditHistoryRepository;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AuditHistoryTest {
    Status state(String store,String pkg,String job,boolean install){
        return new Status("APP","DB","AUDSYS.UNIFIED_AUDIT_TRAIL","READY",store,pkg,job,false,install,List.of(),Map.of(),Map.of(),"");
    }
    Query query(Source source,String user){return new Query(source,LocalDate.of(2026,1,1),LocalDate.of(2026,1,2),"dds","",user,"APP","EVENTS","failure","AABB",1);}
    @Test void dateFiltersAndDdsScopeAreBoundAndArchiveCannotExpandToOtherUsers(){
        var q=AuditHistoryRepository.listSql(query(Source.original,"' OR 1=1 --"),"AUDSYS.UNIFIED_AUDIT_TRAIL");
        assertThat(q.sql()).doesNotContain("' OR 1=1 --").contains("END_USER_NAME IS NOT NULL","RETURN_CODE<>0","RAWTOHEX(END_USER_SECURITY_CONTEXT_ID)=?","OFFSET ? ROWS FETCH NEXT 21");
        assertThat(q.args()).contains("' OR 1=1 --","2026-01-03","AABB");
        assertThat(q.sql()).contains("TO_CHAR(a.SESSIONID) SESSIONID","TO_CHAR(a.SCN) SCN");
        assertThat(AuditHistoryRepository.listSql(query(Source.archive,"user"),"").sql()).contains("DBC_AUDIT_ARCHIVE","END_USER_NAME IS NOT NULL").doesNotContain("SYS.DBMS_CRYPTO");
        assertThatThrownBy(()->AuditHistoryRepository.listSql(query(Source.original,""),"EVIL.UNIFIED_AUDIT_TRAIL")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void installationIsScopedDisabledFirstAndNeverChangesSourceAuditOrGrants(){
        var ddl=AuditHistorySql.plan(state("MISSING","MISSING","MISSING",true),Operation.INSTALL,new Settings(60,0));
        assertThat(String.join("\n",ddl)).contains("CREATE TABLE DBC_AUDIT_ARCHIVE","CREATE PACKAGE DBC_AUDIT_ARCHIVER AUTHID CURRENT_USER","enabled=>FALSE","DBMS_SCHEDULER.ENABLE","RETENTION_DAYS) VALUES (1,'DBC_AUDIT_ARCHIVE_V1',60,0)");
        assertThat(String.join("\n",ddl)).doesNotContain("CREATE OR REPLACE","CLEAN_AUDIT_TRAIL","GRANT ","AUDIT POLICY","DROP ");
        assertThatThrownBy(()->AuditHistorySql.plan(state("CONFLICT","MISSING","MISSING",false),Operation.INSTALL,new Settings(60,0))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void collectorKeepsClobsAndIdentitiesAndCommitsCheckpointOnlyOnSuccess(){
        String sql=AuditHistorySql.body("AUDSYS.UNIFIED_AUDIT_TRAIL");
        assertThat(sql).contains("RETURNING CLOB","FOR UPDATE NOWAIT","INTERVAL '1' DAY","INTERVAL '14' DAY","IF v_days>0 THEN","DELETE FROM DBC_AUDIT_ARCHIVE WHERE","LAST_SCAN_TO=v_to","ROLLBACK; RAISE");
        assertThat(sql).doesNotContain("DBMS_LOB.SUBSTR","DELETE FROM AUDSYS","DELETE FROM UNIFIED");
        String key=AuditHistorySql.key();for(String name:List.of("ENTRY_ID","OBJECT_NAME","RLS_INFO","SQL_TEXT","END_USER_NAME","SECURITY_CONTEXT_ID","STATEMENT_ID","UNIFIED_AUDIT_POLICIES"))assertThat(key).contains(name);
    }
    @Test void settingsAndQueriesAreBoundedAndRunUsesSameSchedulerJob(){
        assertThatThrownBy(()->new Settings(0,0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new Settings(60,-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new Query(Source.original,LocalDate.now(),LocalDate.now().plusDays(366),"dds","","","","","all","",1)).isInstanceOf(IllegalArgumentException.class);
        assertThat(AuditHistorySql.plan(state("READY","READY","READY",false),Operation.RUN,new Settings(60,0))).hasSize(1).first().asString().contains("RUN_JOB","use_current_session=>FALSE").doesNotContain(".COLLECT");
    }
    @Test void helpSeparatesStatementsForSqlClientsAndIncludesBothSources(){
        String sql=AuditHistorySql.help("AUDSYS.UNIFIED_AUDIT_TRAIL");
        assertThat(sql).contains("FROM AUDSYS.UNIFIED_AUDIT_TRAIL","FROM DBC_AUDIT_ARCHIVE ORDER BY","END;\n/","USER_SCHEDULER_JOB_RUN_DETAILS");
        assertThat(AuditHistorySql.script(List.of("SELECT 1 FROM DUAL","BEGIN NULL; END;"))).isEqualTo("SELECT 1 FROM DUAL;\n\nBEGIN NULL; END;\n/");
    }
}
