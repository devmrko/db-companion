package com.dbcompanion.service;

import com.dbcompanion.common.i18n.UiMessages;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/** Produces local agent instructions only; has no database or AI dependency. */
@Service
public class ScenarioDataService {
    public static final List<String> PRESETS = List.of("commerce", "logistics", "hospital", "school", "public", "custom");
    private final TnsCatalogService catalog;
    private final String template;
    private final JsonMapper json = new JsonMapper();

    public ScenarioDataService(TnsCatalogService catalog) throws IOException {
        this.catalog = catalog;
        template = new ClassPathResource("scenario/prompt.md").getContentAsString(StandardCharsets.UTF_8);
    }

    public record Input(String walletId, String tnsAlias, String preset, String description,
                        long rows, String startDate, String endDate, String username, String prefix) {}
    public record Config(int version, String walletPath, String tnsAlias, String preset, String description,
                         long rows, String startDate, String endDate, String username, String prefix, String outputDirectory) {}
    public record Output(String prompt, String config) {}

    public Output generate(Input input) {
        if (input == null || input.preset() == null || !PRESETS.contains(input.preset()) || input.rows() < 1 || input.rows() > 10_000_000)
            throw invalid();
        String description = input.description() == null ? "" : input.description().strip();
        if (description.length() > 2000 || description.codePoints().anyMatch(Character::isISOControl)
                || ("custom".equals(input.preset()) && description.isBlank())) throw invalid();
        String username = identifier(input.username(), "[A-Z][A-Z0-9_]{0,29}");
        if (List.of("SYS", "SYSTEM", "ADMIN", "PUBLIC").contains(username)) throw invalid();
        String prefix = identifier(input.prefix(), "[A-Z][A-Z0-9_]{0,14}_");
        try {
            var start = LocalDate.parse(input.startDate());
            var end = LocalDate.parse(input.endDate());
            if (start.getYear() < 1900 || end.getYear() > 2100 || end.isBefore(start)) throw invalid();
        } catch (java.time.DateTimeException | NullPointerException ex) { throw invalid(); }
        catalog.validate(input.walletId(), input.tnsAlias());
        var wallet = catalog.wallet(input.walletId());
        var config = new Config(1, wallet.path().toString(), input.tnsAlias(), input.preset(), description,
                input.rows(), input.startDate(), input.endDate(), username, prefix, "scenario-results/" + prefix);
        return new Output(template, json.writerWithDefaultPrettyPrinter().writeValueAsString(config));
    }

    private static String identifier(String value, String pattern) {
        if (value == null || !value.toUpperCase(Locale.ROOT).matches(pattern)) throw invalid();
        return value.toUpperCase(Locale.ROOT);
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException(UiMessages.text("scenario.invalid", "입력값과 접두어 형식을 확인해 주세요."));
    }
}
