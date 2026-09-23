package com.dbcompanion.service;

import com.dbcompanion.model.SelectAiTest.Action;
import java.util.*;
import java.util.regex.Pattern;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.select.*;
import net.sf.jsqlparser.util.TablesNamesFinder;

/** Display-only AST analysis. Never executes, repairs or authorizes the response SQL. */
public final class SelectAiSqlReferences {
    private SelectAiSqlReferences() {}
    public record Table(String owner,String name,String sqlName) {}
    public record Analysis(String status,String source,List<Table> tables) {
        public Analysis { tables=List.copyOf(tables); }
    }
    private static final Pattern FENCE=Pattern.compile("(?is)^```(?:sql)?[ \\t]*\\R(.*)\\R```$");
    private static final Pattern REFUSAL=Pattern.compile("(?is)^Sorry, unfortunately a valid SELECT statement could not be generated\\b.*");
    private static final Pattern EXCEPTION=Pattern.compile("(?m)^[ \\t]*Exception encountered: ORA-\\d{5}:");
    private static final Pattern SQL_START=Pattern.compile("(?im)^[ \\t]*(?:SELECT|WITH)\\b");
    private static Analysis empty(String status,String source){return new Analysis(status,source,List.of());}
    public static Analysis analyze(Action action,String text){
        if(action!=Action.SQL)return empty("NOT_SQL","RESPONSE");
        if(text==null||text.isBlank())return empty("UNAVAILABLE","RESPONSE");
        if(text.length()>20_000||text.indexOf('\0')>=0)return empty("UNSUPPORTED","RESPONSE");
        String sql=text.strip(),source="RESPONSE";
        if(REFUSAL.matcher(sql).matches()){
            var end=EXCEPTION.matcher(sql);if(!end.find())return empty("UNSUPPORTED","REJECTED_RESPONSE");
            String before=sql.substring(0,end.start());var start=SQL_START.matcher(before);
            if(!start.find())return empty("UNSUPPORTED","REJECTED_RESPONSE");
            sql=before.substring(start.start()).strip();source="REJECTED_RESPONSE";
        }
        var fence=FENCE.matcher(sql);if(fence.matches())sql=fence.group(1).strip();
        // Conservative input bound in addition to the parser timeout. A refusal is not a zero-table result.
        int depth=0;for(char c:sql.toCharArray()){if(c=='('&&++depth>64)return empty("UNSUPPORTED",source);if(c==')')depth=Math.max(0,depth-1);}
        try{
            var statements=CCJSqlParserUtil.parseStatements(sql,p->p.withTimeOut(1500));
            if(statements.size()!=1||!(statements.getFirst() instanceof Select))return empty("UNSUPPORTED",source);
            var statement=statements.getFirst();
            var finder=new ReferenceFinder();finder.getTables(statement);var names=finder.names;
            if(names.size()>100)return empty("UNSUPPORTED",source);
            var tables=new LinkedHashMap<List<String>,Table>();
            for(String name:names){
                var parts=SqlObjectName.parts(name);if(parts.size()>2)return empty("UNSUPPORTED",source);
                tables.putIfAbsent(parts,new Table(parts.size()==2?parts.getFirst():null,parts.getLast(),name));
            }
            var sorted=new ArrayList<>(tables.values());sorted.sort(Comparator.comparing(Table::sqlName));
            return new Analysis("PARSED",source,sorted);
        }catch(Exception ex){return empty("UNSUPPORTED",source);}
    }
    /** Oracle identifier folding and lexical CTE scope differ from the generic finder's global list. */
    private static final class ReferenceFinder extends TablesNamesFinder<Void> {
        final Set<String> names=new LinkedHashSet<>();
        final Deque<Set<String>> scopes=new ArrayDeque<>();
        private Void scoped(Select select,java.util.function.Supplier<Void> visit){
            var aliases=new HashSet<String>();
            if(select.getWithItemsList()!=null)for(var item:select.getWithItemsList())aliases.add(SqlObjectName.parts(item.getAliasName()).getFirst());
            scopes.push(aliases);try{return visit.get();}finally{scopes.pop();}
        }
        @Override public <S> Void visit(Select select,S context){return scoped(select,()->super.visit(select,context));}
        @Override public <S> Void visit(PlainSelect select,S context){return scoped(select,()->super.visit(select,context));}
        @Override public <S> Void visit(ParenthesedSelect select,S context){return scoped(select,()->super.visit(select,context));}
        @Override public <S> Void visit(SetOperationList select,S context){return scoped(select,()->super.visit(select,context));}
        @Override public <S> Void visit(net.sf.jsqlparser.schema.Table table,S context){
            String name=extractTableName(table);var parts=SqlObjectName.parts(name);
            if(parts.size()!=1||scopes.stream().noneMatch(scope->scope.contains(parts.getFirst())))names.add(name);
            return null;
        }
    }
}
