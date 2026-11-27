package com.dbcompanion;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.controller.DdsManagementController;
import com.dbcompanion.controller.SemanticChangeController;
import com.dbcompanion.model.DdsManagement;
import com.dbcompanion.service.DdsManagementService;
import com.dbcompanion.service.SemanticChangeService;
import com.dbcompanion.repository.AppRecordRepository;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.assertj.core.api.Assertions.*;

class ServiceReadinessHttpTest {
    private MockHttpServletRequest request(PoolSession session,String verb){
        var request=new MockHttpServletRequest(verb,"/test");request.getSession().setAttribute(PoolSession.ATTRIBUTE,session);return request;
    }
    @Test void missingSemanticHistoryStorageKeepsItsActionableConflictStatus(){
        var db=new OntologyReadCacheTest();
        var records=new AppRecordRepository(db.jdbc,db.json){
            @Override public String status(String schema,String login){return "MISSING";}
        };
        var service=new SemanticChangeService(db.source,records,null,db.json);
        try(var session=db.session()){
            var response=new SemanticChangeController(service).page("APP","SOURCE","",request(session,"GET"));
            assertThat(response.getStatusCode().value()).isEqualTo(409);
            assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
            assertThat(response.getBody().toString()).doesNotContain("RDF");
        }
    }
    @Test void unexpectedSemanticHistoryFailuresRemainUnavailableWithoutLeakingDetails(){
        var db=new OntologyReadCacheTest();
        var service=new SemanticChangeService(db.source,null,null,db.json){
            @Override public Object page(PoolSession s,String schema,String kind,String name){throw new IllegalStateException("private backend detail");}
        };
        try(var session=db.session()){
            var response=new SemanticChangeController(service).page("APP","SOURCE","",request(session,"GET"));
            assertThat(response.getStatusCode().value()).isEqualTo(503);assertThat(response.getBody().toString()).doesNotContain("private backend detail");
        }
    }
    @Test void knownDdsReadPrerequisitesDifferFromUnexpectedFailuresAndUncertainWrites(){
        var db=new OntologyReadCacheTest();
        for(int code:new int[]{942,1031,904,17002}){
            var failure=new BadSqlGrammarException("test","private SQL",new SQLException("private backend detail","",code));
            var service=new DdsManagementService(db.source,null){
                @Override public DdsManagement.State state(PoolSession s){throw failure;}
                @Override public java.util.Map<String,Object> apply(PoolSession s,DdsManagement.Apply input){throw failure;}
            };
            try(var session=db.session()){
                var controller=new DdsManagementController(service);
                var read=controller.state(request(session,"GET"));
                assertThat(read.getStatusCode().value()).isEqualTo(code==17002?503:409);
                assertThat(read.getBody().toString()).doesNotContain("private SQL","private backend detail");
                assertThat(read.getHeaders().getCacheControl()).isEqualTo("no-store");
                // The same exception during a changing operation must not be reclassified as a safe prerequisite failure.
                var changingPath=controller.apply(null,request(session,"POST"));
                assertThat(changingPath.getStatusCode().value()).isEqualTo(503);
            }
        }
    }
}
