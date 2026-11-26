package com.dbcompanion.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Compatibility for bookmarks from the initial scenario-data preview. */
@Controller
public class DemoDataRedirectController {
    @GetMapping("/db/scenario-data")
    public String legacyPage() { return "redirect:/db/demo-data"; }
}
