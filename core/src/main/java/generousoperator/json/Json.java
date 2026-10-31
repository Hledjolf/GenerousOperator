package generousoperator.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON reader and writer, so the core needs no library. Objects read as {@code LinkedHashMap<String,
 * Object>} (key order kept), arrays as {@code ArrayList<Object>}, numbers as {@code Long} when they have no
 * fraction or exponent and {@code Double} otherwise, and {@code true}, {@code false} and {@code null} as
 * themselves.
 */
public final class Json {
    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    /** Parses one JSON value. Throws {@link JsonException} with the position on bad input. */
    public static Object parse(String text) {
        Json p = new Json(text);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i != p.s.length()) throw p.error("unexpected text after the value");
        return v;
    }

    private Object value() {
        if (i >= s.length()) throw error("unexpected end of input");
        char c = s.charAt(i);
        switch (c) {
            case '{': return object();
            case '[': return array();
            case '"': return string();
            case 't': return word("true", Boolean.TRUE);
            case 'f': return word("false", Boolean.FALSE);
            case 'n': return word("null", null);
            default:
                if (c == '-' || (c >= '0' && c <= '9')) return number();
                throw error("unexpected character '" + c + "'");
        }
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++;
        ws();
        if (peek('}')) { i++; return m; }
        while (true) {
            ws();
            if (!peek('"')) throw error("expected a key in quotes");
            String k = string();
            ws();
            expect(':');
            ws();
            if (m.containsKey(k)) throw error("duplicate key \"" + k + "\"");
            m.put(k, value());
            ws();
            if (peek(',')) { i++; continue; }
            expect('}');
            return m;
        }
    }

    private List<Object> array() {
        List<Object> a = new ArrayList<>();
        i++;
        ws();
        if (peek(']')) { i++; return a; }
        while (true) {
            ws();
            a.add(value());
            ws();
            if (peek(',')) { i++; continue; }
            expect(']');
            return a;
        }
    }

    private String string() {
        i++;
        StringBuilder b = new StringBuilder();
        while (true) {
            if (i >= s.length()) throw error("unterminated string");
            char c = s.charAt(i++);
            if (c == '"') return b.toString();
            if (c < 0x20) throw error("control character in string");
            if (c != '\\') { b.append(c); continue; }
            if (i >= s.length()) throw error("unterminated string");
            char e = s.charAt(i++);
            switch (e) {
                case '"': b.append('"'); break;
                case '\\': b.append('\\'); break;
                case '/': b.append('/'); break;
                case 'b': b.append('\b'); break;
                case 'f': b.append('\f'); break;
                case 'n': b.append('\n'); break;
                case 'r': b.append('\r'); break;
                case 't': b.append('\t'); break;
                case 'u':
                    if (i + 4 > s.length()) throw error("bad \\u escape");
                    try {
                        b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    } catch (NumberFormatException ex) {
                        throw error("bad \\u escape");
                    }
                    i += 4;
                    break;
                default: throw error("bad escape \\" + e);
            }
        }
    }

    private Object number() {
        int start = i;
        if (peek('-')) i++;
        while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
        boolean real = false;
        if (peek('.')) { real = true; i++; while (i < s.length() && Character.isDigit(s.charAt(i))) i++; }
        if (peek('e') || peek('E')) {
            real = true;
            i++;
            if (peek('+') || peek('-')) i++;
            while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
        }
        String t = s.substring(start, i);
        try {
            return real ? (Object) Double.parseDouble(t) : (Object) Long.parseLong(t);
        } catch (NumberFormatException ex) {
            throw error("bad number " + t);
        }
    }

    private Object word(String w, Object v) {
        if (!s.startsWith(w, i)) throw error("unexpected word");
        i += w.length();
        return v;
    }

    private boolean peek(char c) {
        return i < s.length() && s.charAt(i) == c;
    }

    private void expect(char c) {
        if (!peek(c)) throw error("expected '" + c + "'");
        i++;
    }

    private void ws() {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++;
            else break;
        }
    }

    private JsonException error(String what) {
        int line = 1, col = 1;
        for (int k = 0; k < i && k < s.length(); k++) {
            if (s.charAt(k) == '\n') { line++; col = 1; } else col++;
        }
        return new JsonException(what + " at line " + line + ", column " + col);
    }

    /** Writes a value (the same types {@link #parse} returns, plus any {@code Number}) as compact JSON. */
    public static String write(Object v) {
        StringBuilder b = new StringBuilder();
        write(b, v, -1);
        return b.toString();
    }

    /** The same, indented two spaces per level, for files people edit by hand. Empty objects and lists stay {@code {}} and {@code []}. */
    public static String pretty(Object v) {
        StringBuilder b = new StringBuilder();
        write(b, v, 0);
        return b.toString();
    }

    /** {@code indent} is the current depth, or -1 for compact output. */
    private static void write(StringBuilder b, Object v, int indent) {
        if (v == null) { b.append("null"); return; }
        if (v instanceof String) { quote(b, (String) v); return; }
        if (v instanceof Boolean || v instanceof Long || v instanceof Integer) { b.append(v); return; }
        if (v instanceof Number) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) b.append("null"); else b.append(d);
            return;
        }
        int inner = indent < 0 ? -1 : indent + 1;
        if (v instanceof Map) {
            b.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                if (!first) b.append(',');
                first = false;
                newline(b, inner);
                quote(b, String.valueOf(e.getKey()));
                b.append(indent < 0 ? ":" : ": ");
                write(b, e.getValue(), inner);
            }
            if (!first) newline(b, indent);
            b.append('}');
            return;
        }
        if (v instanceof List) {
            b.append('[');
            boolean first = true;
            for (Object o : (List<?>) v) {
                if (!first) b.append(',');
                first = false;
                newline(b, inner);
                write(b, o, inner);
            }
            if (!first) newline(b, indent);
            b.append(']');
            return;
        }
        quote(b, v.toString());
    }

    private static void newline(StringBuilder b, int indent) {
        if (indent < 0) return;
        b.append('\n');
        for (int k = 0; k < indent; k++) b.append("  ");
    }

    private static void quote(StringBuilder b, String s) {
        b.append('"');
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c);
            }
        }
        b.append('"');
    }
}
