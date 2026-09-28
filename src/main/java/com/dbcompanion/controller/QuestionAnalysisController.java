package com.dbcompanion.controller;

import com.dbcompanion.common.db.PoolSession;
import com.dbcompanion.common.i18n.UiMessages;
import com.dbcompanion.model.AiAssistant;
import com.dbcompanion.model.QuestionLanguage;
import com.dbcompanion.repository.CredentialCatalogRepository;
import com.dbcompanion.service.QuestionAnalysisService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Read-only configuration inspection. No setup mutation or automatic installation endpoint. */
@RestController
@RequestMapping("/question-analysis")
public class QuestionAnalysisController {
    private final QuestionAnalysisService service;
    public QuestionAnalysisController(QuestionAnalysisService service){this.service=service;}
    public static QuestionLanguage language(String requested,Locale locale){return QuestionLanguage.of(requested==null||requested.isBlank()?UiMessages.supported(locale).toLanguageTag():requested);}
    @GetMapping("/configuration") public ResponseEntity<?> configuration(@RequestParam(defaultValue="") String language,Locale locale,HttpServletRequest request){
        var http=request.getSession(false);var session=http==null?null:(PoolSession)http.getAttribute(PoolSession.ATTRIBUTE);
        if(session==null)return error(401,UiMessages.text("ui.b9c067f345b1","Login required"));
        try{return ResponseEntity.ok().header("Cache-Control","no-store").body(service.configuration(session,language(language,locale)));}
        catch(AiAssistant.Failure ex){return error(ex.status(),ex.getMessage());}
        catch(RuntimeException ex){return error(503,UiMessages.text("questionAnalysis.readError","Could not verify question analysis settings.")+" · "+CredentialCatalogRepository.error(ex));}
    }
    private ResponseEntity<?> error(int status,String message){return ResponseEntity.status(status).header("Cache-Control","no-store").body(Map.of("error",message));}
}
