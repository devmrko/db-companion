package com.dbcompanion;

import com.dbcompanion.controller.AdminHelpController;
import com.dbcompanion.controller.AiCreationController;
import com.dbcompanion.common.db.*;
import com.dbcompanion.model.*;
import com.dbcompanion.repository.*;
import com.dbcompanion.service.AiCreationService;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.assertj.core.api.Assertions.*;

class ConversationalCreationTest {
    @Test void permissionAdviceIsReadOnlyScopedAndDoesNotPretendToDetectMissingGrants(){
        var advice=AdminHelpController.advice("APP","APP","profile");
        assertThat(advice.privilegeStatus()).isEqualTo("NOT_CHECKED");
        assertThat(advice.instructions()).extracting(AdminHelpController.Instruction::sql).containsExactly("GRANT EXECUTE ON DBMS_CLOUD_AI TO \"APP\";");
        assertThat(AdminHelpController.advice("ADMIN","APP","semantic").instructions()).isEmpty();
        assertThat(AdminHelpController.advice("ADMIN","APP","network").link()).isEqualTo("/db/accounts#ai-network");
        assertThatThrownBy(()->AdminHelpController.advice("APP","OTHER","profile")).isInstanceOf(SecurityException.class);
        for(String target:List.of("A;DROP USER X","A\"","A.B","a"))assertThatThrownBy(()->AdminHelpController.advice("ADMIN",target,"profile")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->AdminHelpController.advice("ADMIN","APP","unknown")).isInstanceOf(IllegalArgumentException.class);
        assertThat(new AdminHelpController().guidance("profile","APP",new MockHttpServletRequest()).getStatusCode().value()).isEqualTo(401);
    }
    @Test void newProfileChoicesUseExistingRepositoryWithoutAnExistingProfileAndRejectForeignScopes(){
        var db=new OntologyReadCacheTest();var calls=new ArrayList<String>();
        var repository=new AiCreationRepository(db.jdbc,null,null,null,null,null,null){
            @Override public ProfileEdit.ObjectChoices profileObjects(String owner,String filter){calls.add(owner+":"+filter);return new ProfileEdit.ObjectChoices(List.of(new ProfileEdit.ObjectChoice("T","TABLE")),false);}
            @Override public List<String> profileSuggestions(String attribute,String provider){calls.add(attribute+":"+provider);return List.of(attribute);}
        };
        var service=new AiCreationService(db.source,repository,null,db.json);
        try(var session=db.session()){
            var result=service.profileOptions(session,"APP","APP","T","oci");assertThat(result.objects().objects()).hasSize(1);assertThat(calls).containsExactly("APP:T","model:oci","region:oci");
            calls.clear();
            assertThatThrownBy(()->service.profileOptions(session,"APP","NOT_VISIBLE","","oci")).isInstanceOf(com.dbcompanion.common.exception.MetadataEditException.class);
            assertThatThrownBy(()->service.profileOptions(session,"APP","APP","x".repeat(129),"oci")).isInstanceOf(com.dbcompanion.common.exception.MetadataEditException.class);
            assertThatThrownBy(()->service.profileOptions(session,"OTHER","APP","","oci")).isInstanceOf(com.dbcompanion.common.exception.MetadataEditException.class);
            assertThat(calls).isEmpty();
            var request=new MockHttpServletRequest();request.getSession().setAttribute(PoolSession.ATTRIBUTE,session);
            assertThat(new AiCreationController(service).profileOptions("APP","NOT_VISIBLE","","oci",request).getStatusCode().value()).isEqualTo(403);
        }
    }
}
