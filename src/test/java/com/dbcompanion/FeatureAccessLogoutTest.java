package com.dbcompanion;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.common.db.SessionDataSource;
import com.dbcompanion.controller.FeatureAccessController;
import com.dbcompanion.service.AuditHistoryService;
import com.dbcompanion.service.DeepDataSecurityService;
import com.zaxxer.hikari.HikariDataSource;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import static org.junit.jupiter.api.Assertions.*;

class FeatureAccessLogoutTest {
    private MockHttpServletRequest request(MockHttpSession http){
        var request=new MockHttpServletRequest();request.setSession(http);return request;
    }
    @Test void invalidatedBeforeOwnerReadIsUnauthenticated(){
        var http=new MockHttpSession(){
            @Override public Object getAttribute(String name){throw new IllegalStateException("Session already invalidated");}
        };
        assertEquals(401,new FeatureAccessController(null,null).access(request(http)).getStatusCode().value());
    }
    @Test void logoutBetweenOwnerAndCacheReadIsUnauthenticated(){
        try(var owner=new PoolSession(new HikariDataSource(),"TEST",()->{})){
            var http=new MockHttpSession(){
                @Override public Object getAttribute(String name){
                    if(name.equals(FeatureAccessController.class.getName()))throw new IllegalStateException("Session already invalidated");
                    return super.getAttribute(name);
                }
            };
            http.setAttribute(PoolSession.ATTRIBUTE,owner);
            assertEquals(401,new FeatureAccessController(null,null).access(request(http)).getStatusCode().value());
        }
    }
    @Test void logoutDuringCapabilityReadDoesNotPublishAnExpiredSnapshot(){
        try(var owner=new PoolSession(new HikariDataSource(),"TEST",()->{})){
            var http=new MockHttpSession(){
                @Override public void setAttribute(String name,Object value){
                    if(isInvalid())throw new IllegalStateException("Session already invalidated");
                    super.setAttribute(name,value);
                }
            };
            http.setAttribute(PoolSession.ATTRIBUTE,owner);
            var request=request(http);
            var dds=new DeepDataSecurityService(new SessionDataSource(),null){
                @Override public Map<String,String> capabilities(PoolSession session){http.invalidate();return Map.of();}
            };
            var result=new FeatureAccessController(dds,new AuditHistoryService(null,null)).access(request);
            assertEquals(401,result.getStatusCode().value());
            assertEquals(Map.of("error","Login required"),result.getBody());
        }
    }
}
