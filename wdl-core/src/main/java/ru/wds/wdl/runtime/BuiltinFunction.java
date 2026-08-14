package ru.wds.wdl.runtime;

import ru.wds.wdl.embed.Args;
import ru.wds.wdl.source.Span;
import ru.wds.wdl.value.Arity;
import ru.wds.wdl.value.CallContext;
import ru.wds.wdl.value.FunctionValue;
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
public final class BuiltinFunction implements FunctionValue {

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
    private final Arity arity;
    private final Body body;

    private BuiltinFunction(String name, Arity arity, Body body) {
        this.name = Objects.requireNonNull(name, "name");
        this.arity = Objects.requireNonNull(arity, "arity");
        this.body = Objects.requireNonNull(body, "body");
    }

    public static BuiltinFunction of(String name, Arity arity, Body body) {
        return new BuiltinFunction(name, arity, body);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Arity arity() {
        return arity;
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
