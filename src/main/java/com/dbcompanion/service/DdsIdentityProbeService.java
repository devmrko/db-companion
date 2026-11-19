package com.dbcompanion.service;

import com.dbcompanion.common.db.OraclePoolFactory;
import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.model.DdsManagement;
import com.dbcompanion.repository.CredentialCatalogRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** A separate, directly authenticated local end-user connection; never borrows ADMIN privileges. */
@Service
public class DdsIdentityProbeService {
    public record Request(String username,String password,String table,boolean consent) {
        @Override public String toString(){return "DdsIdentityProbe.Request[redacted]";}
    }
    private final DdsManagementService management;
    private final OraclePoolFactory pools;
    public DdsIdentityProbeService(DdsManagementService management,OraclePoolFactory pools){this.management=management;this.pools=pools;}
    public Map<String,Object> probe(PoolSession session,Request request){synchronized(session){
        DdsManagement.require(request!=null&&request.consent(),"confirm");
        var identities=management.identities(session);
        DdsManagement.id(request.username());DdsManagement.id(request.table());
        DdsManagement.require(request.password()!=null&&!request.password().isEmpty()&&request.password().length()<=128,"passwordInvalid");
        DdsManagement.require(identities.users().stream().anyMatch(r->request.username().equals(r.get("USERNAME"))
                &&"PASSWORD".equals(r.get("AUTHENTICATION_TYPE"))),"localPasswordUser");
        var state=management.state(session);String owner=session.metadata().info().username();
        DdsManagement.require(state.columns().stream().anyMatch(r->request.table().equals(r.get("TABLE_NAME"))),"columnType");
        var result=new LinkedHashMap<String,Object>();result.put("mode","LOCAL_DIRECT_LOGON");result.put("requestedUser",request.username());
        try(var temporary=pools.open(request.username(),request.password(),session.alias(),session.walletId());
            var connection=temporary.pool().getConnection()){
            connection.setAutoCommit(false);
            try {
                try(var statement=connection.createStatement()){
                    statement.setQueryTimeout(15);statement.execute("SET TRANSACTION READ ONLY");
                    try(var rows=statement.executeQuery("SELECT JSON_VALUE(ORA_END_USER_CONTEXT.username,'$' RETURNING VARCHAR2(128) ERROR ON ERROR) FROM SYS.DUAL")){
                        if(!rows.next())throw new DdsManagement.Failure("contextMissing");String user=rows.getString(1);
                        DdsManagement.require(request.username().equals(user),"contextMissing");result.put("endUser",user);
                    }
                    try(var roles=statement.executeQuery("SELECT ROLE_NAME FROM V$END_USER_DATA_ROLE ORDER BY ROLE_NAME")){
                        var values=new java.util.ArrayList<String>();while(roles.next()&&values.size()<500)values.add(roles.getString(1));
                        result.put("activeRoles",values);result.put("rolesStatus","VERIFIED");
                    }catch(java.sql.SQLException ex){result.put("activeRoles",List.of());result.put("rolesStatus","UNVERIFIED "+CredentialCatalogRepository.error(ex));}
                    try(var rows=statement.executeQuery("SELECT COUNT(*) FROM "+DdsManagement.id(owner)+"."+DdsManagement.id(request.table()))){
                        if(!rows.next())throw new DdsManagement.Failure("verifyFailed");result.put("visibleRows",rows.getLong(1));result.put("queryStatus","READ_COMPLETED");
                    }catch(java.sql.SQLException ex){result.put("queryStatus","REJECTED_OR_FAILED");result.put("queryCode",CredentialCatalogRepository.error(ex));}
                }
            } finally {connection.rollback();}
        }catch(java.sql.SQLException ex){throw new IllegalStateException("DDS local probe failed",ex);}
        return result;
    }}
}
