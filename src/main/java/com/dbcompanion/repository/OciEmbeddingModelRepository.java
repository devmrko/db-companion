package com.dbcompanion.repository;

import com.dbcompanion.common.i18n.UiMessages;
import com.dbcompanion.model.OciEmbeddingModels.*;
import com.dbcompanion.model.VectorSearch.Failure;
import java.sql.Types;
import java.util.*;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;
import static com.dbcompanion.model.VectorSearch.quote;

@Repository
public class OciEmbeddingModelRepository {
    private static final String PACKAGE="DBMS_CLOUD_OCI_GA_GENERATIVE_AI";
    private static final String RESPONSE="DBMS_CLOUD_OCI_GA_GENERATIVE_AI_LIST_MODELS_RESPONSE_T";
    private static final String CAPABILITIES="DBMS_CLOUD_OCI_GENERATIVE_AI_VARCHAR2_TBL";
    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;
    public OciEmbeddingModelRepository(JdbcTemplate jdbc,JsonMapper mapper) {
        this.jdbc=new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource()));this.jdbc.setQueryTimeout(30);this.mapper=mapper;
    }
    private String object(String name,String type) {
        // Never execute a user-schema shadow package/type or a database link.
        var owners=jdbc.queryForList("""
                SELECT DISTINCT o.OWNER FROM SYS.ALL_OBJECTS o
                WHERE o.OBJECT_NAME=? AND o.OBJECT_TYPE=? AND o.STATUS='VALID' AND o.ORACLE_MAINTAINED='Y'
                """,String.class,name,type);
        if(owners.size()!=1)throw new Failure(409,UiMessages.text("vector.oci.sdkUnavailable",
                "OCI 모델 조회 SDK가 없거나 접근할 수 없습니다. DB 관리자에게 SDK 조회 권한을 확인해 주세요."));
        return quote(owners.getFirst())+"."+quote(name);
    }
    public static String sql(String pkg,String response,String capabilities) {
        return """
                DECLARE
                  r %s;
                  result_json JSON_OBJECT_T := JSON_OBJECT_T();
                  items JSON_ARRAY_T := JSON_ARRAY_T();
                  item JSON_OBJECT_T;
                  caps JSON_ARRAY_T;
                BEGIN
                  r := %s.LIST_MODELS(compartment_id=>?,region=>?,credential_name=>?,page=>?,
                    capability=>%s('TEXT_EMBEDDINGS'),lifecycle_state=>'ACTIVE',limit=>100,
                    sort_by=>'displayName',sort_order=>'ASC');
                  IF r.status_code <> 200 OR r.status_code IS NULL THEN
                    RAISE_APPLICATION_ERROR(-20084,'OCI ListModels HTTP ' || r.status_code);
                  END IF;
                  FOR i IN 1..r.response_body.items.COUNT LOOP
                    item := JSON_OBJECT_T(); caps := JSON_ARRAY_T();
                    item.put('name',r.response_body.items(i).display_name);
                    item.put('id',r.response_body.items(i).id);
                    item.put('vendor',r.response_body.items(i).vendor);
                    item.put('version',r.response_body.items(i).version);
                    item.put('state',r.response_body.items(i).lifecycle_state);
                    item.put('type',r.response_body.items(i).l_type);
                    item.put('deprecatedAt',TO_CHAR(r.response_body.items(i).time_deprecated,'YYYY-MM-DD"T"HH24:MI:SSTZH:TZM'));
                    FOR j IN 1..r.response_body.items(i).capabilities.COUNT LOOP
                      caps.append(r.response_body.items(i).capabilities(j));
                    END LOOP;
                    item.put('capabilities',caps); items.append(item);
                  END LOOP;
                  result_json.put('items',items);
                  IF r.headers IS NOT NULL THEN result_json.put('nextPage',r.headers.get_string('opc-next-page')); END IF;
                  ? := result_json.to_clob();
                END;
                """.formatted(response,pkg,capabilities);
    }
    public Page list(Request request) {
        String statement=sql(object(PACKAGE,"PACKAGE"),object(RESPONSE,"TYPE"),object(CAPABILITIES,"TYPE"));
        return jdbc.execute((CallableStatementCreator)connection->{
            var call=connection.prepareCall(statement);
            call.setString(1,request.query().compartment());call.setString(2,request.query().region());
            call.setString(3,request.query().credential());
            call.setString(4,request.page().isEmpty()?null:request.page());call.registerOutParameter(5,Types.CLOB);
            return call;
        },(CallableStatementCallback<Page>)call->{
            call.execute();var clob=call.getClob(5);
            if(clob==null)throw new Failure(502,UiMessages.text("vector.oci.responseInvalid","OCI 모델 목록 응답 형식을 확인할 수 없습니다."));
            try {
                if(clob.length()>1_000_000)throw new Failure(502,UiMessages.text("vector.oci.responseInvalid","OCI 모델 목록 응답 형식을 확인할 수 없습니다."));
                return com.dbcompanion.model.OciEmbeddingModels.parse(clob.getSubString(1,(int)clob.length()),mapper);
            } finally {clob.free();}
        });
    }
}
