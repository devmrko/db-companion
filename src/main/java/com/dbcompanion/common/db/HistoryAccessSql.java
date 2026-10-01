package com.dbcompanion.common.db;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.core.io.ClassPathResource;

/** Separate, optional capability gateway; the existing audit package and captured records are unchanged. */
public final class HistoryAccessSql {
    private HistoryAccessSql() {}
    public static final String PACKAGE = "DBC_METADATA_ACCESS";
    public static List<HistorySql.Asset> assets(String owner) {
        return List.of(
            new HistorySql.Asset(owner,"DBC_MH_TARGETS","TABLE","CREATE TABLE " + HistorySql.qualified(owner,"DBC_MH_TARGETS")
                + " (SCHEMA_NAME VARCHAR2(128 BYTE) NOT NULL, TABLE_NAME VARCHAR2(128 BYTE) NOT NULL,"
                + " BEFORE_TRIGGER VARCHAR2(128 BYTE) NOT NULL, AFTER_TRIGGER VARCHAR2(128 BYTE) NOT NULL,"
                + " BEFORE_SOURCE CLOB NOT NULL, AFTER_SOURCE CLOB NOT NULL, SPEC_SOURCE CLOB NOT NULL, BODY_SOURCE CLOB NOT NULL,"
                + " CONSTRAINT DBC_MH_TARGETS_PK PRIMARY KEY (SCHEMA_NAME,TABLE_NAME))"),
            new HistorySql.Asset(owner,"DBC_MH_ACCESS","TABLE","CREATE TABLE " + HistorySql.qualified(owner,"DBC_MH_ACCESS")
                + " (SCHEMA_NAME VARCHAR2(128 BYTE) NOT NULL, TABLE_NAME VARCHAR2(128 BYTE) NOT NULL, GRANTEE VARCHAR2(128 BYTE) NOT NULL,"
                + " CAN_READ CHAR(1 BYTE) NOT NULL, CAN_MANAGE CHAR(1 BYTE) NOT NULL, CHANGED_AT TIMESTAMP(6) WITH TIME ZONE NOT NULL,"
                + " CHANGED_BY VARCHAR2(128 BYTE) NOT NULL, CONSTRAINT DBC_MH_ACCESS_PK PRIMARY KEY (SCHEMA_NAME,TABLE_NAME,GRANTEE),"
                + " CONSTRAINT DBC_MH_ACCESS_CK CHECK (CAN_READ IN ('Y','N') AND CAN_MANAGE IN ('Y','N') AND (CAN_MANAGE='N' OR CAN_READ='Y')))"),
            new HistorySql.Asset(owner,PACKAGE,"PACKAGE",resource("access-spec.sql",owner)),
            new HistorySql.Asset(owner,PACKAGE,"PACKAGE BODY",resource("access-body.sql",owner)));
    }
    private static String resource(String name,String owner) {
        try(var in=new ClassPathResource("db/history/"+name).getInputStream()) {
            return new String(in.readAllBytes(),StandardCharsets.UTF_8).replace("{{OWNER}}",MetadataSql.identifier(owner));
        } catch(java.io.IOException ex) { throw new IllegalStateException(ex); }
    }
    public static String grantee(String user) { return "PUBLIC".equals(user)?"PUBLIC":MetadataSql.identifier(user); }
}
