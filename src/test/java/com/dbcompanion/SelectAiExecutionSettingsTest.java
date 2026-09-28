package com.dbcompanion;

import com.dbcompanion.common.config.SelectAiExecutionSettings;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertiesPropertySource;
import static org.assertj.core.api.Assertions.*;

class SelectAiExecutionSettingsTest {
    private AnnotationConfigApplicationContext context(String override) throws Exception {
        return context(override,null);
    }
    private AnnotationConfigApplicationContext context(String override,String generateOverride) throws Exception {
        var context=new AnnotationConfigApplicationContext();
        var properties=new Properties();
        try(var input=getClass().getResourceAsStream("/application.properties")){properties.load(input);}
        context.getEnvironment().getPropertySources().addFirst(new PropertiesPropertySource("app",properties));
        if(override!=null)context.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("test",Map.of("SELECT_AI_SQL_TIMEOUT_SECONDS",override)));
        if(generateOverride!=null)context.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("generateTest",Map.of("SELECT_AI_GENERATE_TIMEOUT_SECONDS",generateOverride)));
        context.register(SelectAiExecutionSettings.class);
        return context;
    }
    @Test void springDefaultIsFiveMinutesWithLargerRelatedBudgets() throws Exception {
        try(var context=context(null)){
            context.refresh();var settings=context.getBean(SelectAiExecutionSettings.class);
            assertThat(settings.sqlTimeoutSeconds()).isEqualTo(300);
            assertThat(settings.networkTimeoutMillis()).isEqualTo(330_000);
            assertThat(settings.transactionTimeoutSeconds()).isEqualTo(360);
            assertThat(settings.generateTimeoutSeconds()).isEqualTo(300);
            assertThat(settings.generateNetworkTimeoutMillis()).isEqualTo(330_000);
            assertThat(settings.generateTransactionTimeoutSeconds()).isEqualTo(360);
        }
    }
    @Test void environmentOverrideReachesAllThreeBudgets() throws Exception {
        try(var context=context("600")){
            context.refresh();var settings=context.getBean(SelectAiExecutionSettings.class);
            assertThat(settings.sqlTimeoutSeconds()).isEqualTo(600);
            assertThat(settings.networkTimeoutMillis()).isEqualTo(630_000);
            assertThat(settings.transactionTimeoutSeconds()).isEqualTo(660);
            assertThat(settings.generateTimeoutSeconds()).isEqualTo(300);
        }
    }
    @Test void generationOverrideIsIndependentOfSqlExecution() throws Exception {
        try(var context=context("120","600")){
            context.refresh();var settings=context.getBean(SelectAiExecutionSettings.class);
            assertThat(settings.sqlTimeoutSeconds()).isEqualTo(120);
            assertThat(settings.generateTimeoutSeconds()).isEqualTo(600);
            assertThat(settings.generateNetworkTimeoutMillis()).isEqualTo(630_000);
            assertThat(settings.generateTransactionTimeoutSeconds()).isEqualTo(660);
        }
    }
    @Test void invalidGenerationConfigurationFailsStartup() throws Exception {
        for(String value:new String[]{"0","-1","3601","2147483647","no-timeout",""}){
            try(var context=context(null,value)){
                assertThatThrownBy(context::refresh).isInstanceOf(org.springframework.beans.BeansException.class);
            }
        }
    }
    @Test void invalidConfigurationFailsStartupInsteadOfBecomingUnlimited() throws Exception {
        for(String value:new String[]{"0","-1","3601","2147483647","no-timeout",""}){
            try(var context=context(value)){
                assertThatThrownBy(context::refresh).isInstanceOf(org.springframework.beans.BeansException.class);
            }
        }
    }
    @Test void acceptedEndpointsCannotOverflowRelatedBudgets(){
        for(int seconds:new int[]{1,3600}){
            var settings=new SelectAiExecutionSettings(seconds);
            assertThat(settings.networkTimeoutMillis()).isEqualTo((seconds+30)*1000);
            assertThat(settings.transactionTimeoutSeconds()).isEqualTo(seconds+60);
            assertThat(settings.generateTimeoutSeconds()).isEqualTo(300);
            var generation=new SelectAiExecutionSettings(300,seconds);
            assertThat(generation.generateNetworkTimeoutMillis()).isEqualTo((seconds+30)*1000);
            assertThat(generation.generateTransactionTimeoutSeconds()).isEqualTo(seconds+60);
        }
    }
}
