package com.dbcompanion.common.db;

import java.util.List;

/** Presents authenticated Oracle failures without treating wrapper text as a proven cause. */
public final class AiErrorExplanation {
    private AiErrorExplanation() {}

    public record Summary(String stage, String original, List<String> confirmed, List<String> possible) {
        public Summary { confirmed = List.copyOf(confirmed); possible = List.copyOf(possible); }
        public String display() {
            var lines = new java.util.ArrayList<String>();
            if (stage != null && !stage.isBlank()) lines.add("Failed stage: " + stage);
            if (original != null && !original.isBlank()) lines.add("Oracle original:\n" + original);
            if (!confirmed.isEmpty()) lines.add("Confirmed: " + String.join("; ", confirmed));
            if (!possible.isEmpty()) lines.add("Possible cause (not confirmed): " + String.join("; ", possible));
            return String.join("\n", lines);
        }
    }

    public static Summary explain(String stage, Throwable error) {
        String original = OracleErrorDetails.forDisplay(error);
        var confirmed = new java.util.ArrayList<String>();
        var possible = new java.util.ArrayList<String>();
        if (!original.isBlank()) confirmed.add("Oracle returned the displayed code(s)");
        if (original.contains("ORA-00942"))
            possible.add("the dictionary object may be unavailable or the login account may lack read privilege");
        if (original.contains("ORA-00904"))
            possible.add("a referenced column, attribute, or generated identifier may be invalid for this database version or object");
        if (original.contains("ORA-20004"))
            possible.add("an application or profile policy rejected the request; this alone does not prove an external object was used");
        if (original.contains("ORA-01031"))
            possible.add("the login account may not have the required privilege");
        return new Summary(stage, original, confirmed, possible);
    }
}
