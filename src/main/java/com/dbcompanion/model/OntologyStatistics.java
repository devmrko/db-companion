package com.dbcompanion.model;

import com.dbcompanion.model.Ontology.*;
import java.math.BigDecimal;
import java.util.*;

/** Bounded, non-random observations. Never a whole-table profile or approved meaning. */
public final class OntologyStatistics {
    private OntologyStatistics(){}
    public static final int LIMIT=1000;
    public record Request(String schema,String table,int revision,String column,boolean confirmed){}
    public record Frequency(String value,int count){}
    public record Column(String name,String dataType,int observed,int nulls,int withheld,int distinctObserved,String minimum,String maximum,List<Frequency> topValues){}
    public record Report(String method,int limit,String checkedAt,List<Column> columns){}
    public static Report summarize(List<ColumnInfo> columns,List<List<String>> rows,String now){
        if(rows.size()>LIMIT||rows.stream().anyMatch(r->r.size()!=columns.size()))throw new Failure(400,"invalid");
        var result=new ArrayList<Column>();
        for(int i=0;i<columns.size();i++){
            var c=columns.get(i);var counts=new HashMap<String,Integer>();int nulls=0,withheld=0;String min=null,max=null;
            boolean number=!OntologyValues.type(c.dataType()).equals("TEXT")&&c.dataType().matches("(?i)(NUMBER|FLOAT).*"),date=c.dataType().matches("(?i)(DATE|TIMESTAMP).*"),blocked=false;
            for(var row:rows){String value=row.get(i);if(value==null){nulls++;continue;}
                if(OntologyWizard.privateValue(value)){blocked=true;continue;}
                if(value.length()>200||value.codePoints().anyMatch(Character::isISOControl)){withheld++;continue;}
                counts.merge(value,1,Integer::sum);
                if(number||date){if(min==null||compare(value,min,number)<0)min=value;if(max==null||compare(value,max,number)>0)max=value;}
            }
            if(blocked){counts.clear();min=null;max=null;withheld=rows.size()-nulls;}
            var top=counts.entrySet().stream().sorted(Map.Entry.<String,Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey())).limit(5).map(e->new Frequency(e.getKey(),e.getValue())).toList();
            result.add(new Column(c.name(),c.dataType(),rows.size(),nulls,withheld,counts.size(),min,max,top));
        }
        return new Report("LIMITED_ROWS_NOT_RANDOM_NOT_WHOLE_TABLE",LIMIT,now,List.copyOf(result));
    }
    private static int compare(String a,String b,boolean number){return number?new BigDecimal(a).compareTo(new BigDecimal(b)):a.compareTo(b);}
    /** Send distributions without additional raw values from the larger sample. */
    public static List<Map<String,Object>> aiEvidence(Report report){return report.columns().stream().map(c->Map.<String,Object>of("column",c.name(),"observed",c.observed(),"nulls",c.nulls(),"withheld",c.withheld(),"distinctObserved",c.distinctObserved(),"topFrequencies",c.topValues().stream().map(Frequency::count).toList())).toList();}
}
