package com.dbcompanion.model;

import com.dbcompanion.model.Ontology.*;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/** User-declared business codes, not discovered facts or executable SQL. */
public final class OntologyValues {
    private OntologyValues(){}
    public static final int LIMIT=100,ROWS=20;
    public record Binding(String id,String column,String type,String value,String label,List<String> aliases,String description){
        public Binding {aliases=aliases==null?List.of():List.copyOf(aliases);}
    }
    public record Lookup(String schema,String table,int revision,String codeColumn,String labelColumn,boolean confirmed){}
    public record Candidate(String value,String label){}
    public record Preview(String type,List<Candidate> items,int scanned,int skipped,String checkedAt){public Preview{items=List.copyOf(items);}}
    public static String type(String type){
        return switch(type.replaceAll("\\(.*?\\)","").strip().toUpperCase(Locale.ROOT)){
            case "CHAR","NCHAR","VARCHAR2","NVARCHAR2" -> "TEXT";
            case "NUMBER","FLOAT" -> "NUMBER";
            default -> "";
        };
    }
    private static String text(String value,int max,boolean required){
        if(value==null||value.length()>max||required&&value.isBlank()||value.codePoints().anyMatch(c->Character.isISOControl(c)||c>=0xD800&&c<=0xDFFF)||OntologyWizard.privateValue(value))throw new Failure(400,"values.invalid");
        return value;
    }
    public static String canonical(String type,String value){
        text(value,200,true);
        if("TEXT".equals(type))return value;
        if(!"NUMBER".equals(type)||!value.matches("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]{1,3})?"))throw new Failure(400,"values.invalid");
        try{var number=new BigDecimal(value);if(number.precision()>38||number.scale()<-125||number.scale()>130)throw new NumberFormatException();return number.stripTrailingZeros().toPlainString();}
        catch(NumberFormatException ex){throw new Failure(400,"values.invalid");}
    }
    public static void validate(Snapshot source,Meaning meaning){
        if(meaning.valueMappings().size()>LIMIT)throw new Failure(400,"values.limit");
        var ids=new HashSet<String>();var values=new HashSet<List<String>>();
        for(var b:meaning.valueMappings()){
            if(b==null)throw new Failure(400,"values.invalid");
            try{if(!UUID.fromString(b.id()).toString().equals(b.id()))throw new IllegalArgumentException();}catch(RuntimeException ex){throw new Failure(400,"values.invalid");}
            var column=source.columns().stream().filter(c->c.name().equals(b.column())).findFirst().orElseThrow(()->new Failure(400,"values.invalid"));
            if(!ids.add(b.id())||!type(column.dataType()).equals(b.type())||!OntologyWizard.blocked(column,meaning.columns().get(column.name())).isEmpty()
                ||!values.add(List.of(b.column(),canonical(b.type(),b.value()))))throw new Failure(400,"values.invalid");
            text(b.label(),200,true);text(b.description(),500,false);if(b.aliases().size()>10)throw new Failure(400,"values.invalid");
            b.aliases().forEach(a->text(a,80,true));
        }
    }
    public static List<ColumnInfo> columns(Entry entry,Lookup input){
        if(!input.confirmed())throw new Failure(400,"confirmRequired");
        if(entry.revision()!=input.revision())throw new Failure(409,"stale");
        var result=new ArrayList<ColumnInfo>();
        for(String name:List.of(input.codeColumn(),input.labelColumn())){
            var c=entry.document().source().columns().stream().filter(v->v.name().equals(name)).findFirst().orElseThrow(()->new Failure(400,"values.invalid"));
            if(type(c.dataType()).isEmpty()||!OntologyWizard.blocked(c,entry.document().meaning().columns().get(name)).isEmpty())throw new Failure(400,"values.invalid");
            result.add(c);
        }
        return result;
    }
    public static Preview preview(String type,List<List<String>> rows,String now){
        if(rows.size()>ROWS)throw new Failure(400,"values.invalid");
        var items=new LinkedHashMap<List<String>,Candidate>();int skipped=0;
        for(var row:rows){
            if(row.size()!=2)throw new Failure(400,"values.invalid");
            try{String value=row.get(0),label=row.get(1);canonical(type,value);text(label,200,true);items.putIfAbsent(List.of(value,label),new Candidate(value,label));}
            catch(Failure ex){skipped++;}
        }
        return new Preview(type,new ArrayList<>(items.values()),rows.size(),skipped,now);
    }
    private static String normal(String value){return Normalizer.normalize(value,Normalizer.Form.NFC).toLowerCase(Locale.ROOT);}
    /** Whole terms only: EU != EUR; allow common Korean particles after a term. */
    public static boolean mentions(String question,String term){
        if(term==null||term.isBlank())return false;
        return Pattern.compile("(?<![\\p{L}\\p{N}_])"+Pattern.quote(normal(term.strip()))+"(?:(?:에서|으로|에게|에는|의|에|은|는|이|가|을|를|와|과|로))?(?![\\p{L}\\p{N}_])").matcher(normal(question)).find();
    }
    public static List<String> terms(Binding b){var result=new ArrayList<>(b.aliases());result.add(b.label());result.add(b.value());return result;}
    public static List<Binding> matching(Entry entry,String question){
        if(!"APPROVED".equals(entry.state()))return List.of();
        return entry.document().meaning().valueMappings().stream().filter(b->terms(b).stream().anyMatch(t->mentions(question,t))).toList();
    }
    public static void unambiguous(List<Entry> entries,String question){
        var meanings=new HashMap<String,Set<List<String>>>();
        for(var e:entries)for(var b:matching(e,question))for(var term:terms(b))if(mentions(question,term))
            meanings.computeIfAbsent(normal(term.strip()),k->new HashSet<>()).add(List.of(e.document().source().schema(),e.document().source().table(),b.column(),canonical(b.type(),b.value())));
        if(meanings.values().stream().anyMatch(v->v.size()>1))throw new Failure(409,"values.ambiguous");
    }
}
