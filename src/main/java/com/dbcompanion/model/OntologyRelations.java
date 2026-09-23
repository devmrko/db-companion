package com.dbcompanion.model;

import com.dbcompanion.model.Ontology.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.*;

/** Reviewed metadata mappings, not database constraints or executable join expressions. */
public final class OntologyRelations {
    private OntologyRelations(){}
    public static final int MAX_COLUMNS=20_000,MAX_LINKS=2_000,MAX_JSON=20_000_000;
    public record Link(String id,String targetDocumentId,int sourceRevision,int targetRevision,
                       String targetSchema,String targetTable,List<String> sourceColumns,List<String> targetColumns,
                       String label,String condition,String status,String origin,List<String> evidence,String actor,String reviewedAt){
        public Link {sourceColumns=List.copyOf(sourceColumns);targetColumns=List.copyOf(targetColumns);evidence=List.copyOf(evidence);}
    }
    public record Field(String name,String type,String description,String label,List<String> aliases,boolean sensitive){
        public Field {aliases=List.copyOf(aliases);}
    }
    public record Table(String name,String documentId,int revision,String state,String concept,String description,List<Field> columns,List<Key> keys){
        public Table {columns=List.copyOf(columns);keys=List.copyOf(keys);}
    }
    public record Relation(String id,String source,String targetSchema,String target,List<String> sourceColumns,List<String> targetColumns,
                           String status,String origin,String label,String condition,List<String> evidence,Key key,Link review){
        public Relation {sourceColumns=List.copyOf(sourceColumns);targetColumns=List.copyOf(targetColumns);evidence=List.copyOf(evidence);}
    }
    public record Analysis(String schema,List<Table> tables,List<Relation> relations,String checkedAt){
        public Analysis {tables=List.copyOf(tables);relations=List.copyOf(relations);}
    }
    public record Review(String schema,String source,String sourceDocumentId,int sourceRevision,String target,String targetDocumentId,
                         int targetRevision,List<String> sourceColumns,List<String> targetColumns,String candidateId,String label,String condition,String status){}

