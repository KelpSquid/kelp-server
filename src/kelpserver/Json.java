package kelpserver;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads and writes JSON. Objects become LinkedHashMaps (keeping their order), arrays ArrayLists, numbers Longs when
 * they're whole and Doubles when they're not. Writing takes the same, plus Integers, Booleans and null.
 */
public final class Json {
    private final String text;
    private int at;

    private Json(String text) {
        this.text = text;
    }

    public static Object parse(String text) {
        Json j = new Json(text);
        j.space();
        Object value = j.value();
        j.space();
        if (j.at != text.length()) throw j.error("extra text after the end");
        return value;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> array(Object value) {
        return value instanceof List<?> list ? (List<Object>) list : null;
    }

    private IllegalArgumentException error(String what) {
        return new IllegalArgumentException("bad JSON at " + at + ": " + what);
    }

    private void space() {
        while (at < text.length() && Character.isWhitespace(text.charAt(at))) at++;
    }

    private Object value() {
        if (at >= text.length()) throw error("it ends too early");
        char c = text.charAt(at);
        return switch (c) {
            case '{' -> object();
            case '[' -> array();
            case '"' -> string();
            case 't' -> word("true", Boolean.TRUE);
            case 'f' -> word("false", Boolean.FALSE);
            case 'n' -> word("null", null);
            default -> number();
        };
    }

    private Object word(String word, Object value) {
        if (!text.startsWith(word, at)) throw error("unexpected text");
        at += word.length();
        return value;
    }

    private Map<String, Object> object() {
        Map<String, Object> map = new LinkedHashMap<>();
        at++;
        space();
        if (at < text.length() && text.charAt(at) == '}') {
            at++;
            return map;
        }
        while (true) {
            space();
            if (at >= text.length() || text.charAt(at) != '"') throw error("expected a name in quotes");
            String key = string();
            space();
            if (at >= text.length() || text.charAt(at) != ':') throw error("expected :");
            at++;
            space();
            map.put(key, value());
            space();
            if (at >= text.length()) throw error("it ends too early");
            char c = text.charAt(at++);
            if (c == '}') return map;
            if (c != ',') throw error("expected , or }");
        }
    }

    private List<Object> array() {
        List<Object> list = new ArrayList<>();
        at++;
        space();
        if (at < text.length() && text.charAt(at) == ']') {
            at++;
            return list;
        }
        while (true) {
            space();
            list.add(value());
            space();
            if (at >= text.length()) throw error("it ends too early");
            char c = text.charAt(at++);
            if (c == ']') return list;
            if (c != ',') throw error("expected , or ]");
        }
    }

    private String string() {
        StringBuilder out = new StringBuilder();
        at++;
        while (true) {
            if (at >= text.length()) throw error("unfinished text");
            char c = text.charAt(at++);
            if (c == '"') return out.toString();
            if (c != '\\') {
                if (c < 0x20) throw error("control character in text");
                out.append(c);
                continue;
            }
            if (at >= text.length()) throw error("unfinished escape");
            char e = text.charAt(at++);
            switch (e) {
                case '"', '\\', '/' -> out.append(e);
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'u' -> {
                    if (at + 4 > text.length()) throw error("unfinished \\u");
                    out.append((char) Integer.parseInt(text.substring(at, at + 4), 16));
                    at += 4;
                }
                default -> throw error("unknown escape");
            }
        }
    }

    private Object number() {
        int start = at;
        if (at < text.length() && (text.charAt(at) == '-' || text.charAt(at) == '+')) at++;
        while (at < text.length() && "0123456789.eE+-".indexOf(text.charAt(at)) >= 0) at++;
        String n = text.substring(start, at);
        if (n.isEmpty()) throw error("unexpected character");
        try {
            if (n.contains(".") || n.contains("e") || n.contains("E")) return Double.parseDouble(n);
            return Long.parseLong(n);
        } catch (NumberFormatException e) {
            throw error("bad number");
        }
    }

    // ---- Writing ----

    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out);
        return out.toString();
    }

    private static void write(Object value, StringBuilder out) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String s) {
            quote(s, out);
        } else if (value instanceof Boolean || value instanceof Integer || value instanceof Long) {
            out.append(value);
        } else if (value instanceof Number n) {
            double d = n.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) out.append("null");
            else if (d == Math.rint(d) && Math.abs(d) < 1e15) out.append((long) d);
            else out.append(d);
        } else if (value instanceof Map<?, ?> map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) out.append(',');
                first = false;
                quote(String.valueOf(e.getKey()), out);
                out.append(':');
                write(e.getValue(), out);
            }
            out.append('}');
        } else if (value instanceof Iterable<?> list) {
            out.append('[');
            boolean first = true;
            for (Object o : list) {
                if (!first) out.append(',');
                first = false;
                write(o, out);
            }
            out.append(']');
        } else {
            quote(value.toString(), out);
        }
    }

    private static void quote(String s, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '<' -> out.append("\\u003c"); // so JSON put inside a web page can't end a <script>
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        out.append('"');
    }
}
