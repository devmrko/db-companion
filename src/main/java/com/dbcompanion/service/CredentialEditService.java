package com.dbcompanion.service;

import com.dbcompanion.common.db.*;
import com.dbcompanion.common.i18n.UiMessages;
import com.dbcompanion.model.CredentialCatalog;
import com.dbcompanion.repository.CredentialCatalogRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Single-attribute rotation. Secrets exist only in the submitted request and bound call. */
@Service
public class CredentialEditService {
    public static final String SQL="BEGIN DBMS_CLOUD.UPDATE_CREDENTIAL(credential_name => ?, attribute => ?, value => ?); END;";
    public record Identity(long objectId,String usernameHash,String enabled) {}
    public record Plan(String token,String owner,String name,String attribute,boolean restore,Identity identity,Instant expires,String sql) {}
    private final SessionDataSource source;
    private final JdbcTemplate jdbc;
    private final CredentialCatalogRepository catalog;
    private final TransactionTemplate read,write;
    public CredentialEditService(SessionDataSource source,JdbcTemplate jdbc,CredentialCatalogRepository catalog){
        this.source=source;this.jdbc=new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource()));this.jdbc.setQueryTimeout(20);this.catalog=catalog;
        var manager=new DataSourceTransactionManager(source);read=new TransactionTemplate(manager);read.setReadOnly(true);read.setTimeout(20);write=new TransactionTemplate(manager);write.setTimeout(30);
    }
    public static CredentialCatalog.Failure error(int status,String key){return new CredentialCatalog.Failure(status,UiMessages.text("credentialEdit."+key,key));}
    public static String owner(PoolSession s){String owner=s.metadata().info().username();if(!owner.equals(s.metadata().selectedSchema()))throw error(403,"ownerOnly");return owner;}
    private <T>T tx(PoolSession s,boolean writing,Supplier<T> work){source.bind(s.pool(),owner(s));try{return (writing?write:read).execute(x->work.get());}finally{source.clear();}}
    protected Identity identity(String owner,String name){
        var list=catalog.list(owner,true);if(!list.status().equals("AVAILABLE"))throw error(403,"unavailable");
        var entry=list.items().stream().filter(e->e.name().equals(name)).findFirst().orElseThrow(()->error(404,"missing"));
        if(!entry.usernameFormat().equals("TEXT"))throw error(409,"unsupported");
        var ids=jdbc.queryForList("SELECT OBJECT_ID FROM USER_OBJECTS WHERE OBJECT_NAME=? AND OBJECT_TYPE='CREDENTIAL'",Long.class,name);
        if(ids.size()!=1)throw error(409,"unsupported");
        String hash=jdbc.queryForObject("SELECT RAWTOHEX(STANDARD_HASH(USERNAME,'SHA256')) FROM SYS.USER_CREDENTIALS WHERE CREDENTIAL_NAME=?",String.class,name);
        return new Identity(ids.getFirst(),hash,entry.enabled());
    }
    public Plan preview(PoolSession s,String name,String attribute,boolean restore){
        String login=owner(s);
        if(name==null||!name.matches("[A-Z][A-Z0-9_$#]{0,127}")||!Set.of("USERNAME","PASSWORD").contains(Objects.toString(attribute,"")))throw error(400,"invalid");
        return tx(s,false,()->new Plan(UUID.randomUUID().toString(),login,name,attribute,restore,identity(login,name),Instant.now().plusSeconds(300),"BEGIN DBMS_CLOUD.UPDATE_CREDENTIAL(credential_name => '"+name+"', attribute => '"+attribute+"', value => :value_not_logged); END;"));
    }
    public static void require(Plan plan,String token,boolean consent,String owner,Instant now){
        if(!consent)throw error(400,"consentRequired");
        if(plan==null||!Objects.equals(plan.token(),token)||!plan.owner().equals(owner)||!now.isBefore(plan.expires()))throw error(409,"stale");
    }
    public static void input(String attribute,String value){
        if(value==null||value.isEmpty()||value.indexOf('\0')>=0||value.getBytes(StandardCharsets.UTF_8).length>4000)throw error(400,"invalidValue");
        if(attribute.equals("USERNAME")&&(value.indexOf('"')>=0||value.codePoints().anyMatch(Character::isWhitespace)))throw error(400,"invalidValue");
    }
    protected void update(Plan plan,String value){
        jdbc.execute((ConnectionCallback<Void>)c->{try(var call=c.prepareCall(SQL)){call.setQueryTimeout(30);call.setString(1,plan.name());call.setString(2,plan.attribute());call.setString(3,value);call.execute();return null;}});
    }
    public Map<String,Object> apply(PoolSession s,Plan plan,String value){
        require(plan,plan==null?null:plan.token(),true,owner(s),Instant.now());
        input(plan.attribute(),value);
        return tx(s,true,()->{
            if(!plan.identity().equals(identity(owner(s),plan.name())))throw error(409,"stale");
            update(plan,value);
            // Passwords are deliberately never read back or tested against an external provider.
            var after=identity(owner(s),plan.name());
            if(after.objectId()!=plan.identity().objectId())throw error(409,"uncertain");
            return Map.of("name",plan.name(),"attribute",plan.attribute(),"restore",plan.restore(),"updated",true,"externalAuthenticationVerified",false);
        });
    }
}
