package com.dbcompanion.service;

import com.dbcompanion.model.Ontology.*;
import java.util.*;
import java.util.regex.*;

/** A closed SELECT grammar, not a blacklist or a general Oracle SQL parser. */
public final class ReviewedSql {
    private ReviewedSql(){}
    public record Table(String name,Set<String> columns){public Table{columns=Set.copyOf(columns);}}
    public record Scope(String schema,Map<String,Table> tables,List<OntologyInquiry.Evidence> relations){public Scope{tables=Map.copyOf(tables);relations=List.copyOf(relations);}}
    public record Checked(String sql,List<String> tables){public Checked{tables=List.copyOf(tables);}}
    private record Token(String kind,String value){}
    private record Column(String alias,String name){}
    private record Pair(Column a,Column b){}
    private record Join(String alias,List<Pair> pairs){}
    private static final Set<String> FUNCTIONS=Set.of("COUNT","SUM","AVG","MIN","MAX","ROUND","TRUNC","COALESCE","NVL","NULLIF","UPPER","LOWER","LENGTH","ABS","TRIM");
    private static final Set<String> RESERVED=Set.of("SELECT","FROM","WHERE","GROUP","BY","HAVING","ORDER","ASC","DESC","NULLS","FIRST","LAST","FETCH","ROWS","ROW","ONLY","JOIN","INNER","LEFT","OUTER","ON","AND","OR","NOT","IS","NULL","IN","LIKE","BETWEEN","AS","DISTINCT","DATE","TIMESTAMP","SYSDATE","CURRENT_DATE");
    private static final Pattern LEX=Pattern.compile("\\G(?:\\s+|('(?:''|[^'])*')|(\"(?:\"\"|[^\"])+\")|([A-Za-z][A-Za-z0-9_$#]*)|([0-9]+(?:\\.[0-9]+)?)|(<=|>=|<>|!=|[(),.+*/=<>-]))");
    private static Failure invalid(){return new Failure(422,"query.sqlBlocked");}
    public static boolean scalar(String type){return type!=null&&type.toUpperCase(Locale.ROOT).matches("(?:NUMBER|FLOAT|VARCHAR2|NVARCHAR2|CHAR|NCHAR|BINARY_FLOAT|BINARY_DOUBLE|DATE|TIMESTAMP)(?:\\([^)]*\\))?(?: WITH (?:LOCAL )?TIME ZONE)?");}
    public static Scope scope(OntologyInquiry.Search search,List<Entry> entries){
        var tables=new LinkedHashMap<String,Table>();for(var e:entries){var s=e.document().source();var names=new LinkedHashSet<String>();s.columns().stream().filter(c->scalar(c.dataType())&&OntologyContext.safe(c,e.document().meaning().columns().get(c.name()))).forEach(c->names.add(c.name()));tables.put(s.table(),new Table(s.table(),names));}
        return new Scope(search.schema(),tables,search.evidence().stream().filter(e->e.usable()&&e.kind().equals("RELATION")).toList());
    }
    public static Checked check(String value,Scope scope){
        if(value==null||value.length()>20000||value.indexOf(0)>=0)throw invalid();String sql=value.strip();
        if(sql.startsWith("```sql\n")&&sql.endsWith("```"))sql=sql.substring(7,sql.length()-3).strip();
        if(sql.endsWith(";"))sql=sql.substring(0,sql.length()-1).strip();
        if(sql.isEmpty())throw invalid();var tokens=new ArrayList<Token>();var matcher=LEX.matcher(sql);int end=0;
        while(end<sql.length()){
            matcher.region(end,sql.length());if(!matcher.lookingAt())throw invalid();end=matcher.end();
            if(matcher.group(1)!=null)tokens.add(new Token("STRING",matcher.group(1)));
            else if(matcher.group(2)!=null)tokens.add(new Token("IDENT",matcher.group(2).substring(1,matcher.group(2).length()-1).replace("\"\"","\"")));
            else if(matcher.group(3)!=null)tokens.add(new Token("WORD",matcher.group(3).toUpperCase(Locale.ROOT)));
            else if(matcher.group(4)!=null)tokens.add(new Token("NUMBER",matcher.group(4)));
            else if(matcher.group(5)!=null)tokens.add(new Token("SYMBOL",matcher.group(5)));
            if(tokens.size()>4000)throw invalid();
        }
        var tables=new LinkedHashSet<String>();int start=0,branches=0;
        for(int i=0;i<=tokens.size();i++){
            if(i<tokens.size()&&!(tokens.get(i).kind().equals("WORD")&&tokens.get(i).value().equals("UNION")))continue;
            var parser=new Parser(tokens.subList(start,i),scope);parser.select();tables.addAll(parser.aliases.values());
            if(++branches>10)throw invalid();
            if(i<tokens.size()){
                if(i+1>=tokens.size()||!tokens.get(i+1).kind().equals("WORD")||!tokens.get(i+1).value().equals("ALL"))throw invalid();
                i++;start=i+1;
            }
        }
        return new Checked(sql,new ArrayList<>(tables));
    }
    private static final class Parser {
        final List<Token> tokens;final Scope scope;int at=0,depth=0;final Map<String,String> aliases=new LinkedHashMap<>();final List<Column> columns=new ArrayList<>();final List<Join> joins=new ArrayList<>();
        Parser(List<Token> tokens,Scope scope){this.tokens=tokens;this.scope=scope;}
        boolean is(String s){return at<tokens.size()&&!Set.of("STRING","IDENT").contains(tokens.get(at).kind())&&tokens.get(at).value().equals(s);}
        boolean take(String s){if(is(s)){at++;return true;}return false;}
        void need(String s){if(!take(s))throw invalid();}
        boolean identifier(){return at<tokens.size()&&(tokens.get(at).kind().equals("IDENT")||tokens.get(at).kind().equals("WORD")&&!RESERVED.contains(tokens.get(at).value()));}
        String name(){if(!identifier())throw invalid();return tokens.get(at++).value();}
        void select(){
            need("SELECT");take("DISTINCT");int count=0;do{expr();if(take("AS"))name();else if(identifier())name();if(++count>40)throw invalid();}while(take(","));
            need("FROM");table();
            while(is("JOIN")||is("INNER")||is("LEFT")){
                if(!take("INNER")&&take("LEFT"))take("OUTER");need("JOIN");String alias=table();need("ON");var pairs=new ArrayList<Pair>();
                do{Column a=column();need("=");Column b=column();pairs.add(new Pair(a,b));if(pairs.size()>32)throw invalid();}while(take("AND"));joins.add(new Join(alias,pairs));
            }
            if(take("WHERE"))condition();
            if(take("GROUP")){need("BY");do{expr();}while(take(","));}
            if(take("HAVING"))condition();
            if(take("ORDER")){need("BY");do{expr();if(!take("ASC"))take("DESC");if(take("NULLS")&&!take("FIRST"))need("LAST");}while(take(","));}
            if(take("FETCH")){need("FIRST");if(at>=tokens.size()||!tokens.get(at).kind().equals("NUMBER")||!tokens.get(at).value().matches("[1-9][0-9]{0,5}"))throw invalid();at++;if(!take("ROWS"))need("ROW");need("ONLY");}
            if(at!=tokens.size())throw invalid();
            for(var c:columns){String table=aliases.get(c.alias());if(table==null||!scope.tables().get(table).columns().contains(c.name()))throw invalid();}
            var seen=new LinkedHashSet<String>();seen.add(aliases.keySet().iterator().next());
            for(var j:joins){
                boolean allowed=false;
                for(String prior:seen)for(var evidence:scope.relations()){
                    String a=aliases.get(j.alias()),b=aliases.get(prior);var expected=new HashSet<Pair>();
                    if(evidence.source().equals(a)&&evidence.target().equals(b))for(int i=0;i<evidence.from().size();i++)expected.add(new Pair(new Column(j.alias(),evidence.from().get(i)),new Column(prior,evidence.to().get(i))));
                    else if(evidence.target().equals(a)&&evidence.source().equals(b))for(int i=0;i<evidence.from().size();i++)expected.add(new Pair(new Column(j.alias(),evidence.to().get(i)),new Column(prior,evidence.from().get(i))));
                    if(!expected.isEmpty()&&expected.size()==j.pairs().size()&&expected.stream().allMatch(p->j.pairs().contains(p)||j.pairs().contains(new Pair(p.b(),p.a()))))allowed=true;
                }
                if(!allowed)throw invalid();seen.add(j.alias());
            }
        }
        String table(){String first=name(),table=first;if(take(".")){if(!first.equals(scope.schema()))throw invalid();table=name();}
            if(!scope.tables().containsKey(table))throw invalid();String alias=table;if(take("AS"))alias=name();else if(identifier())alias=name();
            if(aliases.putIfAbsent(alias,table)!=null||aliases.size()>10)throw invalid();return alias;
        }
        Column column(){String alias=name();need(".");var column=new Column(alias,name());columns.add(column);return column;}
        void expr(){if(++depth>24)throw invalid();try{term();while(take("+")||take("-"))term();}finally{depth--;}}
        void term(){atom();while(take("*")||take("/"))atom();}
        void atom(){
            if(take("+")||take("-")){if(++depth>24)throw invalid();try{atom();}finally{depth--;}return;}
            if(take("(")){expr();need(")");return;}
            if(at>=tokens.size())throw invalid();var token=tokens.get(at);
            if(token.kind().equals("STRING")||token.kind().equals("NUMBER")){at++;return;}
            if(take("NULL")||take("SYSDATE")||take("CURRENT_DATE"))return;
            if(take("DATE")||take("TIMESTAMP")){if(at>=tokens.size()||!tokens.get(at).kind().equals("STRING"))throw invalid();at++;return;}
            if(token.kind().equals("WORD")&&FUNCTIONS.contains(token.value())&&at+1<tokens.size()&&tokens.get(at+1).value().equals("(")){
                at+=2;if(token.value().equals("COUNT")&&take("*")){need(")");return;}take("DISTINCT");int args=0;do{expr();if(++args>8)throw invalid();}while(take(","));need(")");return;
            }
            column();
        }
        void condition(){if(++depth>24)throw invalid();try{conjunction();while(take("OR"))conjunction();}finally{depth--;}}
        void conjunction(){predicate();while(take("AND"))predicate();}
        void predicate(){
            if(take("NOT")){if(++depth>24)throw invalid();try{predicate();}finally{depth--;}return;}
            if(take("(")){condition();need(")");return;}
            expr();if(take("IS")){take("NOT");need("NULL");return;}
            boolean not=take("NOT");if(take("IN")){need("(");int n=0;do{expr();if(++n>100)throw invalid();}while(take(","));need(")");return;}
            if(take("BETWEEN")){expr();need("AND");expr();return;}if(take("LIKE")){expr();return;}if(not)throw invalid();
            if(take("=")||take("<>")||take("!=")||take("<")||take(">")||take("<=")||take(">=")){expr();return;}throw invalid();
        }
    }
}
