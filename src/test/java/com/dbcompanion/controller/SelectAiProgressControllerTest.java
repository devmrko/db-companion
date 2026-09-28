package com.dbcompanion.controller;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.model.*;
import com.zaxxer.hikari.HikariDataSource;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.assertj.core.api.Assertions.*;

class SelectAiProgressControllerTest {
    @Test void statusIsSessionScopedNoStoreAndDoesNotNeedADatabaseOrService(){
        var controller=new SelectAiTestController(null,null);
        assertThat(controller.progress(new MockHttpServletRequest()).getStatusCode().value()).isEqualTo(401);
        try(var session=new PoolSession(new HikariDataSource(),"LOW",()->{})){
            session.initialize(new DatabaseSession(new DatabaseInfo("APP","APP","LOW","DB"),List.of("APP")));
            session.metadata().aiTest().progress().begin(UUID.randomUUID().toString(),"EXECUTE").step(SelectAiProgress.Stage.QUERY);
            var request=new MockHttpServletRequest();request.getSession().setAttribute(PoolSession.ATTRIBUTE,session);
            var response=controller.progress(request);assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
            assertThat((List<?>)response.getBody()).hasSize(1);
            assertThat(controller.progress(new MockHttpServletRequest()).getStatusCode().value()).isEqualTo(401);
        }
    }
}
