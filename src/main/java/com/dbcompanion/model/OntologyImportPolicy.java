package com.dbcompanion.model;

import java.util.Set;
import java.util.regex.Pattern;

/** Import suggestions only, not an authorization rule or a reason to delete saved definitions. */
public final class OntologyImportPolicy {
    private OntologyImportPolicy() {}
    private static final Set<String> APP_TABLES=Set.of(
        "DBC_AI_HISTORY","DBC_APP_RECORD","DBC_BUSINESS_TERM","DBC_GLOSSARY_TERM",
        "DBC_ONTOLOGY_CATALOG","DBC_PROFILE_AUDIT_CONFIG","DBC_PROFILE_HISTORY",
        "DBC_METADATA_TRACKING","DBC_METADATA_HISTORY","DBC_MH_TARGETS","DBC_MH_ACCESS",
        "DBC_SQL_CACHE_ARCHIVE");
    private static final Pattern TEXT=Pattern.compile("DR\\$.+\\$(?:I|K|N|U|B|C|Q|R|G|P|O|D|KG|SN|ST|SD|SV|STZ|S|X|Y|Z)");
    private static final Pattern VECTOR=Pattern.compile("VECTOR\\$.+\\$[0-9]+(?:_[0-9]+)*\\$(?:IVF_FLAT_CENTROIDS|IVF_FLAT_CENTROID_PARTITIONS|HNSW_.+)");
    public static String reason(String name,String secondary,String maintained) {
        if("Y".equals(maintained))return "ORACLE_MAINTAINED";
        if("Y".equals(secondary))return "SECONDARY";
        if(APP_TABLES.contains(name))return "APP_STORE";
        if(TEXT.matcher(name).matches())return "TEXT_NAME";
        if(VECTOR.matcher(name).matches())return "VECTOR_NAME";
        // Select AI's feedback storage convention; do not exclude arbitrary *$VECTAB tables.
        if(name.length()>AiFeedback.TABLE_SUFFIX.length()&&name.endsWith(AiFeedback.TABLE_SUFFIX))return "FEEDBACK_NAME";
        return null;
    }
}
