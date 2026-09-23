package com.dbcompanion.common.i18n;

import com.dbcompanion.controller.LanguageController;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@ControllerAdvice(basePackages="com.dbcompanion.controller")
public class LanguageModel {
    @ModelAttribute("languageReturn")
    public String returnTo(HttpServletRequest request) {
        return LanguageController.safeReturn(request.getRequestURI()+(request.getQueryString()==null?"":"?"+request.getQueryString()));
    }
}
