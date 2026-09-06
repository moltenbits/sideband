package com.moltenbits.sideband.install;

import java.util.List;
import java.util.Map;

/** Two-space indented JSON for files people edit by hand, such as Claude Code settings. */
final class PrettyJson {

    private PrettyJson() {
    }

    static String render(Object value) {
        StringBuilder out = new StringBuilder();
        write(out, value, 0);
        return out.toString();
    }

    private static void write(StringBuilder out, Object value, int depth) {
        switch (value) {
            case null -> out.append("null");
            case Map<?, ?> map -> {
                if (map.isEmpty()) {
                    out.append("{}");
                    return;
                }
                out.append("{\n");
                int i = 0;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    indent(out, depth + 1).append(quote(String.valueOf(entry.getKey()))).append(": ");
                    write(out, entry.getValue(), depth + 1);
                    out.append(++i < map.size() ? ",\n" : "\n");
                }
                indent(out, depth).append('}');
            }
            case List<?> list -> {
                if (list.isEmpty()) {
                    out.append("[]");
                    return;
                }
                out.append("[\n");
                for (int i = 0; i < list.size(); i++) {
                    indent(out, depth + 1);
                    write(out, list.get(i), depth + 1);
                    out.append(i + 1 < list.size() ? ",\n" : "\n");
                }
                indent(out, depth).append(']');
            }
            case String s -> out.append(quote(s));
            case Boolean b -> out.append(b);
            case Number n -> out.append(n);
            default -> out.append(quote(value.toString()));
        }
    }

    private static StringBuilder indent(StringBuilder out, int depth) {
        return out.append("  ".repeat(depth));
    }

    static String quote(String s) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
