package ru.wds.wdl.runtime;

import ru.wds.wdl.runtime.Args;
import ru.wds.wdl.source.Span;
import ru.wds.wdl.value.Arity;
import ru.wds.wdl.value.CallContext;
import ru.wds.wdl.value.FunctionValue;
import ru.wds.wdl.value.Signature;
import ru.wds.wdl.value.Value;

import java.util.List;
import java.util.Objects;

/**
 * Функция, реализованная на Java: {@code println} и всё, что придёт следом
 * из стандартной библиотеки.
 * <p>
 * Ничем не отличается от будущей пользовательской функции с точки зрения языка —
 * это такое же значение, лежащее в такой же переменной. Поэтому {@code println}
 * можно передать аргументом или переопределить, а вызов не требует знать, что там
 * внутри: Java-код или тело на wdl.
 * <p>
 * Тело задаётся лямбдой, состояния у функции нет: всё нужное приходит в
 * {@link CallContext}. Один экземпляр спокойно живёт в нескольких интерпретаторах.
 */
public final class BuiltinFunction implements FunctionValue, ru.wds.wdl.value.Documented {

    /**
     * Реализация встроенной функции. Число аргументов уже проверено.
     * <p>
     * Аргументы приходят {@link Args} — обычным списком значений, который вдобавок
     * умеет отвечать за их тип: {@code args.string(0, "путь")} вместо проверки руками
     * и своего текста ошибки в каждом теле.
     */
    @FunctionalInterface
    public interface Body {
        Value apply(CallContext context, Args arguments, Span span);
    }

    private final String name;
    private final Signature signature;
    private final Body body;
    /** Описание для подсказки редактора; выполнение его не читает никогда. */
    private final String documentation;

    private BuiltinFunction(String name, Signature signature, Body body, String documentation) {
        this.name = Objects.requireNonNull(name, "name");
        this.signature = Objects.requireNonNull(signature, "signature");
        this.body = Objects.requireNonNull(body, "body");
        this.documentation = documentation;
    }

    /**
     * Функция, которую зовут только по позиции: имена параметров не объявлены.
     * <p>
     * Форма остаётся законной и после появления именованных аргументов — у
     * {@code println} с любым числом аргументов имён нет и быть не может.
     */
    public static BuiltinFunction of(String name, Arity arity, Body body) {
        return new BuiltinFunction(name, Signature.positional(arity), body, null);
    }

    /**
     * Функция с объявленными именами параметров: {@code gui.grid(rows: 2, cols: 3)}.
     * <p>
     * Значения по умолчанию здесь — готовые значения, а не выражения, поэтому пропуск
     * в середине закрывает связыватель, и тело получает привычный плотный список.
     * Ему не нужно знать, что вызов был именованным.
     */
    public static BuiltinFunction of(String name, Signature signature, Body body) {
        return new BuiltinFunction(name, signature, body, null);
    }

    /**
     * Та же функция с описанием для подсказки.
     * <p>
     * Копией, а не полем в фабрике: описание приходит из построителя модуля позже,
     * чем тело, — {@code .function(...)} и {@code .doc(...)} стоят разными звеньями
     * цепочки. Функция неизменяема, копия дёшева и делается один раз на установку.
     */
    public BuiltinFunction documented(String text) {
        return text == null || text.isBlank()
                ? this
                : new BuiltinFunction(name, signature, body, text);
    }

    @Override
    public String documentation() {
        return documentation;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Arity arity() {
        return signature.arity();
    }

    @Override
    public Signature signature() {
        return signature;
    }

    @Override
    public Value call(CallContext context, List<Value> arguments, Span span) {
        return body.apply(context, Args.of(name, arguments, context, span), span);
    }

    @Override
    public String toString() {
        return display();
    }
}
