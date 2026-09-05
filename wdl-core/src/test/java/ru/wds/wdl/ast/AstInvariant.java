package ru.wds.wdl.ast;

import ru.wds.wdl.source.Source;
import ru.wds.wdl.source.Span;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Проверка свойств дерева, на которых стоит весь инструментарий: узел лежит внутри
 * текста, ребёнок — внутри родителя, соседи не наезжают друг на друга.
 * <p>
 * Свойства выглядят самоочевидными, и ровно поэтому их надо проверять машиной:
 * пока дерево только печатают, нарушение незаметно, а «узел под курсором»
 * и построение PSI по интервалам оно ломает сразу. Найденное нарушение печатается
 * с путём от корня и обеими подстроками исходника — иначе разбираться в нём
 * приходится гаданием.
 */
final class AstInvariant {

    private final Source source;
    private final List<Node> path = new ArrayList<>();

    private AstInvariant(Source source) {
        this.source = source;
    }

    /** Проверяет всё дерево; при нарушении роняет тест с подробным сообщением. */
    static void check(Program program, Source source) {
        AstInvariant invariant = new AstInvariant(source);
        invariant.checkNode(program, null);
        Span span = program.span();
        if (!span.isNone() && span.end() != source.length()) {
            // Пустой файл и файл из одних комментариев дают пустую программу — у неё
            // интервал нулевой, и это правда: узлов в ней нет.
            if (!(program.statements().isEmpty() && span.length() == 0)) {
                invariant.violation("интервал программы не доходит до конца документа",
                        program, span, new Span(0, source.length()));
            }
        }
    }

    private void checkNode(Node node, Node parent) {
        Span span = node.span();
        path.add(node);
        if (span == null) {
            violation("у узла нет интервала", node, Span.NONE, Span.NONE);
        }
        if (span.isNone()) {
            violation("узел без места в исходнике: дерево разбора таких не порождает",
                    node, span, parent == null ? Span.NONE : parent.span());
        }
        if (span.start() < 0 || span.end() > source.length()) {
            violation("интервал выходит за пределы текста длиной " + source.length(),
                    node, span, new Span(0, source.length()));
        }
        if (parent != null && !contains(parent.span(), span)) {
            violation("узел вылез за интервал родителя " + describe(parent), node, span, parent.span());
        }

        List<Node> children = Nodes.children(node);
        Node previous = null;
        for (Node child : children) {
            if (previous != null && !previous.span().isNone() && !child.span().isNone()
                    && previous.span().end() > child.span().start()) {
                violation("соседние дети пересекаются: после " + describe(previous),
                        child, child.span(), previous.span());
            }
            previous = child;
        }
        for (Node child : children) {
            checkNode(child, node);
        }
        path.remove(path.size() - 1);
    }

    private static boolean contains(Span outer, Span inner) {
        return !outer.isNone() && !inner.isNone()
                && outer.start() <= inner.start() && inner.end() <= outer.end();
    }

    private void violation(String what, Node node, Span span, Span other) {
        StringBuilder message = new StringBuilder(256);
        message.append(source.name()).append(": ").append(what).append('\n')
                .append("  узел: ").append(describe(node)).append(' ').append(place(span)).append('\n')
                .append("  рядом: ").append(place(other)).append('\n')
                .append("  путь от корня: ");
        for (int i = 0; i < path.size(); i++) {
            message.append(i > 0 ? " → " : "").append(path.get(i).getClass().getSimpleName());
        }
        fail(message.toString());
    }

    private String place(Span span) {
        if (span == null || span.isNone()) {
            return "<нет позиции>";
        }
        String text = source.text().substring(Math.max(0, span.start()),
                Math.min(source.length(), span.end()));
        return span + " (" + source.positionOf(span.start()) + ") «" + text.replace("\n", "\\n") + "»";
    }

    private static String describe(Node node) {
        return node.getClass().getSimpleName();
    }
}
