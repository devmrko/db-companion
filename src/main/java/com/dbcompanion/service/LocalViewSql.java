package com.dbcompanion.service;

import com.dbcompanion.model.Ontology.Failure;
import java.util.*;
import java.util.regex.*;

/** Intentionally supports projection-only local views, not arbitrary SQL hidden in a view. */
public final class LocalViewSql {
    private LocalViewSql(){}
    public record Base(String owner,String name){}
    private static final String IDENT="(?:\"(?:[^\"]|\"\")+\"|[A-Za-z][A-Za-z0-9_$#]*)";
    private static final Pattern VIEW=Pattern.compile("\\A\\s*SELECT\\s+(.+?)\\s+FROM\\s+("+IDENT+")(?:\\s*\\.\\s*("+IDENT+"))?(?:\\s+(?!WITH\\b)("+IDENT+"))?\\s*(?:WITH\\s+READ\\s+ONLY)?\\s*\\z",Pattern.CASE_INSENSITIVE|Pattern.DOTALL);
    private static String name(String value){return value.startsWith("\"")?value.substring(1,value.length()-1).replace("\"\"","\""):value.toUpperCase(Locale.ROOT);}
    public static Base base(String owner,String sql){
        if(sql==null||sql.length()>20000||sql.contains("--")||sql.contains("/*")||sql.contains("@"))throw new Failure(409,"query.localTables");
        var match=VIEW.matcher(sql);if(!match.matches())throw new Failure(409,"query.localTables");
        String schema=match.group(3)==null?owner:name(match.group(2)),table=name(match.group(3)==null?match.group(2):match.group(3));
        String alias=match.group(4)==null?table:name(match.group(4));
        var column=Pattern.compile("\\s*(?:("+IDENT+")\\s*\\.\\s*)?(?:"+IDENT+"|\\*)(?:\\s+(?:AS\\s+)?"+IDENT+")?\\s*",Pattern.CASE_INSENSITIVE);
        for(String projection:match.group(1).split(",",-1)){var c=column.matcher(projection);if(!c.matches()||c.group(1)!=null&&!name(c.group(1)).equals(alias))throw new Failure(409,"query.localTables");}
        return new Base(schema,table);
    }
}
