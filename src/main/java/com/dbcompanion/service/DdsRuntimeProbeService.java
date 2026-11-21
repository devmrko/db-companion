package com.dbcompanion.service;

import com.dbcompanion.common.db.OraclePoolFactory;
import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.model.DdsManagement;
import com.dbcompanion.repository.CredentialCatalogRepository;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import oracle.jdbc.EndUserSecurityContext;
import oracle.jdbc.OracleConnection;
import org.springframework.stereotype.Service;

/** Administrator-run A/B/A experiment. Credentials and contexts live only for this request. */
@Service
public class DdsRuntimeProbeService {
    public record Principal(String name,String token) {
        @Override public String toString(){return "Principal[redacted]";}
    }
    public record Request(String poolUser,String poolPassword,String databaseToken,String mode,
                          Principal a,Principal b,String table,String memoryStore,String recordA,String recordB,boolean consent) {
        @Override public String toString(){return "DdsRuntimeProbe.Request[redacted]";}
    }
    private final DdsManagementService management;
    private final OraclePoolFactory pools;
    public DdsRuntimeProbeService(DdsManagementService management,OraclePoolFactory pools){this.management=management;this.pools=pools;}
    public static void validate(Request r){
        DdsManagement.require(r!=null&&r.consent(),"confirm");
        DdsManagement.id(r.poolUser());
        DdsManagement.require(!Set.of("ADMIN","SYS","SYSTEM").contains(r.poolUser()),"runtimePoolUser");
        DdsManagement.require(r.poolPassword()!=null&&!r.poolPassword().isEmpty()&&r.poolPassword().length()<=128,"passwordInvalid");
        secret(r.databaseToken());DdsManagement.require(Set.of("NAME","TOKEN").contains(r.mode()),"invalid");
        for(var p:List.of(r.a(),r.b())){
            DdsManagement.require(p.name()!=null&&!p.name().isBlank()&&p.name().length()<=256&&p.name().codePoints().noneMatch(Character::isISOControl),"invalid");
            if(r.mode().equals("TOKEN"))secret(p.token());else DdsManagement.id(p.name());
        }
        DdsManagement.require(!r.a().name().equals(r.b().name()),"runtimeDifferentUsers");
        if(r.memoryStore()!=null&&!r.memoryStore().isBlank()){
            DdsManagement.id(r.memoryStore());
            for(String id:List.of(r.recordA(),r.recordB()))DdsManagement.require(id!=null&&!id.isBlank()&&id.length()<=256,"invalid");
            DdsManagement.require(!r.recordA().equals(r.recordB()),"invalid");
        }else DdsManagement.id(r.table());
    }
    private static void secret(String token){DdsManagement.require(token!=null&&!token.isBlank()&&token.length()<=32768,"runtimeToken");}
    public Map<String,Object> probe(PoolSession session,Request request){synchronized(session){
        validate(request);var identities=management.identities(session); // Enforces ADMIN + own schema.
        var readiness=management.runtimeReadiness(session);
        DdsManagement.require(!"OCI_IAM".equals(readiness.get("provider"))
                ||(!"MISSING".equals(readiness.get("oauthConfig"))&&!"MISSING".equals(readiness.get("signingCredential"))),"runtimeOAuthMissing");
        if(request.mode().equals("NAME"))for(var p:List.of(request.a(),request.b()))
            DdsManagement.require(identities.users().stream().anyMatch(u->p.name().equals(u.get("USERNAME"))),"missing");
        String table=request.memoryStore()!=null&&!request.memoryStore().isBlank()
                ?management.memoryProbeTable(session,request.memoryStore()):request.table();
        var state=management.state(session);
        DdsManagement.require(state.columns().stream().anyMatch(c->table.equals(c.get("TABLE_NAME"))),"columnType");
        String qualified=DdsManagement.id(session.metadata().info().username())+"."+DdsManagement.id(table);
        try(var pool=pools.open(request.poolUser(),request.poolPassword(),session.alias(),session.walletId());
            var connection=pool.pool().getConnection()){
            return observe(connection,request,qualified);
        }catch(SQLException ex){throw new IllegalStateException("DDS runtime probe failed",ex);}
    }}
    public static Map<String,Object> observe(Connection connection,Request r,String qualified)throws SQLException {
        var oracle=connection.unwrap(OracleConnection.class);
        connection.setAutoCommit(false);
        oracle.clearEndUserSecurityContext();
        String baseline=identity(connection);connection.rollback();
        var steps=new ArrayList<Map<String,Object>>();boolean restored=true;
        for(var p:List.of(r.a(),r.b(),r.a())){
            var step=new LinkedHashMap<String,Object>();step.put("requestedUser",p.name());
            try {
                var context=r.mode().equals("TOKEN")?EndUserSecurityContext.createWithToken(r.databaseToken(),p.token()):EndUserSecurityContext.createWithName(r.databaseToken(),p.name());
                oracle.setEndUserSecurityContext(context);
                try(var st=connection.createStatement()){st.setQueryTimeout(15);st.execute("SET TRANSACTION READ ONLY");}
                String actual=identity(connection);step.put("endUser",Objects.toString(actual,""));
                if(!p.name().equals(actual)){step.put("status","IDENTITY_MISMATCH");}
                else {
                    step.put("visibleRows",count(connection,qualified,null));
                    if(r.memoryStore()!=null&&!r.memoryStore().isBlank()){
                        step.put("recordAVisible",count(connection,qualified,r.recordA()));
                        step.put("recordBVisible",count(connection,qualified,r.recordB()));
                    }
                    step.put("status","READ_COMPLETED");
                }
            }catch(SQLException|IllegalArgumentException ex){
                step.put("status","REJECTED_OR_FAILED");step.put("code",CredentialCatalogRepository.error(ex));
            }finally{
                // Roll back under the attached identity before detaching. A cleanup error stops reuse.
                try{connection.rollback();}catch(SQLException ex){
                    step.put("status","REJECTED_OR_FAILED");step.put("rollbackCode",CredentialCatalogRepository.error(ex));
                }finally{oracle.clearEndUserSecurityContext();}
            }
            String cleared=identity(connection);connection.rollback();
            boolean clean=Objects.equals(baseline,cleared);step.put("contextCleared",clean);steps.add(step);
            if(!clean){restored=false;break;}
            if(!"READ_COMPLETED".equals(step.get("status")))break;
        }
        return Map.of("mode",r.mode(),"sequence","A/B/A","sameConnection",true,"steps",steps,
                "contextRestored",restored,"memoryScope",r.memoryStore()==null||r.memoryStore().isBlank()?"NOT_REQUESTED":"RECORD_ACCESS_ONLY");
    }
    private static String identity(Connection c)throws SQLException{
        try(var st=c.createStatement()){
            st.setQueryTimeout(15);
            try(var rs=st.executeQuery("SELECT JSON_VALUE(ORA_END_USER_CONTEXT.username,'$' RETURNING VARCHAR2(256) ERROR ON ERROR) FROM SYS.DUAL")){
                if(!rs.next())throw new SQLException("Missing identity");return rs.getString(1);
            }
        }
    }
    private static long count(Connection c,String table,String record)throws SQLException{
        try(var st=c.prepareStatement("SELECT COUNT(*) FROM "+table+(record==null?"":" WHERE RECORD_ID=?"))){
            st.setQueryTimeout(15);if(record!=null)st.setString(1,record);
            try(var rs=st.executeQuery()){if(!rs.next())throw new SQLException("Missing count");return rs.getLong(1);}
        }
    }
}
