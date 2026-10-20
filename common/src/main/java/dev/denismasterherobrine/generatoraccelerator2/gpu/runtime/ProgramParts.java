package dev.denismasterherobrine.generatoraccelerator2.gpu.runtime;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A generated program's kernels, and its source with only some of them. The other kernels are cut out and nothing else changes. The
 * driver drops the helpers no kept kernel calls, so a part builds in about the time of its own kernels. With fewer callers in the
 * module the driver may inline a helper it kept out of line before. Device time stayed the same or dropped slightly (vanilla 6.5%,
 * Terralith 1-5% less per chunk).
 */
public final class ProgramParts {
    private ProgramParts() {}

    private static final Pattern KERNEL = Pattern.compile("__kernel\\s+(?:__attribute__\\s*\\(\\((?:[^()]|\\([^()]*\\))*\\)\\)\\s*)?void\\s+(\\w+)\\s*\\(");

    /** One kernel's definition: its name and the source range from {@code __kernel} to its closing brace. */
    record Span(String name, int start, int end) {}

    static List<Span> spans(String src) {
        List<Span> out = new ArrayList<>();
        Matcher m = KERNEL.matcher(src);
        int from = 0;
        while (from < src.length() && m.find(from)) {
            int start = m.start();
            if (inComment(src, start)) {
                from = m.end();
                continue;
            }
            int params = skipGroup(src, m.end() - 1, '(', ')');
            int open = src.indexOf('{', params);
            if (open < 0) {
                throw new IllegalArgumentException("kernel " + m.group(1) + " has no body");
            }
            int end = skipGroup(src, open, '{', '}');
            out.add(new Span(m.group(1), start, end));
            from = end;
        }
        return out;
    }

    /** The names of the kernels {@code src} defines, in order. */
    public static List<String> names(String src) {
        return spans(src).stream().map(Span::name).toList();
    }

    /** {@code src} without the kernels {@code keep} does not name. */
    public static String only(String src, Collection<String> keep) {
        StringBuilder out = new StringBuilder(src.length());
        int at = 0;
        for (Span s : spans(src)) {
            if (!keep.contains(s.name())) {
                out.append(src, at, s.start());
                at = s.end();
            }
        }
        return out.append(src, at, src.length()).toString();
    }

    /** The index just past the bracket that closes the one at {@code open}. Comments and literals are skipped. */
    private static int skipGroup(String src, int open, char in, char out) {
        int depth = 0;
        int n = src.length();
        for (int i = open; i < n; i++) {
            char c = src.charAt(i);
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                int nl = src.indexOf('\n', i);
                i = nl < 0 ? n : nl;
            } else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                int close = src.indexOf("*/", i + 2);
                i = close < 0 ? n : close + 1;
            } else if (c == '"' || c == '\'') {
                i++;
                while (i < n && src.charAt(i) != c) {
                    i += src.charAt(i) == '\\' ? 2 : 1;
                }
            } else if (c == in) {
                depth++;
            } else if (c == out && --depth == 0) {
                return i + 1;
            }
        }
        throw new IllegalArgumentException("unbalanced '" + in + "' at " + open);
    }

    /** Whether {@code at} is inside a line comment. Kernels named in a comment are not kernels. */
    private static boolean inComment(String src, int at) {
        int line = src.lastIndexOf('\n', at) + 1;
        int slash = src.indexOf("//", line);
        return slash >= 0 && slash < at;
    }
}
