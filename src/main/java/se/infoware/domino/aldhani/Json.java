package se.infoware.domino.aldhani;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JSON reader and writer for MCP messages. Kyber bodies are carried as text. */
final class Json {
    private final String text;
    private int index;

    private Json(String text) {
        this.text = text;
    }

    static Object parse(String text) {
        Json parser = new Json(text);
        parser.skip();
        Object value = parser.value();
        parser.skip();
        if (parser.index != text.length()) {
            throw new IllegalArgumentException("Trailing data in JSON");
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw new IllegalArgumentException("Expected a JSON object");
    }

    static String string(Object value) {
        if (value instanceof String text) {
            return text;
        }
        return null;
    }

    static String write(Object value) {
        StringBuilder out = new StringBuilder();
        write(out, value);
        return out.toString();
    }

    private static void write(StringBuilder out, Object value) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String text) {
            out.append('"');
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                switch (c) {
                    case '"' -> out.append("\\\"");
                    case '\\' -> out.append("\\\\");
                    case '\b' -> out.append("\\b");
                    case '\f' -> out.append("\\f");
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
            out.append('"');
        } else if (value instanceof Number || value instanceof Boolean) {
            out.append(value);
        } else if (value instanceof Map<?, ?> map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                write(out, String.valueOf(entry.getKey()));
                out.append(':');
                write(out, entry.getValue());
            }
            out.append('}');
        } else if (value instanceof Iterable<?> list) {
            out.append('[');
            boolean first = true;
            for (Object item : list) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                write(out, item);
            }
            out.append(']');
        } else {
            throw new IllegalArgumentException("Cannot write " + value.getClass().getName());
        }
    }

    private Object value() {
        skip();
        if (index >= text.length()) {
            throw new IllegalArgumentException("Unexpected end of JSON");
        }
        char c = text.charAt(index);
        return switch (c) {
            case '{' -> object();
            case '[' -> array();
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> number();
        };
    }

    private Map<String, Object> object() {
        index++;
        Map<String, Object> map = new LinkedHashMap<>();
        skip();
        if (peek('}')) {
            index++;
            return map;
        }
        while (true) {
            skip();
            if (index >= text.length() || text.charAt(index) != '"') {
                throw new IllegalArgumentException("Expected a JSON object key");
            }
            String key = string();
            skip();
            expect(':');
            map.put(key, value());
            skip();
            if (peek('}')) {
                index++;
                return map;
            }
            expect(',');
        }
    }

    private List<Object> array() {
        index++;
        List<Object> list = new ArrayList<>();
        skip();
        if (peek(']')) {
            index++;
            return list;
        }
        while (true) {
            list.add(value());
            skip();
            if (peek(']')) {
                index++;
                return list;
            }
            expect(',');
        }
    }

    private String string() {
        index++;
        StringBuilder out = new StringBuilder();
        while (index < text.length()) {
            char c = text.charAt(index++);
            if (c == '"') {
                return out.toString();
            }
            if (c != '\\') {
                out.append(c);
                continue;
            }
            if (index >= text.length()) {
                throw new IllegalArgumentException("Bad escape");
            }
            char escaped = text.charAt(index++);
            switch (escaped) {
                case '"', '\\', '/' -> out.append(escaped);
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'u' -> {
                    if (index + 4 > text.length()) {
                        throw new IllegalArgumentException("Bad unicode escape");
                    }
                    int code = Integer.parseInt(text.substring(index, index + 4), 16);
                    out.append((char) code);
                    index += 4;
                }
                default -> throw new IllegalArgumentException("Bad escape");
            }
        }
        throw new IllegalArgumentException("Unclosed string");
    }

    private Object literal(String word, Object value) {
        if (!text.startsWith(word, index)) {
            throw new IllegalArgumentException("Expected " + word);
        }
        index += word.length();
        return value;
    }

    private Number number() {
        int start = index;
        if (peek('-')) {
            index++;
        }
        if (index >= text.length() || !Character.isDigit(text.charAt(index))) {
            throw new IllegalArgumentException("Expected a number");
        }
        while (index < text.length() && Character.isDigit(text.charAt(index))) {
            index++;
        }
        boolean fractional = false;
        if (peek('.')) {
            fractional = true;
            index++;
            while (index < text.length() && Character.isDigit(text.charAt(index))) {
                index++;
            }
        }
        if (peek('e') || peek('E')) {
            fractional = true;
            index++;
            if (peek('+') || peek('-')) {
                index++;
            }
            while (index < text.length() && Character.isDigit(text.charAt(index))) {
                index++;
            }
        }
        String raw = text.substring(start, index);
        if (fractional) {
            return Double.valueOf(raw);
        }
        return Long.valueOf(raw);
    }

    private void skip() {
        while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
            index++;
        }
    }

    private boolean peek(char c) {
        return index < text.length() && text.charAt(index) == c;
    }

    private void expect(char c) {
        if (!peek(c)) {
            throw new IllegalArgumentException("Expected '" + c + "'");
        }
        index++;
    }
}
