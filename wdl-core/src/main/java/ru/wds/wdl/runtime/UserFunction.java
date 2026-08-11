package ru.wds.wdl.runtime;

import ru.wds.wdl.ast.expr.FunctionExpr;
import ru.wds.wdl.module.Unit;
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
 * <p>
 * Вместе с замыканием функция запоминает и {@link Unit юнит} — файл, где она написана.
 * Замыкание отвечает на вопрос «какие имена видно», юнит — «в каком файле мы находимся»:
 * функция из модуля, вызванная из главного скрипта, объявляет классы по формам своего
 * файла и сообщает об ошибках его строками.
 */
public final class UserFunction implements FunctionValue {

    private final FunctionExpr declaration;
    private final Environment closure;
    private final Unit unit;
    /** Интерпретатор безсостоятельный, поэтому делить один экземпляр безопасно. */
    private final Interpreter interpreter;
    private final Arity arity;

    UserFunction(FunctionExpr declaration, Environment closure, Unit unit, Interpreter interpreter) {
        this.declaration = Objects.requireNonNull(declaration, "declaration");
        this.closure = Objects.requireNonNull(closure, "closure");
        this.unit = Objects.requireNonNull(unit, "unit");
        this.interpreter = Objects.requireNonNull(interpreter, "interpreter");
        this.arity = arityOf(declaration);
    }

    /**
     * Обязательных параметров столько, сколько их до первого со значением по умолчанию:
     * парсер не пропускает обязательный после необязательного, поэтому дальше идут
     * только необязательные и арность остаётся отрезком.
     */
    private static Arity arityOf(FunctionExpr declaration) {
        List<FunctionExpr.Param> params = declaration.params();
        int required = 0;
        while (required < params.size() && !params.get(required).hasDefault()) {
            required++;
        }
        return Arity.between(required, params.size());
    }

    @Override
    public String name() {
        return declaration.title();
    }

    @Override
    public Arity arity() {
        return arity;
    }

    @Override
    public Value call(CallContext context, List<Value> arguments, Span span) {
        if (context.callDepth() >= ExecutionContext.MAX_CALL_DEPTH) {
            // Рекурсия без выхода — не «эта операция не удалась», а «выполнение дальше
            // не идёт»: поймать такое обработчиком нельзя, иначе цикл с try съел бы
            // собственную защиту от зацикливания.
            throw FatalError.tooDeep(span, "Проверьте условие выхода из '" + name() + "'");
        }

        Environment local = closure.child();
        // Контекст создаётся до связывания: в нём же вычисляются значения по умолчанию,
        // и оттого они видят параметры, связанные левее, — область у них одна и та же.
        ExecutionContext inner = ExecutionContext.call(local, context, unit, name(), span);
        List<FunctionExpr.Param> params = declaration.params();
        for (int i = 0; i < params.size(); i++) {
            // Параметры — всегда локальные: одноимённая внешняя переменная остаётся
            // нетронутой, даже если функция параметру что-то присвоит.
            local.define(params.get(i).name(), i < arguments.size()
                    ? arguments.get(i)
                    // Значение по умолчанию считается заново на каждом вызове: снимок,
                    // сделанный при объявлении, сделал бы один массив или объект общим
                    // для всех вызовов — известная ловушка Python.
                    : interpreter.visit(params.get(i).defaultValue(), inner));
        }

        try {
            interpreter.visit(declaration.body(), inner);
        } catch (ControlSignal.Return signal) {
            return signal.value();
        } catch (WdlError error) {
            // Место в исходнике у ошибки уже есть, а вот какому файлу оно принадлежит,
            // знает только тело функции — здесь и последняя возможность это сказать.
            if (error instanceof WdlRuntimeError runtime) {
                // И здесь же — путь по скрипту. Спросят об этом все границы вызова
                // на пути наружу, но полная цепочка кадров только у самой внутренней:
                // у внешних от неё остался бы хвост.
                runtime.rememberTrace(Frame.trace(inner.frame()));
            }
            throw error.inSource(unit.source());
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
