package io.meridian.fraud.domain.expr;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Tokeniser for {@link RuleExpression}. */
final class Lexer {
    enum Kind { NUMBER, STRING, IDENT, KEYWORD, OP, EOF }

    record Token(Kind kind, String text, Object value, int pos) {
        boolean is(String s) {
            return (kind == Kind.OP || kind == Kind.KEYWORD) && text.equals(s);
        }
    }

    static final Set<String> KEYWORDS = Set.of("and", "or", "not", "in", "between", "true", "false", "null");
    private static final List<String> OPS = List.of("==", "!=", "<=", ">=", "&&", "||", "..",
            "<", ">", "!", "+", "-", "*", "/", "%", "(", ")", "[", "]", ",");

    private Lexer() {}

    static List<Token> tokens(String src) {
        List<Token> out = new ArrayList<>();
        int i = 0;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            int start = i;
            if (Character.isDigit(c)) {
                while (i < n && Character.isDigit(src.charAt(i))) {
                    i++;
                }
                // a fraction needs a digit after the dot, so that "1..5" lexes as 1 .. 5
                if (i + 1 < n && src.charAt(i) == '.' && Character.isDigit(src.charAt(i + 1))) {
                    i++;
                    while (i < n && Character.isDigit(src.charAt(i))) {
                        i++;
                    }
                }
                String t = src.substring(start, i);
                out.add(new Token(Kind.NUMBER, t, new BigDecimal(t), start));
                continue;
            }
            if (c == '\'' || c == '"') {
                StringBuilder sb = new StringBuilder();
                i++;
                boolean closed = false;
                while (i < n) {
                    char d = src.charAt(i);
                    if (d == '\\' && i + 1 < n) {
                        sb.append(src.charAt(i + 1));
                        i += 2;
                        continue;
                    }
                    if (d == c) {
                        closed = true;
                        i++;
                        break;
                    }
                    sb.append(d);
                    i++;
                }
                if (!closed) {
                    throw new ExpressionException(start, "unterminated string");
                }
                out.add(new Token(Kind.STRING, sb.toString(), sb.toString(), start));
                continue;
            }
            if (Character.isLetter(c) || c == '_') {
                while (i < n) {
                    char d = src.charAt(i);
                    boolean dotIdent = d == '.' && i + 1 < n && (Character.isLetter(src.charAt(i + 1)) || src.charAt(i + 1) == '_');
                    if (Character.isLetterOrDigit(d) || d == '_' || dotIdent) {
                        i++;
                    } else {
                        break;
                    }
                }
                String t = src.substring(start, i);
                String lower = t.toLowerCase(java.util.Locale.ROOT);
                if (KEYWORDS.contains(lower)) {
                    out.add(new Token(Kind.KEYWORD, lower, null, start));
                } else {
                    out.add(new Token(Kind.IDENT, t, null, start));
                }
                continue;
            }
            String op = null;
            for (String o : OPS) {
                if (src.startsWith(o, i)) {
                    op = o;
                    break;
                }
            }
            if (op == null) {
                throw new ExpressionException(i, "unexpected character '" + c + "'");
            }
            out.add(new Token(Kind.OP, op, null, start));
            i += op.length();
        }
        out.add(new Token(Kind.EOF, "", null, n));
        return out;
    }
}
