package com.dbcompanion.service;

import com.dbcompanion.model.AiAssistant.Failure;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.util.TablesNamesFinder;
import java.util.*;
import java.util.regex.Pattern;

/** Single SELECT AST plus conservative lexical/function restrictions. Never repairs SQL. */
public final class SelectAiReadSql {
    private SelectAiReadSql() {}
    public record Checked(String sql,List<String> tables,Set<String> identifiers) {
        public Checked { tables=List.copyOf(tables);identifiers=Set.copyOf(identifiers); }
    }
    private static final Set<String> FUNCTIONS=Set.of("COUNT","SUM","AVG","MIN","MAX","ROUND","TRUNC","FLOOR","CEIL","ABS","MOD","POWER","SQRT",
            "COALESCE","NVL","NVL2","NULLIF","DECODE","GREATEST","LEAST","UPPER","LOWER","INITCAP","LENGTH","LENGTHB","SUBSTR","SUBSTRB","INSTR",
            "REPLACE","TRANSLATE","TRIM","LTRIM","RTRIM","LPAD","RPAD","CONCAT","TO_CHAR","TO_NUMBER","TO_DATE","TO_TIMESTAMP","ADD_MONTHS",
            "MONTHS_BETWEEN","LAST_DAY","NEXT_DAY","EXTRACT","UNISTR","ASCII","CHR","REGEXP_LIKE","REGEXP_SUBSTR","REGEXP_REPLACE","REGEXP_INSTR",
            "ROW_NUMBER","RANK","DENSE_RANK","LAG","LEAD","FIRST_VALUE","LAST_VALUE","NTILE","RATIO_TO_REPORT","STDDEV","VARIANCE","LISTAGG");
    private static final Set<String> PAREN_KEYWORDS=Set.of("IN","EXISTS","AS","OVER","BY","FROM","JOIN","WHERE","AND","OR","NOT","ON","WHEN","THEN","ELSE","SELECT","DISTINCT","HAVING","GROUP","UNION","ALL");
    private static final Set<String> BLOCKED=Set.of("INSERT","UPDATE","DELETE","MERGE","CREATE","ALTER","DROP","TRUNCATE","GRANT","REVOKE","COMMIT","ROLLBACK",
            "BEGIN","DECLARE","EXECUTE","CALL","PROCEDURE","FUNCTION","INTO","FOR","LOCK","NEXTVAL","CURRVAL","CONNECT","MODEL","PIVOT","UNPIVOT",
            "MATCH_RECOGNIZE","XMLTABLE","JSON_TABLE","TABLE","CURSOR","XMLQUERY","XMLCAST","TREAT","NEW","CAST","LATERAL","APPLY","VERSIONS","FLASHBACK");
    private static final Pattern TOKEN=Pattern.compile("\\G(?:\\s+|('(?:''|[^'])*')|(\"(?:\"\"|[^\"])+\")|([A-Za-z][A-Za-z0-9_$#]*)|([0-9]+(?:\\.[0-9]+)?)|(<=|>=|<>|!=|\\|\\||[(),.+*/=<>-]))");
    private record Token(String type,String text) {}
    public static Failure blocked(){return new Failure(422,"aitest.sqlBlocked","지원하는 조회 SQL이 아닙니다. 쓰기·사용자 함수·외부 연결·미지원 구문은 실행하지 않습니다.");}
    public static Checked check(String value){
        if(value==null||value.length()>20_000)throw blocked();String sql=value.strip();
        if(sql.startsWith("```sql\n")&&sql.endsWith("```"))sql=sql.substring(7,sql.length()-3).strip();
        if(sql.endsWith(";"))sql=sql.substring(0,sql.length()-1).strip();
        var tokens=new ArrayList<Token>();var identifiers=new LinkedHashSet<String>();var matcher=TOKEN.matcher(sql);int end=0,depth=0;
        while(end<sql.length()){
            matcher.region(end,sql.length());if(!matcher.lookingAt())throw blocked();end=matcher.end();
            Token token=null;
            if(matcher.group(1)!=null)token=new Token("literal",matcher.group(1));
            else if(matcher.group(2)!=null){String name=matcher.group(2).substring(1,matcher.group(2).length()-1).replace("\"\"","\"");token=new Token("quoted",name);identifiers.add(name);}
            else if(matcher.group(3)!=null){String name=matcher.group(3).toUpperCase(Locale.ROOT);if(BLOCKED.contains(name))throw blocked();token=new Token("word",name);identifiers.add(name);}
            else if(matcher.group(4)!=null)token=new Token("number",matcher.group(4));
            else if(matcher.group(5)!=null){String symbol=matcher.group(5);if(symbol.equals("(")&&++depth>32)throw blocked();if(symbol.equals(")")&&--depth<0)throw blocked();token=new Token("symbol",symbol);}
            if(token!=null)tokens.add(token);if(tokens.size()>5000||identifiers.size()>500)throw blocked();
        }
        if(depth!=0||tokens.isEmpty()||!tokens.getFirst().type().equals("word")||!Set.of("SELECT","WITH").contains(tokens.getFirst().text()))throw blocked();
        for(int i=0;i<tokens.size()-1;i++){
            var current=tokens.get(i);var next=tokens.get(i+1);
            if(current.type().equals("word")&&current.text().equals("SELECT")&&next.type().equals("word")&&next.text().equals("AI"))throw blocked();
            if(current.type().equals("symbol")&&next.type().equals("symbol")&&Set.of("--","/*","*/").contains(current.text()+next.text()))throw blocked();
            if(next.type().equals("symbol")&&next.text().equals("(")&&Set.of("word","quoted").contains(current.type())
                    &&(current.type().equals("quoted")||!FUNCTIONS.contains(current.text())&&!PAREN_KEYWORDS.contains(current.text())||i>0&&tokens.get(i-1).text().equals("."))){
                throw blocked();
            }
            if(Set.of("word","quoted").contains(current.type())&&Set.of("NEXTVAL","CURRVAL").contains(current.text().toUpperCase(Locale.ROOT)))throw blocked();
        }
        try{
            var parsed=CCJSqlParserUtil.parse(sql,p->p.withTimeOut(1500));if(!(parsed instanceof Select))throw blocked();
            var finder=new TablesNamesFinder<Void>();var tables=new ArrayList<>(finder.getTables(parsed));
            if(tables.size()>40)throw blocked();for(String table:tables){var parts=SqlObjectName.parts(table);if(parts.isEmpty()||parts.size()>2)throw blocked();}
            return new Checked(sql,tables,identifiers);
        }catch(Failure ex){throw ex;}catch(Exception ex){throw blocked();}
    }
}
