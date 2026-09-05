package ru.wds.wdl.ast;

import ru.wds.wdl.ast.expr.Argument;
import ru.wds.wdl.ast.expr.ArrayExpr;
import ru.wds.wdl.ast.expr.CaseTail;
import ru.wds.wdl.ast.expr.Decorator;
import ru.wds.wdl.ast.expr.FunctionExpr;
import ru.wds.wdl.ast.expr.MatchCase;
import ru.wds.wdl.ast.expr.ObjectExpr;
import ru.wds.wdl.ast.stmt.ClassDeclStmt;
import ru.wds.wdl.ast.stmt.PropertyDecl;
import ru.wds.wdl.ast.stmt.TraitDeclStmt;
import ru.wds.wdl.ast.stmt.TryStmt;
import ru.wds.wdl.ast.stmt.UnpackTarget;
import ru.wds.wdl.ast.stmt.UseStmt;

/**
 * Часть узла, у которой есть своё место в исходнике, но нет ни значения, ни действия:
 * параметр, аргумент, запись объекта, обработчик {@code catch}, привязка {@code use},
 * ветка {@code match}, свойство и его аксессор.
 * <p>
 * <b>Зачем ветвь, а не просто набор записей.</b> Инструментам редактора нужен один
 * ответ на вопрос «что за кусок дерева стоит под курсором» — а до появления этого
 * интерфейса половина кусков узлами не была: {@code span} у них имелся, но по типу
 * они не были родня, и каждый обход обязан был знать каждого в лицо. Теперь
 * «у любой части дерева есть место в исходнике» — правда без исключений, и обход
 * пишется один раз ({@link Nodes}).
 * <p>
 * <b>Разборы {@code Expr} и {@code Stmt} от этого не меняются.</b> Полнота
 * {@code switch} проверяется по {@code permits} своей иерархии, а фрагмент — другая
 * ветвь {@link Node}: он не может встать там, где ждут выражение или инструкцию.
 * <p>
 * <b>Чего здесь нет.</b> {@code Annotations} — единственная запись со «своим местом»,
 * которая сюда не попала: у ненаписанных аннотаций места нет вовсе, и {@code span()}
 * там честно отвечает {@code null}. Втащить её значило бы завести узел без интервала,
 * то есть отменить то самое правило, ради которого ветвь и вводится; её записи
 * при этом доступны как дети своего владельца.
 */
public sealed interface Fragment extends Node
        permits FunctionExpr.Param, FunctionExpr.Rest, Argument, ArrayExpr.Element,
                ObjectExpr.Entry, Decorator, MatchCase, CaseTail,
                ClassDeclStmt.Superclass, ClassDeclStmt.TraitRef, ClassDeclStmt.Factory,
                TraitDeclStmt.Requirement, PropertyDecl, PropertyDecl.Accessor,
                TryStmt.Catch, TryStmt.TypeRef, UseStmt.Binding, UnpackTarget {
}
