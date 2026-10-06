package io.meridian.fraud.domain.expr;

import io.meridian.fraud.domain.expr.Lexer.Kind;
import io.meridian.fraud.domain.expr.Lexer.Token;
import io.meridian.fraud.domain.expr.RuleExpression.Node;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Recursive-descent parser with explicit node and depth budgets. */
final class Parser {
    private final List<Token> tokens;
    private int pos;
    private int nodes;
    private int depth;
    final Set<String> identifiers = new LinkedHashSet<>();
    final Set<String> lists = new LinkedHashSet<>();

    Parser(List<Token> tokens) {
        this.tokens = tokens;
    }

    Node parse() {
        Node n = or();
        if (peek().kind() != Kind.EOF) {
            throw new ExpressionException(peek().pos(), "unexpected '" + peek().text() + "'");
        }
        return n;
    }

    private Node or() {
        enter();
        Node l = and();
        while (peek().is("||") || peek().is("or")) {
            next();
            l = count(new RuleExpression.Or(l, and()));
        }
        leave();
        return l;
    }

    private Node and() {
        Node l = not();
        while (peek().is("&&") || peek().is("and")) {
            next();
            l = count(new RuleExpression.And(l, not()));
        }
        return l;
    }

    private Node not() {
        if (peek().is("!") || (peek().is("not") && !peekAt(1).is("in"))) {
            next();
            enter();
            Node inner = not();
            leave();
            return count(new RuleExpression.Not(inner));
        }
        return comparison();
    }

    private Node comparison() {
        Node l = additive();
        Token t = peek();
        if (t.kind() == Kind.OP && List.of("==", "!=", "<", "<=", ">", ">=").contains(t.text())) {
            next();
            return count(new RuleExpression.Compare(t.text(), l, additive()));
        }
        boolean negated = false;
        if (t.is("not") && peekAt(1).is("in")) {
            next();
            negated = true;
            t = peek();
        }
        if (t.is("in")) {
            next();
            Node container = additive();
            if (peek().is("..")) {
                next();
                return count(new RuleExpression.Range(l, container, additive(), negated));
            }
            return count(new RuleExpression.In(l, container, negated));
        }
        if (negated) {
            throw new ExpressionException(t.pos(), "expected 'in' after 'not'");
        }
        if (t.is("between")) {
            next();
            Node lo = additive();
            if (!peek().is("and") && !peek().is("&&")) {
                throw new ExpressionException(peek().pos(), "expected 'and' in between");
            }
            next();
            return count(new RuleExpression.Range(l, lo, additive(), false));
        }
        return l;
    }

    private Node additive() {
        Node l = multiplicative();
        while (peek().is("+") || peek().is("-")) {
            char op = next().text().charAt(0);
            l = count(new RuleExpression.Arith(op, l, multiplicative()));
        }
        return l;
    }

    private Node multiplicative() {
        Node l = unary();
        while (peek().is("*") || peek().is("/") || peek().is("%")) {
            char op = next().text().charAt(0);
            l = count(new RuleExpression.Arith(op, l, unary()));
        }
        return l;
    }

    private Node unary() {
        if (peek().is("-")) {
            next();
            enter();
            Node inner = unary();
            leave();
            return count(new RuleExpression.Neg(inner));
        }
        return primary();
    }

    private Node primary() {
        Token t = next();
        switch (t.kind()) {
            case NUMBER, STRING -> {
                return count(new RuleExpression.Literal(t.value()));
            }
            case KEYWORD -> {
                return switch (t.text()) {
                    case "true" -> count(new RuleExpression.Literal(Boolean.TRUE));
                    case "false" -> count(new RuleExpression.Literal(Boolean.FALSE));
                    case "null" -> count(new RuleExpression.Literal(null));
                    default -> throw new ExpressionException(t.pos(), "unexpected keyword '" + t.text() + "'");
                };
            }
            case IDENT -> {
                if (peek().is("(")) {
                    next();
                    String fn = t.text().toLowerCase(java.util.Locale.ROOT);
                    if (!Functions.NAMES.containsKey(fn)) {
                        throw new ExpressionException(t.pos(), "unknown function " + t.text());
                    }
                    List<Node> args = new ArrayList<>();
                    if (!peek().is(")")) {
                        enter();
                        args.add(or());
                        while (peek().is(",")) {
                            next();
                            args.add(or());
                        }
                        leave();
                    }
                    expect(")");
                    int arity = Functions.NAMES.get(fn);
                    if (args.size() != arity) {
                        throw new ExpressionException(t.pos(), fn + " takes " + arity + " argument(s)");
                    }
                    if (fn.equals("on_list")) {
                        if (!(args.getFirst() instanceof RuleExpression.Literal lit) || !(lit.value() instanceof String code)) {
                            throw new ExpressionException(t.pos(), "on_list needs a list code string literal");
                        }
                        lists.add(code);
                    }
                    if (fn.equals("has") && !(args.getFirst() instanceof RuleExpression.Ident)) {
                        throw new ExpressionException(t.pos(), "has() needs an identifier");
                    }
                    return count(new RuleExpression.Call(fn, List.copyOf(args)));
                }
                identifiers.add(t.text());
                return count(new RuleExpression.Ident(t.text()));
            }
            case OP -> {
                if (t.is("(")) {
                    enter();
                    Node inner = or();
                    leave();
                    expect(")");
                    return inner;
                }
                if (t.is("[")) {
                    List<Node> items = new ArrayList<>();
                    if (!peek().is("]")) {
                        enter();
                        items.add(or());
                        while (peek().is(",")) {
                            next();
                            items.add(or());
                        }
                        leave();
                    }
                    expect("]");
                    return count(new RuleExpression.ListLit(List.copyOf(items)));
                }
                throw new ExpressionException(t.pos(), "unexpected '" + t.text() + "'");
            }
            default -> throw new ExpressionException(t.pos(), "unexpected end of expression");
        }
    }

    private Node count(Node n) {
        if (++nodes > RuleExpression.MAX_NODES) {
            throw new ExpressionException(peek().pos(), "expression too complex (more than " + RuleExpression.MAX_NODES + " nodes)");
        }
        return n;
    }

    private void enter() {
        if (++depth > RuleExpression.MAX_DEPTH) {
            throw new ExpressionException(peek().pos(), "expression nested too deeply");
        }
    }

    private void leave() {
        depth--;
    }

    private void expect(String op) {
        Token t = next();
        if (!t.is(op)) {
            throw new ExpressionException(t.pos(), "expected '" + op + "' but found '" + t.text() + "'");
        }
    }

    private Token peek() {
        return tokens.get(pos);
    }

    private Token peekAt(int ahead) {
        return tokens.get(Math.min(pos + ahead, tokens.size() - 1));
    }

    private Token next() {
        Token t = tokens.get(pos);
        if (t.kind() != Kind.EOF) {
            pos++;
        }
        return t;
    }
}
