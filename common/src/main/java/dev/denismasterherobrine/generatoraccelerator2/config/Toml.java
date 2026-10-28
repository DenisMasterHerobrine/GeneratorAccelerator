package dev.denismasterherobrine.generatoraccelerator2.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The part of TOML the config files use: tables, dotted table headers, comments and scalar values (booleans, integers,
 * floats, strings). Values are flattened to their full key path. Integers parse as {@link Long}, floats as {@link Double}.
 * Any other value on a line is kept out of the result, e.g. arrays, inline tables and multi-line strings. That entry then
 * falls back to its default.
 */
final class Toml {
    private Toml() {}

    static Map<List<String>, Object> parse(List<String> lines) {
        Map<List<String>, Object> out = new LinkedHashMap<>();
        List<String> table = List.of();
        for (String raw : lines) {
            String line = stripComment(raw).strip();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("[")) {
                if (line.startsWith("[[") || !line.endsWith("]")) {
                    continue;
                }
                table = splitKey(line.substring(1, line.length() - 1));
                continue;
            }
            int eq = indexOutsideQuotes(line, '=');
            if (eq <= 0) {
                continue;
            }
            List<String> key = new ArrayList<>(table);
            key.addAll(splitKey(line.substring(0, eq)));
            Object value = parseValue(line.substring(eq + 1).strip());
            if (value != null) {
                out.put(List.copyOf(key), value);
            }
        }
        return out;
    }

    private static String stripComment(String line) {
        int hash = indexOutsideQuotes(line, '#');
        return hash < 0 ? line : line.substring(0, hash);
    }

    private static int indexOutsideQuotes(String s, char c) {
        char quote = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (quote != 0) {
                if (ch == '\\' && quote == '"') {
                    i++;
                } else if (ch == quote) {
                    quote = 0;
                }
            } else if (ch == '"' || ch == '\'') {
                quote = ch;
            } else if (ch == c) {
                return i;
            }
        }
        return -1;
    }

    private static List<String> splitKey(String key) {
        List<String> parts = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < key.length(); i++) {
            char ch = key.charAt(i);
            if (quote != 0) {
                if (ch == quote) {
                    quote = 0;
                } else {
                    cur.append(ch);
                }
            } else if (ch == '"' || ch == '\'') {
                quote = ch;
            } else if (ch == '.') {
                parts.add(cur.toString().strip());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        parts.add(cur.toString().strip());
        return parts;
    }

    private static Object parseValue(String v) {
        if (v.equals("true")) {
            return Boolean.TRUE;
        }
        if (v.equals("false")) {
            return Boolean.FALSE;
        }
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            return unescape(v.substring(1, v.length() - 1));
        }
        if (v.length() >= 2 && v.startsWith("'") && v.endsWith("'")) {
            return v.substring(1, v.length() - 1);
        }
        String n = v.replace("_", "");
        try {
            if (n.startsWith("0x")) {
                return Long.parseLong(n.substring(2), 16);
            }
            return Long.parseLong(n);
        } catch (NumberFormatException ignored) {
            // not an integer
        }
        try {
            return switch (n) {
                case "inf", "+inf" -> Double.POSITIVE_INFINITY;
                case "-inf" -> Double.NEGATIVE_INFINITY;
                case "nan", "+nan", "-nan" -> Double.NaN;
                default -> Double.parseDouble(n);
            };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String unescape(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch != '\\' || i + 1 >= s.length()) {
                out.append(ch);
                continue;
            }
            char e = s.charAt(++i);
            switch (e) {
                case 'n' -> out.append('\n');
                case 't' -> out.append('\t');
                case 'r' -> out.append('\r');
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'u' -> {
                    out.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                    i += 4;
                }
                default -> out.append(e);
            }
        }
        return out.toString();
    }

    static String format(Object value) {
        if (value instanceof String s) {
            StringBuilder out = new StringBuilder("\"");
            for (char ch : s.toCharArray()) {
                switch (ch) {
                    case '"' -> out.append("\\\"");
                    case '\\' -> out.append("\\\\");
                    case '\n' -> out.append("\\n");
                    case '\t' -> out.append("\\t");
                    default -> out.append(ch);
                }
            }
            return out.append('"').toString();
        }
        return String.valueOf(value);
    }

    /** The file for a spec and its values. Tables follow definition order. Entries follow their comments, indented by depth. */
    static List<String> write(ConfigSpec spec) {
        List<String> out = new ArrayList<>();
        List<String> openTable = List.of();
        for (ConfigSpec.ConfigValue<?> value : spec.values()) {
            List<String> table = value.getPath().subList(0, value.getPath().size() - 1);
            if (!table.equals(openTable)) {
                // A header for each table level not open yet, including a parent with no entries of its own, so its comment is kept.
                for (int depth = commonPrefix(openTable, table) + 1; depth <= table.size(); depth++) {
                    List<String> header = table.subList(0, depth);
                    String indent = "\t".repeat(depth - 1);
                    if (!out.isEmpty()) {
                        out.add("");
                    }
                    for (String c : spec.tableComment(header)) {
                        out.add(indent + "#" + c);
                    }
                    out.add(indent + "[" + String.join(".", header) + "]");
                }
                openTable = table;
            }
            String indent = "\t".repeat(table.size());
            for (String c : value.comment()) {
                for (String line : c.split("\n", -1)) {
                    out.add(indent + "#" + line);
                }
            }
            out.add(indent + value.getPath().get(value.getPath().size() - 1) + " = " + format(value.get()));
        }
        out.add("");
        return out;
    }

    private static int commonPrefix(List<String> a, List<String> b) {
        int n = 0;
        while (n < a.size() && n < b.size() && a.get(n).equals(b.get(n))) {
            n++;
        }
        return n;
    }
}
