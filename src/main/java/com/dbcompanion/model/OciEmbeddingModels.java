package com.dbcompanion.model;

import com.dbcompanion.common.i18n.UiMessages;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;
import static com.dbcompanion.model.VectorSearch.*;

/** OCI catalogue only. No prompts, keys, inference or model provisioning. */
public final class OciEmbeddingModels {
    private OciEmbeddingModels() {}
    public record Query(String credential,String region,String compartment) {
        public Query {
            name(credential);
            if(region==null || !region.matches("[a-z]{2}-[a-z]+-[1-9][0-9]?"))
                throw invalid(UiMessages.text("ui.1b3dd86a1df6","OCI 리전 이름을 확인해 주세요."));
            if(compartment==null || compartment.length()>255 || !compartment.matches("ocid1\\.(compartment|tenancy)\\.oc1\\.[A-Za-z0-9.-]+"))
                throw invalid(UiMessages.text("vector.oci.compartmentInvalid","Compartment OCID를 확인해 주세요."));
        }
    }
    public record Request(Query query,String page,boolean refresh) {
        public Request {
            Objects.requireNonNull(query,"Model catalogue query required");
            page=Objects.toString(page,"");
            if(page.length()>4096 || page.chars().anyMatch(c->c<32 || c==127) || (refresh&&!page.isEmpty()))
                throw invalid(UiMessages.text("vector.oci.pageInvalid","모델 목록 페이지를 다시 조회해 주세요."));
        }
    }
    public record Model(String name,String id,String vendor,String version,String deprecatedAt) {}
    public record Page(List<Model> items,String nextPage) {
        public Page {items=List.copyOf(items);nextPage=Objects.toString(nextPage,"");}
    }
    public record Result(Page page,boolean cached) {}
    public static Page parse(String json,JsonMapper mapper) {
        var root=mapper.readTree(json);
        if(!root.path("items").isArray() || root.path("items").size()>100)
            throw new Failure(502,UiMessages.text("vector.oci.responseInvalid","OCI 모델 목록 응답 형식을 확인할 수 없습니다."));
        var items=new ArrayList<Model>();
        for(var row:root.path("items")) {
            if(!"ACTIVE".equals(row.path("state").asText()) || !"BASE".equals(row.path("type").asText()))continue;
            boolean embedding=false;for(var capability:row.path("capabilities"))if("TEXT_EMBEDDINGS".equals(capability.asText()))embedding=true;
            if(!embedding)continue;
            String value=row.path("name").asText("");
            // Display names are copied exactly, never reconstructed from vendor/version/OCID.
            if(!value.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,254}"))continue;
            items.add(new Model(value,row.path("id").asText(""),row.path("vendor").asText(""),
                    row.path("version").asText(""),row.path("deprecatedAt").asText("")));
        }
        String next=root.path("nextPage").asText("");
        if(next.length()>4096 || next.chars().anyMatch(c->c<32 || c==127))
            throw new Failure(502,UiMessages.text("vector.oci.responseInvalid","OCI 모델 목록 응답 형식을 확인할 수 없습니다."));
        return new Page(items,next);
    }
}
