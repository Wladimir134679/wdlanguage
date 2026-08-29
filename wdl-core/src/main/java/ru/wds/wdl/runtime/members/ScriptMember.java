package ru.wds.wdl.runtime.members;

import ru.wds.wdl.ast.expr.FunctionExpr;
import ru.wds.wdl.ast.stmt.PropertyDecl;
import ru.wds.wdl.module.Unit;
import ru.wds.wdl.runtime.Environment;
import ru.wds.wdl.runtime.Interpreter;
import ru.wds.wdl.runtime.Run;
import ru.wds.wdl.runtime.UserFunction;
import ru.wds.wdl.source.Span;
import ru.wds.wdl.value.Arity;
import ru.wds.wdl.value.CallContext;
import ru.wds.wdl.value.FunctionValue;
import ru.wds.wdl.value.Member;
import ru.wds.wdl.value.Property;
import ru.wds.wdl.value.Value;

import java.util.List;

/**
 * Член, объявленный скриптом в {@code extend}.
 * <p>
 * <b>Своего механизма у него нет.</b> Метод — обычная {@link UserFunction}, у которой
 * в замыкании определено имя {@code this}; свойство — та же функция, вызываемая
 * чтением. Отсюда даром достаётся всё, что уже умеет функция: {@code return},
 * ограничение глубины вызовов, трассировка, замыкание на область объявления.
 * <p>
 * <b>{@code this} заводится областью, а не полем значения.</b> Получатель чужой —
 * массив, строка, число, — и класть в него что-либо нельзя; но имя в области видимости
 * ничем не отличается от того, как {@code this} устроен у метода класса. Разница ровно
 * одна и следует из той же причины: скрытого поля у члена типа не бывает, хранить его
 * негде.
 * <p>
 * <b>Записи нет.</b> Свойство расширения только читается: присваивание в свойство —
 * действие, замаскированное под имя, а запись в член встроенного типа вдобавок некуда
 * девать. Отказ выдаётся при объявлении, а не при попытке записи, — чтобы автор узнал
 * о запрете там, где он его нарушил.
 */
public final class ScriptMember {

    private ScriptMember() {
    }

    /** Метод расширения: {@code def swap(i, j) { ... }}. */
    public static Member method(FunctionExpr declaration, Environment closure, Unit unit,
                         Run run, Interpreter interpreter) {
        return new MethodMember(declaration, closure, unit, run, interpreter);
    }

    /** Свойство расширения: {@code property second => this[1]}. */
    public static Member property(PropertyDecl declaration, Environment closure, Unit unit,
                           Run run, Interpreter interpreter) {
        return new PropertyMember(declaration, closure, unit, run, interpreter);
    }

    /**
     * Область получателя: та же область объявления плюс имя {@code this}.
     * <p>
     * Новый слой на каждое чтение, а не общий: получатель у каждого обращения свой,
     * и держать его в поле значило бы, что два потока, читающие один член у разных
     * значений, видят чужой {@code this}.
     */
    private static Environment scopeOf(Environment closure, Value receiver) {
        Environment scope = closure.child();
        scope.define("this", receiver);
        return scope;
    }

    private record MethodMember(FunctionExpr declaration, Environment closure, Unit unit,
                                Run run, Interpreter interpreter) implements Member {

        @Override
        public String name() {
            return declaration.name();
        }

        @Override
        public Property property() {
            return null;
        }

        @Override
        public FunctionValue bind(Value receiver) {
            // Замок synchronized-метода здесь не нужен: замок берётся у экземпляра,
            // а у массива и строки его нет — склеивать обращения им придётся
            // явным th.lock(), как и всем остальным.
            return new UserFunction(declaration, scopeOf(closure, receiver), unit, run, interpreter, null);
        }

        @Override
        public Arity arity() {
            return UserFunction.arityOf(declaration);
        }

        @Override
        public boolean snapshot() {
            return false;
        }
    }

    private record PropertyMember(PropertyDecl declaration, Environment closure, Unit unit,
                                  Run run, Interpreter interpreter) implements Member, Property {

        @Override
        public String name() {
            return declaration.name();
        }

        @Override
        public Property property() {
            return this;
        }

        @Override
        public FunctionValue bind(Value receiver) {
            return null;
        }

        @Override
        public Arity arity() {
            return Arity.exactly(0);
        }

        /**
         * Цену свойства скрипта движок не знает и знать не может — за ним стоит
         * произвольный код. Поэтому «снимок» здесь {@code false}: обещание, которое
         * нечем подтвердить, хуже отсутствующего.
         */
        @Override
        public boolean snapshot() {
            return false;
        }

        @Override
        public boolean readable() {
            return true;
        }

        @Override
        public boolean writable() {
            return false;
        }

        @Override
        public Value read(Value receiver, CallContext context, Span span) {
            return new UserFunction(declaration.getter().function(), scopeOf(closure, receiver),
                    unit, run, interpreter, null).call(context, List.of(), span);
        }

        @Override
        public void write(Value receiver, Value value, CallContext context, Span span) {
            throw new IllegalStateException("у свойства расширения нет записи");
        }
    }
}
