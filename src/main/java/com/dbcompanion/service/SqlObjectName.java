package com.dbcompanion.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Parses only local Oracle identifiers, not SQL expressions or calls. */
public final class SqlObjectName {
    private static final Pattern PART = Pattern.compile("\\s*(?:\"((?:[^\"\\x00]|\"\")+)\"|([\\p{L}][\\p{L}\\p{N}_$#]*))\\s*");
    private SqlObjectName() {}

    public static List<String> parts(String value) {
        if (value == null || value.isBlank() || value.length() > 800) throw new IllegalArgumentException("Invalid routine name");
        var result = new ArrayList<String>();
        int offset = 0;
        while (offset < value.length()) {
            var matcher = PART.matcher(value).region(offset, value.length());
            if (!matcher.lookingAt()) throw new IllegalArgumentException("Invalid routine name");
            result.add(matcher.group(1) == null ? matcher.group(2).toUpperCase(Locale.ROOT)
                    : matcher.group(1).replace("\"\"", "\""));
            offset = matcher.end();
            if (offset == value.length()) break;
            if (value.charAt(offset) != '.' || ++offset == value.length()) throw new IllegalArgumentException("Invalid routine name");
        }
        if (result.size() > 3) throw new IllegalArgumentException("Invalid routine name");
        return List.copyOf(result);
    }
}
