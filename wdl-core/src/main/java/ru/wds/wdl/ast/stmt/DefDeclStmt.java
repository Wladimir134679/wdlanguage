package ru.wds.wdl.ast.stmt;

import ru.wds.wdl.ast.expr.FunctionExpr;
import ru.wds.wdl.source.Span;

import java.util.Objects;

/**
 * Объявление функции: {@code def сумма(a, b) => a + b}.
 * <p>
 * Отдельный вид инструкции, а не сахар над присваиванием {@code сумма = def(a, b) ...},
 * хотя пространство имён в языке одно и результат почти тот же. Разница в двух вещах,
 * и обе существенные. Во-первых, объявление <b>заводит</b> имя в текущей области,
 * а присваивание ищет его снаружи и меняет там, где нашло. Во-вторых, объявления
 * верхнего уровня помечаются в корневой области до начала выполнения
 * (см. {@code Interpreter#run}), поэтому функцию можно вызвать выше её объявления
 * и написать две взаимно рекурсивные — а для этого их надо уметь найти в дереве,
 * не путая с обычным присваиванием функции в переменную.
 * <p>
 * Само значение-функцию описывает {@link FunctionExpr}: узел держит его целиком,
 * а не копию полей, поэтому создание функции остаётся в одном месте интерпретатора.
 *
 * @param function объявляемая функция; её {@link FunctionExpr#name()} и есть имя
 * @param span     место в исходнике от {@code def} до конца тела
 */
public record DefDeclStmt(FunctionExpr function, Span span) implements Stmt {

    /**
     * Та же инструкция с другим интервалом: им объявление раздвигают до написанных
     * перед ним блоков {@code @{...}}. Решает это разбор верхнего уровня — только он
     * знает, не стоит ли между блоками и словом {@code def} декоратор.
     */
    public DefDeclStmt withSpan(Span span) {
        return new DefDeclStmt(function, span);
    }

    public DefDeclStmt {
        Objects.requireNonNull(function, "function");
        Objects.requireNonNull(span, "span");
        if (function.name() == null) {
            throw new IllegalArgumentException("объявление функции без имени: " + span);
        }
    }

    /** Имя, под которым функция заводится в области видимости. */
    public String name() {
        return function.name();
    }

    @Override
    public String toString() {
        return function.toString();
    }
}
