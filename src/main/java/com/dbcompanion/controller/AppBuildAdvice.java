package com.dbcompanion.controller;

import java.io.IOException;
import java.util.Map;
import java.util.Properties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** Build information only; no host paths, credentials or database details. */
@ControllerAdvice
public class AppBuildAdvice {
    private final Map<String,String> build;
    public AppBuildAdvice(){
        var values=new Properties();
        var resource=new ClassPathResource("META-INF/build-info.properties");
        if(resource.exists())try(var input=resource.getInputStream()){values.load(input);}catch(IOException ex){throw new IllegalStateException("Build information unavailable",ex);}
        build=Map.of("version",values.getProperty("build.version","development"),"id",values.getProperty("build.time","local"));
    }
    @ModelAttribute("appBuild") public Map<String,String> build(){return build;}
}
