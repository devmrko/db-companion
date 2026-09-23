package com.dbcompanion.common.db;

import com.dbcompanion.model.ProfileHistory.RequestValue;
import java.util.*;
import java.util.regex.Pattern;

/** A deliberately small static grammar, not a PL/SQL interpreter. Never executes audit SQL. */
public final class ProfileAuditParser {
    private ProfileAuditParser() {}
    private record Token(String kind, String text) {}
    private record Value(String text, String quality) {}
    private static final Map<String, List<String>> PARAMETERS = Map.of(
            "SET_ATTRIBUTE", List.of("PROFILE_NAME", "ATTRIBUTE_NAME", "ATTRIBUTE_VALUE"),
            "SET_ATTRIBUTES", List.of("PROFILE_NAME", "ATTRIBUTES"),
            "CREATE_PROFILE", List.of("PROFILE_NAME", "ATTRIBUTES", "STATUS", "DESCRIPTION"),
            "DROP_PROFILE", List.of("PROFILE_NAME", "FORCE"),
            "ENABLE_PROFILE", List.of("PROFILE_NAME"), "DISABLE_PROFILE", List.of("PROFILE_NAME"));
    public static List<RequestValue> parse(String sql, String binds, String packageOwner) {
        if (sql == null || sql.length() > 131072) return List.of();
        if (sql.endsWith("\0")) sql = sql.substring(0, sql.length() - 1);
        try { return new Parser(tokens(sql), binds, packageOwner).parse(); }
        catch (IllegalArgumentException ex) { return List.of(); }
    }
    private static IllegalArgumentException unknown() { return new IllegalArgumentException("Unsupported audit SQL"); }
    private static List<Token> tokens(String sql) {
        var result = new ArrayList<Token>();
        for (int i = 0; i < sql.length();) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }
            if (sql.startsWith("--", i)) { int end = sql.indexOf('\n', i); i = end < 0 ? sql.length() : end + 1; continue; }
            if (sql.startsWith("/*", i)) { int end = sql.indexOf("*/", i + 2); if (end < 0) throw unknown(); i = end + 2; continue; }
            if ((c == 'q' || c == 'Q') && i + 2 < sql.length() && sql.charAt(i + 1) == '\'') {
                char open = sql.charAt(i + 2), close = switch (open) { case '[' -> ']'; case '(' -> ')'; case '{' -> '}'; case '<' -> '>'; default -> open; };
                if (Character.isWhitespace(open) || open == '\'') throw unknown();
                int end = sql.indexOf("" + close + '\'', i + 3); if (end < 0) throw unknown();
                result.add(new Token("STRING", sql.substring(i + 3, end))); i = end + 2; continue;
            }
            if (c == '\'' || c == '"') {
                var value = new StringBuilder(); boolean closed = false; i++;
                while (i < sql.length()) {
                    char next = sql.charAt(i++);
                    if (next != c) value.append(next);
                    else if (i < sql.length() && sql.charAt(i) == c) { value.append(c); i++; }
                    else { closed = true; break; }
                }
                if (!closed) throw unknown();
                result.add(new Token(c == '\'' ? "STRING" : "ID", value.toString())); continue;
            }
            if (sql.startsWith("=>", i) || sql.startsWith(":=", i)) { result.add(new Token("SYMBOL", sql.substring(i, i + 2))); i += 2; continue; }
            if (c == ':') {
                int start = ++i;
                while (i < sql.length() && (Character.isLetterOrDigit(sql.charAt(i)) || sql.charAt(i) == '_')) i++;
                if (start == i) throw unknown();
                result.add(new Token("BIND", sql.substring(start, i))); continue;
            }
            if (Character.isLetter(c) || c == '_') {
                int start = i++;
                while (i < sql.length() && (Character.isLetterOrDigit(sql.charAt(i)) || "_$#".indexOf(sql.charAt(i)) >= 0)) i++;
                result.add(new Token("ID", sql.substring(start, i).toUpperCase(Locale.ROOT))); continue;
            }
            if (c >= '0' && c <= '9') {
                int start = i++;
                while (i < sql.length() && sql.charAt(i) >= '0' && sql.charAt(i) <= '9') i++;
                result.add(new Token("NUMBER", sql.substring(start, i))); continue;
            }
            if (".,();".indexOf(c) >= 0) { result.add(new Token("SYMBOL", String.valueOf(c))); i++; continue; }
            throw unknown();
        }
        return result;
    }
    private static final class Parser {
        private final List<Token> tokens;
        private final String binds, owner;
        private final Map<String, Value> variables = new HashMap<>();
        private int position;
        Parser(List<Token> tokens, String binds, String owner) { this.tokens = tokens; this.binds = binds; this.owner = owner; }
        boolean at(String text) { return position < tokens.size() && tokens.get(position).text().equals(text) && !tokens.get(position).kind().equals("STRING"); }
        void expect(String text) { if (!at(text)) throw unknown(); position++; }
        Token next() { if (position == tokens.size()) throw unknown(); return tokens.get(position++); }
        String identifier() { Token t = next(); if (!t.kind().equals("ID")) throw unknown(); return t.text(); }
        List<RequestValue> parse() {
            if (at("DECLARE")) {
                position++; String name = identifier();
                if (at("CLOB")) position++;
                else {
                    expect("VARCHAR2"); expect("("); Token size = next();
                    if (!size.kind().equals("NUMBER") || size.text().length() > 5) throw unknown();
                    int length = Integer.parseInt(size.text());
                    if (length < 1 || length > 32767) throw unknown();
                    expect(")");
                }
                expect(":=");
                Value value = value(); expect(";"); variables.put(name, value);
            }
            expect("BEGIN"); var result = new ArrayList<RequestValue>();
            while (!at("END")) {
                if (result.size() >= 100) throw unknown();
                String first = identifier(); expect("."); String second = identifier(); String operation;
                if (at(".")) { position++; if (!first.equals(owner) || !second.equals("DBMS_CLOUD_AI")) throw unknown(); operation = identifier(); }
                else { if (!first.equals("DBMS_CLOUD_AI")) throw unknown(); operation = second; }
                var parameters = PARAMETERS.get(operation); if (parameters == null) throw unknown();
                expect("("); var args = new LinkedHashMap<String, Value>(); boolean named = false; int ordinal = 0;
                while (!at(")")) {
                    String key;
                    if (position + 1 < tokens.size() && tokens.get(position + 1).text().equals("=>")) {
                        named = true; key = identifier(); expect("=>");
                    } else { if (named || ordinal >= parameters.size()) throw unknown(); key = parameters.get(ordinal++); }
                    if (!parameters.contains(key) || args.putIfAbsent(key, value()) != null) throw unknown();
                    if (!at(",")) break; position++;
                }
                expect(")"); expect(";");
                Value profile = args.get("PROFILE_NAME");
                // A truncated bind could name a different profile. Do not attribute it to that profile.
                if (profile == null || !"LITERAL".equals(profile.quality()) || profile.text() == null || profile.text().isBlank() || profile.text().length() > 128) throw unknown();
                String attribute; Value value;
                if (operation.equals("SET_ATTRIBUTE")) {
                    Value name = args.get("ATTRIBUTE_NAME"); value = args.get("ATTRIBUTE_VALUE");
                    if (name == null || !"LITERAL".equals(name.quality()) || name.text() == null || value == null) throw unknown();
                    attribute = name.text().toLowerCase(Locale.ROOT);
                } else if (operation.equals("CREATE_PROFILE") || operation.equals("SET_ATTRIBUTES")) {
                    attribute = "attributes (JSON)"; value = args.get("ATTRIBUTES"); if (value == null) throw unknown();
                } else { attribute = "$lifecycle"; value = new Value(operation, "LITERAL"); }
                result.add(new RequestValue(profile.text().toUpperCase(Locale.ROOT), operation, attribute, value.text(), value.quality()));
            }
            expect("END"); expect(";"); if (position != tokens.size() || result.isEmpty()) throw unknown();
            return List.copyOf(result);
        }
        Value value() {
            Token token = next();
            if (token.kind().equals("STRING")) return new Value(token.text(), "LITERAL");
            if (token.kind().equals("BIND")) return bind(token.text());
            if (token.kind().equals("ID")) {
                if (token.text().equals("NULL")) return new Value(null, "LITERAL");
                if (Set.of("TRUE", "FALSE").contains(token.text())) return new Value(token.text(), "LITERAL");
                if (variables.containsKey(token.text())) return variables.get(token.text());
            }
            throw unknown();
        }
        Value bind(String name) {
            // Only one unambiguous numbered bind is decoded. Audit headers do not prove completeness.
            if (binds == null || !name.equals("1")) return new Value(null, "UNAVAILABLE");
            var header = Pattern.compile("\\s*#1\\((\\d+)\\):", Pattern.DOTALL).matcher(binds);
            if (!header.lookingAt()) return new Value(null, "UNAVAILABLE");
            String value = binds.substring(header.end());
            if (Pattern.compile("#\\d+\\(\\d+\\):").matcher(value).find()) return new Value(null, "UNAVAILABLE");
            return new Value(value, "BIND_UNVERIFIED");
        }
    }
}
