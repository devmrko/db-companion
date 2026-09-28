package com.dbcompanion.service;

import com.dbcompanion.model.AiAssistant.Failure;
import java.util.regex.Pattern;

/** Query-response envelope only. Oracle decides SQL syntax, objects and privileges. */
public final class SelectAiReadSql {
    private SelectAiReadSql() {}
    public record Checked(String sql) {}
    private static final String SPACE="(?:\\s+|--[^\\r\\n]*(?:\\R|\\z)|/\\*.*?\\*/)";
    private static final Pattern QUERY=Pattern.compile("(?is)^"+SPACE+"*(?:SELECT|WITH)\\b");
    private static final Pattern AI_COMMAND=Pattern.compile("(?is)^"+SPACE+"*SELECT"+SPACE+"+AI\\b");
    private static final Pattern FENCE=Pattern.compile("(?is)^```(?:sql)?[ \\t]*\\R(.*)\\R```$");
    private static String body(String value){
        String sql=value.strip();var fence=FENCE.matcher(sql);
        return fence.matches()?fence.group(1).strip():sql;
    }
    /** Recognition for the query-result UI, not a SQL validity or safety verdict. */
    public static boolean isQueryResponse(String value){
        if(value==null)return false;String sql=body(value);
        return QUERY.matcher(sql).find()&&!AI_COMMAND.matcher(sql).find();
    }
    public static Failure blocked(){
        return new Failure(422,"aitest.sqlBlocked","조회할 SELECT/WITH SQL 응답이 필요합니다. 빈 응답·명령·입력 한도 초과는 실행하지 않습니다.");
    }
    public static Checked check(String value){
        if(value==null||value.length()>20_000||value.indexOf('\0')>=0||!isQueryResponse(value))throw blocked();
        String sql=body(value);
        if(sql.endsWith(";"))sql=sql.substring(0,sql.length()-1).stripTrailing();
        return new Checked(sql);
    }
}
