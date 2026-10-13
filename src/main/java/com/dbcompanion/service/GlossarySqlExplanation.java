package com.dbcompanion.service;

import com.dbcompanion.model.BusinessGlossary;
import com.dbcompanion.model.GlossaryRdf;
import com.dbcompanion.model.SelectAiTest;
import java.util.*;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.util.TablesNamesFinder;

/** Display-only AST evidence, not a correctness verdict, repair or execution authorization. */
public final class GlossarySqlExplanation {
    private GlossarySqlExplanation() {}
    public record Reference(GlossaryRdf.Mapping mapping,String status) {}
    public record Analysis(String status,List<Reference> references,List<String> rules) {}
    public static Analysis analyze(String sql,BusinessGlossary.Term term,String owner){
        var tables=SelectAiSqlReferences.analyze(SelectAiTest.Action.SQL,sql);
        if(!"PARSED".equals(tables.status())||!"RESPONSE".equals(tables.source()))return new Analysis("UNSUPPORTED",List.of(),List.of());
        var mappings=term.rdf()==null?List.<GlossaryRdf.Mapping>of():term.rdf().mappings();
        try{
            var statements=CCJSqlParserUtil.parseStatements(sql,p->p.withTimeOut(1500));var finder=new Columns();finder.getTables(statements.getFirst());
            var refs=new ArrayList<Reference>();
            for(var mapping:mappings){
                String object=mapping.schema()+"."+mapping.table();boolean source=tables.tables().stream().anyMatch(t->object.equals(Objects.toString(t.owner(),owner)+"."+t.name()));
                boolean found=false,ambiguous=false;
                for(var column:finder.columns){
                    var names=SqlObjectName.parts(column.getColumnName());if(!mapping.column().equals(names.getLast()))continue;
                    String qualifier=column.getTable()==null?"":Objects.toString(column.getTable().getFullyQualifiedName(),"");
                    if(qualifier.isBlank()){ambiguous=true;continue;}
                    var parts=SqlObjectName.parts(qualifier);String key=String.join(".",parts);var targets=finder.aliases.getOrDefault(key,Set.of());
                    if(targets.size()==1&&targets.contains(object)&&source)found=true;else if(targets.isEmpty()||targets.size()>1)ambiguous=true;
                }
                refs.add(new Reference(mapping,found?"REFERENCED":!source?"SOURCE_NOT_REFERENCED":ambiguous?"UNCONFIRMED":"COLUMN_NOT_REFERENCED"));
            }
            return new Analysis("PARSED",List.copyOf(refs),term.rdf()==null?List.of():term.rdf().rules());
        }catch(Exception ex){return new Analysis("UNSUPPORTED",List.of(),List.of());}
    }
    private static final class Columns extends TablesNamesFinder<Void> {
        private final List<Column> columns=new ArrayList<>();private final Map<String,Set<String>> aliases=new HashMap<>();
        @Override public <S> Void visit(Table table,S context){
            var parts=SqlObjectName.parts(table.getFullyQualifiedName());String object=String.join(".",parts);
            // Only qualified base tables prove source identity. CTEs and unqualified aliases remain unconfirmed.
            if(parts.size()==2){aliases.computeIfAbsent(object,k->new HashSet<>()).add(object);String name=table.getAlias()==null?parts.getLast():SqlObjectName.parts(table.getAlias().getName()).getFirst();aliases.computeIfAbsent(name,k->new HashSet<>()).add(object);}
            else {String name=table.getAlias()==null?parts.getLast():SqlObjectName.parts(table.getAlias().getName()).getFirst();aliases.computeIfAbsent(name,k->new HashSet<>()).add("UNRESOLVED");}
            return super.visit(table,context);
        }
        @Override public <S> Void visit(Column column,S context){columns.add(column);return super.visit(column,context);}
    }
}
