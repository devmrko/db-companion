package com.dbcompanion.service;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.common.db.SessionDataSource;
import com.dbcompanion.model.ProfilePreflight.*;
import com.dbcompanion.repository.CredentialCatalogRepository;
import com.dbcompanion.repository.DatabaseRepository;
import com.dbcompanion.repository.AiFeedbackRepository;
import com.dbcompanion.model.AiCreation;
import com.dbcompanion.model.AiFeedback;
import com.dbcompanion.model.SelectAiTest.Action;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Service
public class ProfilePreflightService {
    private final SessionDataSource source; private final DatabaseRepository profiles;
    private final CredentialCatalogRepository credentials; private final AiFeedbackRepository feedback; private final JsonMapper json; private final TransactionTemplate read;
    public ProfilePreflightService(SessionDataSource source, DatabaseRepository profiles, CredentialCatalogRepository credentials, AiFeedbackRepository feedback, JsonMapper json) {
        this.source=source; this.profiles=profiles; this.credentials=credentials; this.feedback=feedback; this.json=json;
        read=new TransactionTemplate(new DataSourceTransactionManager(source)); read.setReadOnly(true); read.setTimeout(10);
    }
    public Result check(PoolSession session, String schema, String profile) {
        synchronized(session) {
            if (!schema.equals(session.metadata().selectedSchema())) throw new IllegalArgumentException("Selected schema changed");
            boolean own=schema.equals(session.metadata().info().username());
            if (!own && !"ADMIN".equals(session.metadata().info().username())) throw new IllegalArgumentException("Profile scope unavailable");
            source.bind(session.pool(),session.metadata().info().username());
            try { return read.execute(status -> inspect(schema, profile, own)); } finally { source.clear(); }
        }
    }
    Result inspect(String schema,String name,boolean own) {
        var row=profiles.profiles(schema,own,name);
        if(row.size()!=1) throw new IllegalArgumentException("Profile unavailable");
        var attributes=profiles.profileAttributes(schema,own,name); var checks=new ArrayList<Check>();
        var values=new java.util.HashMap<String,String>(); attributes.forEach(a->values.put(a.name(),a.value()));
        String fingerprint=AiCreation.fingerprint(attributes,json);
        checks.add(options(values)); checks.add(objects(values.get("object_list")));
        checks.add(credential(schema,own,values.get("credential_name")));
        checks.add(feedback(schema,name,values.get("object_list")));
        boolean stale=!row.equals(profiles.profiles(schema,own,name))
                ||!attributes.equals(profiles.profileAttributes(schema,own,name));
        return new Result(schema,name,row.getFirst().modified(),fingerprint,stale,Instant.now(),checks);
    }
    private Check options(java.util.Map<String,String> values) {
        var explicit=values.keySet().stream().filter(k->List.of("enforce_object_list","comments","annotations","conversation","model","provider").contains(k)).sorted().toList();
        if(explicit.isEmpty()) return new Check("options",Tone.YELLOW,"No relevant explicit options","Database defaults are not inferred as healthy or unhealthy.","Read profile attributes only.");
        return new Check("options",Tone.GREEN,"Explicit options: "+String.join(", ",explicit),"Boolean values are configuration, not a health verdict.","Read profile attributes only.");
    }
    private Check objects(String raw) {
        if(raw==null) return new Check("objects",Tone.YELLOW,"object_list is not explicitly set","Its default and effective enforcement are not assumed.","Read profile attributes only.");
        try {
            var tree=json.readTree(raw); if(!tree.isArray()) return new Check("objects",Tone.RED,"object_list is not an array","The stored value is not the expected JSON array.","Parsed stored JSON; no mutation.");
            var names=new ArrayList<String>(); for(var item:tree) { if(!item.path("owner").isTextual()) return new Check("objects",Tone.RED,"object_list entry has no owner","An entry cannot be checked without an owner.","Parsed stored JSON; no mutation."); if(item.path("name").isTextual()) names.add(item.path("owner").asString()+"."+item.path("name").asString()); }
            return names.isEmpty() ? new Check("objects",Tone.YELLOW,"No named objects to check","Schema-wide entries may be intentional; they are not expanded.","Parsed stored JSON only.")
                    : objectVisibility(names);
        } catch(RuntimeException ex) { return new Check("objects",Tone.RED,"object_list JSON cannot be parsed","The stored explicit value is malformed.","Parsed stored JSON; no mutation."); }
    }
    private Check objectVisibility(List<String> names) {
        int visible=0,unknown=0;
        try { for(String item:names.stream().limit(20).toList()) { int dot=item.indexOf('.'); if(profiles.objectMetadata(item.substring(0,dot),item.substring(dot+1))!=null)visible++;else unknown++; }
        } catch(RuntimeException ex) { return new Check("objects",Tone.GREY,"Object metadata access is unknown","A dictionary error does not prove an object is absent.","Read SYS.ALL_OBJECTS metadata only; no mutation."); }
        String bounded=names.size()>20?" Partial: checked 20 of "+names.size()+" declared objects.":" Checked "+names.size()+" of "+names.size()+".";
        if(names.size()>20&&unknown==0)return new Check("objects",Tone.YELLOW,visible+" declared object(s) visible (PARTIAL)",bounded+" Metadata visibility is not SELECT authorization.","Read SYS.ALL_OBJECTS metadata only; no mutation.");
        return unknown==0 ? new Check("objects",Tone.GREEN,visible+" declared object(s) visible",bounded+" Metadata visibility is not SELECT authorization.","Read SYS.ALL_OBJECTS metadata only; no mutation.")
                : new Check("objects",Tone.YELLOW,visible+" visible; "+unknown+" not visible", "A missing dictionary row can mean absence or insufficient privilege."+bounded,"Read SYS.ALL_OBJECTS metadata only; no mutation.");
    }
    private Check feedback(String schema,String profile,String objectList) {
        try {
            if(!feedback.tableExists(schema,profile)) return new Check("feedback",Tone.YELLOW,"Feedback table candidate is not visible","It can be absent or unavailable to this session; this is not treated as absence.","Read SYS.ALL_TABLES metadata only; no mutation.");
            var allowed=allowed(objectList); if(allowed==null)return new Check("feedback",Tone.GREY,"Feedback object-list comparison is unknown","Malformed or unset object_list is not used to infer an outside reference.","Bounded read-only Feedback lookup; no mutation.");
            var page=feedback.page(new AiFeedback.Query(schema,profile,"","",1)); int refs=0,rows=0;var outside=new ArrayList<String>();boolean unknown=false,partial=page.hasNext()||page.items().size()>10;
            outer:for(var item:page.items()) { if(rows>=10){partial=true;break;}rows++;var detail=feedback.detail(new AiFeedback.Query(schema,profile,"","",1),item.id()); if(detail==null){unknown=true;continue;}var analysis=SelectAiSqlReferences.analyze(Action.SQL,detail.sqlText());if(!"PARSED".equals(analysis.status())){unknown=true;continue;}for(var table:analysis.tables()){if(refs>=100){partial=true;break outer;}refs++;if(table.owner()==null){if(!"DUAL".equalsIgnoreCase(table.name()))unknown=true;continue;}String key=table.owner()+"."+table.name();if(!allowed.contains(key)&&!allowed.contains(table.owner()+".*"))outside.add(item.id()+" "+key);} }
            String summary="Feedback candidate visible; checked "+rows+"/10 row(s), "+refs+"/100 reference(s)"+(partial?" (PARTIAL)":"")+(unknown?" (UNKNOWN references)":"")+(outside.isEmpty()?"":"; outside-object-list candidate(s): "+String.join(", ",outside));
            Tone tone=!outside.isEmpty()||partial?Tone.YELLOW:unknown?Tone.GREY:Tone.GREEN;
            return new Check("feedback",tone,summary,"Only bounded parsed references are display-only candidates; DUAL is a special built-in reference. UNKNOWN includes unqualified, unsupported, synonym, or parse cases and does not prove prompt use or an enforce error cause.","Read Feedback metadata and at most 10 rows / 100 references; no mutation.");
        } catch(RuntimeException ex) { return new Check("feedback",Tone.GREY,"Feedback visibility is unknown","A query or privilege error does not prove the table is absent.","Bounded read-only Feedback lookup; no mutation."); }
    }
    private java.util.Set<String> allowed(String raw){if(raw==null)return null;try{var tree=json.readTree(raw);if(!tree.isArray())return null;var out=new java.util.HashSet<String>();for(var n:tree){if(!n.path("owner").isTextual())return null;String owner=n.path("owner").asString();out.add(owner+"."+(n.path("name").isTextual()?n.path("name").asString():"*"));}return out;}catch(RuntimeException ex){return null;}}
    private Check credential(String schema,boolean own,String name) {
        if(name==null||name.isBlank()) return new Check("credential",Tone.YELLOW,"No credential_name is explicitly set","A provider may require a credential; no default is assumed.","Read profile attributes only.");
        var catalog=credentials.list(schema,own);
        if(!"AVAILABLE".equals(catalog.status())) return new Check("credential",Tone.GREY,"Credential visibility is unknown","Catalog access is unavailable; this does not prove the credential is absent.","Read credential catalog metadata only.");
        boolean found=catalog.items().stream().anyMatch(item->name.equals(item.name()));
        return found ? new Check("credential",Tone.GREEN,"Credential is visible: "+name,"Visibility does not prove provider connection success.","Read credential catalog metadata only.")
                : new Check("credential",Tone.YELLOW,"Credential is not visible in this catalog","It can be unavailable, disabled, or outside the visible scope.","Read credential catalog metadata only.");
    }
}
