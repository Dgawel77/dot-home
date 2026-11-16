package dev.gawel.dothome.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small recursive-descent JSON parser. Objects, arrays, strings, Doubles, Booleans, null. */
public final class MiniJson {
    private final String s;
    private int i, depth;
    private static final int MAX_DEPTH = 64;

    private MiniJson(String s) { this.s = s; }

    public static Object parse(String s) throws IllegalArgumentException {
        if (s == null) throw new IllegalArgumentException("null input");
        MiniJson p = new MiniJson(s);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i != s.length()) throw p.err("trailing data");
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object o) { return o instanceof Map ? (Map<String, Object>) o : null; }

    @SuppressWarnings("unchecked")
    public static List<Object> arr(Object o) { return o instanceof List ? (List<Object>) o : null; }

    public static double num(Object o, double def) { return o instanceof Double ? (Double) o : def; }

    public static String str(Object o, String def) { return o instanceof String ? (String) o : def; }

    private IllegalArgumentException err(String m) { return new IllegalArgumentException(m + " at " + i); }

    private void ws() {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++; else break;
        }
    }

    private Object value() {
        if (i >= s.length()) throw err("unexpected end");
        char c = s.charAt(i);
        switch (c) {
            case '{': return object();
            case '[': return array();
            case '"': return string();
            case 't': lit("true"); return Boolean.TRUE;
            case 'f': lit("false"); return Boolean.FALSE;
            case 'n': lit("null"); return null;
            default:
                if (c == '-' || (c >= '0' && c <= '9')) return number();
                throw err("unexpected '" + c + "'");
        }
    }

    private void lit(String w) {
        if (!s.startsWith(w, i)) throw err("bad literal");
        i += w.length();
    }

    private void enter() {
        if (++depth > MAX_DEPTH) throw err("nesting too deep");
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<>();
        enter();
        i++;
        ws();
        if (peek() == '}') { i++; depth--; return m; }
        while (true) {
            ws();
            if (peek() != '"') throw err("expected key");
            String k = string();
            ws();
            if (peek() != ':') throw err("expected ':'");
            i++;
            ws();
            m.put(k, value());
            ws();
            char c = peek();
            i++;
            if (c == '}') { depth--; return m; }
            if (c != ',') throw err("expected ',' or '}'");
        }
    }

    private List<Object> array() {
        List<Object> l = new ArrayList<>();
        enter();
        i++;
        ws();
        if (peek() == ']') { i++; depth--; return l; }
        while (true) {
            ws();
            l.add(value());
            ws();
            char c = peek();
            i++;
            if (c == ']') { depth--; return l; }
            if (c != ',') throw err("expected ',' or ']'");
        }
    }

    private char peek() {
        if (i >= s.length()) throw err("unexpected end");
        return s.charAt(i);
    }

    private String string() {
        i++;
        StringBuilder b = new StringBuilder();
        while (true) {
            if (i >= s.length()) throw err("unterminated string");
            char c = s.charAt(i++);
            if (c == '"') return b.toString();
            if (c != '\\') { b.append(c); continue; }
            if (i >= s.length()) throw err("unterminated escape");
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
                    if (i + 4 > s.length()) throw err("bad \\u escape");
                    try {
                        b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    } catch (NumberFormatException ex) {
                        throw err("bad \\u escape");
                    }
                    i += 4;
                    break;
                default: throw err("bad escape");
            }
        }
    }

    private Double number() {
        int st = i;
        if (peek() == '-') i++;
        while (i < s.length() && "0123456789.eE+-".indexOf(s.charAt(i)) >= 0) i++;
        try {
            return Double.valueOf(s.substring(st, i));
        } catch (NumberFormatException e) {
            throw err("bad number");
        }
    }
}