    private static void text(String value,int max){if(value==null||value.length()>max||value.indexOf(0)>=0||value.codePoints().anyMatch(c->c>=0xD800&&c<=0xDFFF))throw new Failure(400,"invalid");}
    public static void validate(List<Link> links){
        if(links.size()>MAX_LINKS)throw new Failure(413,"relationships.limit");
        var ids=new HashSet<String>();
        for(var v:links){
            if(v==null||v.id()==null||!v.id().matches("[0-9a-f]{64}")||!ids.add(v.id()))throw new Failure(409,"mismatch");
            try{if(!UUID.fromString(v.targetDocumentId()).toString().equals(v.targetDocumentId()))throw new IllegalArgumentException();}catch(RuntimeException ex){throw new Failure(409,"mismatch");}
            if(v.sourceRevision()<1||v.targetRevision()<1||!Set.of("APPROVED","REJECTED").contains(v.status())||!Set.of("RULE","USER","AI").contains(v.origin()))throw new Failure(409,"mismatch");
            Ontology.name(v.targetSchema());Ontology.name(v.targetTable());pairs(v.sourceColumns(),v.targetColumns());
            text(v.label(),80);text(v.condition(),1000);text(v.actor(),128);text(v.reviewedAt(),80);
            if(v.evidence().size()>10)throw new Failure(409,"mismatch");v.evidence().forEach(e->text(e,160));
        }
    }
    private static void pairs(List<String> source,List<String> target){
        if(source==null||target==null||source.isEmpty()||source.size()>32||source.size()!=target.size()||new HashSet<>(source).size()!=source.size()||new HashSet<>(target).size()!=target.size())throw new Failure(400,"relationships.mapping");
        source.forEach(Ontology::name);target.forEach(Ontology::name);
    }
    public static String id(String source,String schema,String target,List<String> from,List<String> to){
        var parts=new ArrayList<String>();for(int i=0;i<from.size();i++)parts.add(piece(from.get(i))+piece(to.get(i)));Collections.sort(parts);
        String input=piece(source)+piece(schema)+piece(target)+String.join("",parts);
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}
    }
    private static String piece(String text){return text.length()+":"+text;}
    private static final tools.jackson.databind.json.JsonMapper HASH_JSON=new tools.jackson.databind.json.JsonMapper();
    private static Object canonical(tools.jackson.databind.JsonNode node){
        if(node.isObject()){var result=new TreeMap<String,Object>();node.properties().forEach(e->result.put(e.getKey(),canonical(e.getValue())));return result;}
        if(node.isArray()){var result=new ArrayList<Object>();node.forEach(v->result.add(canonical(v)));return result;}return node;
    }
    /** Review links do not change the table/column definition; retained as evidence, not a mutable revision. */
    public static String definitionHash(Document document){
        String value=HASH_JSON.writeValueAsString(canonical(HASH_JSON.valueToTree(Map.of("source",document.source(),"meaning",document.meaning()))));
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}
    }
    private static boolean matchesDefinition(Link link,String side,String hash,int revision,int reviewedRevision){
        String prefix=side+"_DEFINITION:";var values=link.evidence().stream().filter(e->e.startsWith(prefix)).toList();
        return values.isEmpty()?revision==reviewedRevision:values.size()==1&&values.getFirst().equals(prefix+hash);
    }
    public static String lexical(String text){return Normalizer.normalize(Objects.toString(text,"").strip(),Normalizer.Form.NFC).toLowerCase(Locale.ROOT).replaceAll("[\\s_\\-]+","");}
    private static boolean useful(String value){return value.length()>2&&value.length()<=120&&!Set.of("id","key","code","name","type","status","value","no","number","식별자","코드","이름","명칭","유형","상태","번호").contains(value);}
    public static String family(String type){
        String value=Objects.toString(type,"").toUpperCase(Locale.ROOT).replaceAll("\\(.*?\\)","").strip();
        return switch(value){case "NUMBER","FLOAT","INTEGER","DECIMAL","BINARY_FLOAT","BINARY_DOUBLE"->"NUMBER";
            case "CHAR","VARCHAR2","NCHAR","NVARCHAR2","VARCHAR"->"TEXT";
            case "DATE"->"DATE";case "TIMESTAMP"->"TIMESTAMP";case "RAW"->"RAW";default->"";};
    }
    public static boolean compatible(String a,String b){String family=family(a);return !family.isEmpty()&&family.equals(family(b));}
    private static Map<String,String> terms(Field c){
        var out=new LinkedHashMap<String,String>();String name=lexical(c.name());if(useful(name))out.put(name,"NAME");
        var labels=new ArrayList<>(c.aliases());labels.add(c.label());
        for(String v:labels){String term=lexical(v);if(useful(term))out.put(term,"TERM");}
        String definition=lexical(c.description());if(useful(definition))out.putIfAbsent(definition,"DEFINITION");return out;
    }
    public static Table table(Entry entry){
        var d=entry.document();return new Table(d.source().table(),entry.documentId(),entry.revision(),entry.state(),d.meaning().concept(),d.meaning().description(),
            d.source().columns().stream().map(c->{var m=d.meaning().columns().get(c.name());var def=m.definition();return new Field(c.name(),c.dataType(),m.description(),def==null?"":def.label(),def==null?List.of():def.aliases(),"sensitive".equals(OntologyWizard.blocked(c,m)));}).toList(),d.source().keys());
    }
    private static boolean targetKey(Key k){return Set.of("P","U").contains(k.type())&&"ENABLED".equals(k.status())&&"VALIDATED".equals(k.validated())&&!k.columns().isEmpty()&&k.columns().size()<=32;}
    private static Map<String,Field> fields(Table t){var out=new LinkedHashMap<String,Field>();t.columns().forEach(c->out.put(c.name(),c));return out;}
    public static Analysis analyze(String database,String schema,List<Entry> entries,String checkedAt){
        if(entries.size()>Ontology.GRAPH_TABLE_LIMIT)throw new Failure(413,"relationships.limit");
        var tables=new LinkedHashMap<String,Table>();var saved=new HashMap<String,Entry>();int count=0,fks=0;
        for(var e:entries){var s=e.document().source();if(!database.equals(s.database())||!schema.equals(s.schema())||saved.put(s.table(),e)!=null)throw new Failure(409,"mismatch");
            var t=table(e);tables.put(t.name(),t);count+=t.columns().size();fks+=t.keys().stream().filter(k->"R".equals(k.type())).count();}
        if(count>MAX_COLUMNS||fks>Ontology.GRAPH_EDGE_LIMIT)throw new Failure(413,"relationships.limit");
        var relations=new ArrayList<Relation>();var occupied=new HashSet<String>();var definitions=new HashMap<String,String>();saved.forEach((name,e)->definitions.put(name,definitionHash(e.document())));
        for(var source:tables.values())for(var k:source.keys())if("R".equals(k.type())){
            String owner=Objects.toString(k.targetOwner(),""),target=Objects.toString(k.targetTable(),"");
            if(k.columns().size()==k.targetColumns().size())occupied.add(id(source.name(),owner,target,k.columns(),k.targetColumns()));
            relations.add(new Relation("fk:"+id(source.name(),schema,k.name(),List.of(),List.of()),source.name(),owner,target,k.columns(),k.targetColumns(),"FK","FK",saved.get(source.name()).document().meaning().relations().getOrDefault(k.name(),""),"",List.of("DATABASE_CONSTRAINT"),k,null));
        }
        for(var source:tables.values())for(var link:saved.get(source.name()).document().links()){
            String key=id(source.name(),link.targetSchema(),link.targetTable(),link.sourceColumns(),link.targetColumns());
            if(!key.equals(link.id()))throw new Failure(409,"mismatch");
            occupied.add(key);var target=tables.get(link.targetTable());
            boolean stale=!schema.equals(link.targetSchema())||target==null||!target.documentId().equals(link.targetDocumentId())
                ||!matchesDefinition(link,"TARGET",definitions.get(link.targetTable()),target.revision(),link.targetRevision())||!matchesDefinition(link,"SOURCE",definitions.get(source.name()),source.revision(),link.sourceRevision());
            relations.add(new Relation(key,source.name(),link.targetSchema(),link.targetTable(),link.sourceColumns(),link.targetColumns(),stale?"STALE":link.status(),link.origin(),link.label(),link.condition(),link.evidence(),null,link));
        }
        // Indexed lexical evidence, not an all-column Cartesian comparison.
        record Located(Table table,Field column){}
        var index=new HashMap<String,List<Located>>();
        for(var t:tables.values())for(var c:t.columns())if(!c.sensitive())for(String term:terms(c).keySet())index.computeIfAbsent(term,k->new ArrayList<>()).add(new Located(t,c));
        for(var target:tables.values())for(var key:target.keys())if(targetKey(key)){
            var targetFields=fields(target);var matches=new LinkedHashMap<String,List<List<Field>>>();
            for(int position=0;position<key.columns().size();position++){
                var to=targetFields.get(key.columns().get(position));if(to==null||to.sensitive())continue;
                var seen=new HashSet<String>();
                for(String term:terms(to).keySet())for(var located:index.getOrDefault(term,List.of())){
                    var from=located.column();String source=located.table().name();if(source.equals(target.name())||!compatible(from.type(),to.type())||!seen.add(piece(source)+piece(from.name())))continue;
                    var slots=matches.computeIfAbsent(source,n->{var slotsNew=new ArrayList<List<Field>>();for(int p=0;p<key.columns().size();p++)slotsNew.add(new ArrayList<>());return slotsNew;});slots.get(position).add(from);
                }
            }
            for(var match:matches.entrySet()){
                if(match.getValue().stream().anyMatch(slot->slot.size()!=1))continue;
                var from=match.getValue().stream().map(slot->slot.getFirst().name()).toList();if(new HashSet<>(from).size()!=from.size())continue;
                String id=id(match.getKey(),schema,target.name(),from,key.columns());if(!occupied.add(id))continue;
                var evidence=new LinkedHashSet<String>();evidence.add("COMPATIBLE_TYPE");evidence.add("TARGET_KEY");
                for(int i=0;i<from.size();i++){var a=terms(match.getValue().get(i).getFirst());var b=terms(targetFields.get(key.columns().get(i)));for(String term:a.keySet())if(b.containsKey(term))evidence.add("NAME".equals(a.get(term))&&"NAME".equals(b.get(term))?"NAME":("DEFINITION".equals(a.get(term))||"DEFINITION".equals(b.get(term)))?"DEFINITION":"TERM");}
                relations.add(new Relation(id,match.getKey(),schema,target.name(),from,key.columns(),"CANDIDATE","RULE","","",List.copyOf(evidence),null,null));
                if(relations.size()-fks>MAX_LINKS)throw new Failure(413,"relationships.limit");
            }
        }
        if(relations.size()-fks>MAX_LINKS)throw new Failure(413,"relationships.limit");
        return new Analysis(schema,List.copyOf(tables.values()),relations,checkedAt);
    }
    public static Link review(Review input,Entry source,Entry target,Analysis analysis,String actor,String now){
        if(input==null||source==null||target==null)throw new Failure(409,"stale");
        if(!source.documentId().equals(input.sourceDocumentId())||source.revision()!=input.sourceRevision()||!target.documentId().equals(input.targetDocumentId())||target.revision()!=input.targetRevision()
            ||!source.document().source().schema().equals(input.schema())||!target.document().source().schema().equals(input.schema())||!source.document().source().table().equals(input.source())||!target.document().source().table().equals(input.target()))throw new Failure(409,"stale");
        pairs(input.sourceColumns(),input.targetColumns());text(input.label(),80);text(input.condition(),1000);
        if(input.status()==null||!Set.of("APPROVED","REJECTED").contains(input.status())||input.status().equals("APPROVED")&&input.label().isBlank()||input.label().contains("\n")||input.label().contains("\r"))throw new Failure(400,"invalid");
        var from=fields(table(source));var to=fields(table(target));
        for(int i=0;i<input.sourceColumns().size();i++){var a=from.get(input.sourceColumns().get(i));var b=to.get(input.targetColumns().get(i));if(a==null||b==null||!compatible(a.type(),b.type()))throw new Failure(400,"relationships.mapping");}
        String id=id(input.source(),input.schema(),input.target(),input.sourceColumns(),input.targetColumns());
        var existing=analysis.relations().stream().filter(r->r.source().equals(input.source())&&r.targetSchema().equals(input.schema())&&r.target().equals(input.target())&&r.sourceColumns().size()==r.targetColumns().size()&&id.equals(id(r.source(),r.targetSchema(),r.target(),r.sourceColumns(),r.targetColumns()))).toList();
        if(existing.stream().anyMatch(r->r.origin().equals("FK")))throw new Failure(409,"relationships.hasFk");
        String origin="USER";List<String> evidence=List.of("USER_MAPPING");
        if(input.candidateId()!=null&&!input.candidateId().isEmpty()){
            var candidate=existing.stream().filter(r->r.id().equals(input.candidateId())).findFirst().orElseThrow(()->new Failure(409,"stale"));
            origin=candidate.origin();evidence=candidate.evidence();
        }
        var proof=new ArrayList<>(evidence.stream().filter(e->!e.startsWith("SOURCE_DEFINITION:")&&!e.startsWith("TARGET_DEFINITION:")).toList());
        proof.add("SOURCE_DEFINITION:"+definitionHash(source.document()));proof.add("TARGET_DEFINITION:"+definitionHash(target.document()));
        return new Link(id,target.documentId(),source.revision()+1,input.source().equals(input.target())?source.revision()+1:target.revision(),input.schema(),input.target(),input.sourceColumns(),input.targetColumns(),input.label().strip(),input.condition().strip(),input.status(),origin,proof,actor,now);
    }
    public static Document merge(Entry source,Link link){
        var d=source.document();var links=new ArrayList<>(d.links());links.removeIf(v->v.id().equals(link.id()));links.add(link);validate(links);
        return new Document(d.format(),d.source(),d.meaning(),d.origin(),d.profile(),d.analysis(),links);
    }
}
