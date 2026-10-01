package com.dbcompanion;

import com.dbcompanion.model.OntologyImportPolicy;
import com.dbcompanion.model.Ontology.Catalog;
import com.dbcompanion.model.TableInfo;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class OntologyImportPolicyTest {
    @Test void dictionaryFlagsClassifyArbitraryInternalNamesWithoutUsingCustomerSchemas(){
        assertThat(reason("ANY_NAME","Y","N")).isEqualTo("SECONDARY");
        assertThat(reason("ANY_VIEW","N","Y")).isEqualTo("ORACLE_MAINTAINED");
        assertThat(reason("ANY_NAME","N","N")).isNull();
    }
    @Test void knownInternalConventionsAreLabeledAsNameBased(){
        for(var suffix:List.of("I","K","N","U","B","C","Q"))assertThat(reason("DR$EXAMPLE_IDX$"+suffix,null,null)).isEqualTo("TEXT_NAME");
        for(var suffix:List.of("IVF_FLAT_CENTROIDS","IVF_FLAT_CENTROID_PARTITIONS"))assertThat(reason("VECTOR$EXAMPLE_IDX$123_456_0$"+suffix,null,null)).isEqualTo("VECTOR_NAME");
        assertThat(reason("EXAMPLE_FEEDBACK_VECINDEX$VECTAB",null,null)).isEqualTo("FEEDBACK_NAME");
    }
    @Test void onlyKnownAppNamesAreExcludedAndQuotedCaseIsRespected(){
        for(var name:List.of("DBC_AI_HISTORY","DBC_APP_RECORD","DBC_BUSINESS_TERM","DBC_PROFILE_AUDIT_CONFIG","DBC_MH_TARGETS","DBC_MH_ACCESS","DBC_SQL_CACHE_ARCHIVE"))assertThat(reason(name,null,null)).isEqualTo("APP_STORE");
        for(var name:List.of("DBC_ORDERS","DBC_BT_ALIAS_PROBE_1234","DBC_AI_HISTORY_BACKUP","dbc_ai_history","DR$BUSINESS","VECTOR$BUSINESS","BUSINESS$VECTAB","SALES_VIEW","SYS_CUSTOMER"))assertThat(reason(name,null,null)).isNull();
    }
    @Test void catalogHintsDoNotRemoveTablesOrSavedDefinitionsAndCannotBeMutated(){
        var hints=new HashMap<String,String>();hints.put("DBC_APP_RECORD","APP_STORE");
        var table=new TableInfo("DBC_APP_RECORD","metadata");
        var catalog=new Catalog("READY",false,List.of(table),List.of(),"now",hints);hints.clear();
        assertThat(catalog.tables()).containsExactly(table);
        assertThat(catalog.importExclusions()).containsEntry("DBC_APP_RECORD","APP_STORE");
        assertThatThrownBy(()->catalog.importExclusions().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(new JsonMapper().writeValueAsString(catalog)).contains("\"importExclusions\":{\"DBC_APP_RECORD\":\"APP_STORE\"}");
    }
    private String reason(String name,String secondary,String maintained){return OntologyImportPolicy.reason(name,secondary,maintained);}
}
