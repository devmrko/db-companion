package com.dbcompanion.repository;

import com.dbcompanion.model.FunctionCatalog;
import com.dbcompanion.model.FunctionCatalog.*;
import com.dbcompanion.model.RoutineSource.Definition;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class FunctionCatalogRepository {
    private final JdbcTemplate jdbc;
    public FunctionCatalogRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public List<Entry> list(String schema) {
        return jdbc.query("""
                SELECT DISTINCT p.OBJECT_NAME, p.PROCEDURE_NAME
                FROM SYS.ALL_PROCEDURES p
                WHERE p.OWNER = ? AND (
                    (p.OBJECT_TYPE = 'FUNCTION' AND p.PROCEDURE_NAME IS NULL) OR
                    (p.OBJECT_TYPE = 'PACKAGE' AND p.PROCEDURE_NAME IS NOT NULL AND EXISTS (
                        SELECT 1 FROM SYS.ALL_ARGUMENTS a
                        WHERE a.OWNER = p.OWNER AND a.OBJECT_ID = p.OBJECT_ID
                          AND a.SUBPROGRAM_ID = p.SUBPROGRAM_ID AND a.POSITION = 0 AND a.DATA_LEVEL = 0)))
                ORDER BY p.OBJECT_NAME, p.PROCEDURE_NAME
                """,(r,n)->{
            String object=r.getString(1),member=r.getString(2);
            return new Entry(object,member,object+(member==null?"":"."+member),FunctionCatalog.reference(schema,object,member));
        },schema);
    }
    public Detail detail(Definition definition) {
        var objects=jdbc.query("""
                SELECT OBJECT_TYPE, STATUS, TO_CHAR(LAST_DDL_TIME, 'YYYY-MM-DD HH24:MI:SS')
                FROM SYS.ALL_OBJECTS WHERE OWNER = ? AND OBJECT_NAME = ? AND SUBOBJECT_NAME IS NULL
                  AND OBJECT_TYPE IN ('FUNCTION','PROCEDURE','PACKAGE','PACKAGE BODY') ORDER BY OBJECT_TYPE
                """,(r,n)->new ObjectState(r.getString(1),r.getString(2),r.getString(3)),definition.owner(),definition.object());
        boolean packaged=definition.member()!=null;
        Object[] args=packaged?new Object[]{definition.owner(),definition.object(),definition.member()}
                :new Object[]{definition.owner(),definition.object()};
        var arguments=jdbc.query("""
                SELECT SUBPROGRAM_ID, OVERLOAD, POSITION, ARGUMENT_NAME, DATA_TYPE, IN_OUT, DEFAULTED,
                       TYPE_OWNER, TYPE_NAME, TYPE_SUBNAME, PLS_TYPE
                FROM SYS.ALL_ARGUMENTS WHERE OWNER = ? AND DATA_LEVEL = 0 AND
                """+(packaged?"PACKAGE_NAME = ? AND OBJECT_NAME = ?":"PACKAGE_NAME IS NULL AND OBJECT_NAME = ?")
                +" ORDER BY SUBPROGRAM_ID, SEQUENCE",(r,n)->new Argument(r.getInt(1),r.getString(2),r.getInt(3),r.getString(4),
                    type(r.getString(5),r.getString(8),r.getString(9),r.getString(10),r.getString(11)),r.getString(6),"Y".equals(r.getString(7))),args);
        return new Detail(definition,objects,arguments);
    }
    public static String type(String data,String owner,String name,String subname,String pls) {
        if(name!=null)return Stream.of(owner,name,subname).filter(v->v!=null&&!v.isEmpty()).collect(java.util.stream.Collectors.joining("."));
        return pls!=null?pls:data;
    }
}
