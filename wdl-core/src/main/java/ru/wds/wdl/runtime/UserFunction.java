package ru.wds.wdl.runtime;

import ru.wds.wdl.ast.expr.FunctionExpr;
import ru.wds.wdl.source.Span;
import ru.wds.wdl.value.Arity;
import ru.wds.wdl.value.CallContext;
import ru.wds.wdl.value.FunctionValue;
import ru.wds.wdl.value.Value;
import ru.wds.wdl.value.types.NullValue;

import java.util.List;
import java.util.Objects;

/**
 * Функция, написанная на wdl: тело-дерево плюс область видимости, в которой её объявили.
 * <p>
 * Для языка она ничем не отличается от встроенной {@link BuiltinFunction} — такое же
 * значение в такой же переменной. Поэтому её можно передать аргументом, вернуть
 * из другой функции, положить в массив и в поле объекта, а {@code visitCall} не знает
 * и не хочет знать, что внутри: Java-лямбда или дерево.
 * <p>
 * <b>Замыкание — это ссылка на область объявления, и больше ничего.</b> Отсюда само
 * собой получается всё, чего от функций ждут:
 * <ul>
 *   <li>рекурсия — объявление заводит имя в той же области, которая стала замыканием,
 *       поэтому функция находит себя обычным поиском имени;</li>
 *   <li>вложенная функция видит переменные вызова, внутри которого её объявили,
 *       и живёт дольше него;</li>
 *   <li>значения переменных не копируются: замыкание захватывает область, а не снимок,
 *       так что два замыкания над одной переменной видят одно и то же.</li>
 * </ul>
 * Тело функции выполняется в <b>вложенной</b> области: имя, впервые присвоенное внутри,
 * наружу не попадает, а присваивание уже известному имени уходит туда, где оно заведено.
 * Правило то же, что у блока, — функция не заводит для него исключений.
 */
public final class UserFunction implements FunctionValue {

    private final FunctionExpr declaration;
    private final Environment closure;
    /** Интерпретатор безсостоятельный, поэтому делить один экземпляр безопасно. */
    private final Interpreter interpreter;

    UserFunction(FunctionExpr declaration, Environment closure, Interpreter interpreter) {
        this.declaration = Objects.requireNonNull(declaration, "declaration");
        this.closure = Objects.requireNonNull(closure, "closure");
        this.interpreter = Objects.requireNonNull(interpreter, "interpreter");
    }

    @Override
    public String name() {
        return declaration.title();
    }

    /**
     * Пока — ровно столько аргументов, сколько параметров. Значения по умолчанию
     * ({@code fun f(a, b = 10)}) превратят это в {@link Arity#between}.
     */
    @Override
    public Arity arity() {
        return Arity.exactly(declaration.params().size());
    }

    @Override
    public Value call(CallContext context, List<Value> arguments, Span span) {
        if (context.callDepth() >= ExecutionContext.MAX_CALL_DEPTH) {
            // Рекурсия без выхода — ошибка скрипта, и говорить о ней надо на языке скрипта.
            throw new WdlRuntimeError(span, "слишком глубокая рекурсия: вложенных вызовов больше "
                    + ExecutionContext.MAX_CALL_DEPTH + ". Проверьте условие выхода из '" + name() + "'");
        }

        Environment local = closure.child();
        List<FunctionExpr.Param> params = declaration.params();
        for (int i = 0; i < params.size(); i++) {
            // Параметры — всегда локальные: одноимённая внешняя переменная остаётся
            // нетронутой, даже если функция параметру что-то присвоит.
            local.define(params.get(i).name(), arguments.get(i));
        }

        try {
            interpreter.visit(declaration.body(), ExecutionContext.call(local, context));
        } catch (ControlSignal.Return signal) {
            return signal.value();
        } catch (ControlSignal.Break | ControlSignal.Continue signal) {
            // Парсер обнуляет счётчик циклов на границе функции, поэтому сюда можно
            // попасть только с деревом, собранным в обход разбора.
            throw new IllegalStateException(
                    "выход из цикла через границу функции: дерево собрано неверно", signal);
        }
        // Тело закончилось без return: функция ничего не вернула, и это null, а не «ничего».
        return NullValue.NULL;
    }

    @Override
    public String display() {
        return declaration.name() != null ? "fun " + declaration.name() : "fun";
    }

    @Override
    public String toString() {
        return display();
    }
}
