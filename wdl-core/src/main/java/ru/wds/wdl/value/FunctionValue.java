package ru.wds.wdl.value;

import ru.wds.wdl.source.Span;

import java.util.List;

/**
 * Функция как значение.
 * <p>
 * В языке одно пространство имён: {@code println} — не синтаксис и не особая
 * сущность, а обычное значение в обычной переменной. Отсюда сразу следует всё
 * остальное: функцию можно передать аргументом, вернуть из другой функции, положить
 * в массив и в поле объекта, а вызов {@code точка.строкой()} — это обращение
 * по ключу, давшее функцию, и её вызов.
 * <p>
 * Интерфейс, а не {@code record}: реализации бывают разные — встроенная функция
 * ({@code runtime.BuiltinFunction}), позже пользовательская с телом-деревом
 * и замыканием, ещё позже мост к Java-методу. Для вызывающего они неразличимы.
 * Поэтому {@link Value} и остаётся {@code sealed}, а эта его ветка открыта.
 */
public non-sealed interface FunctionValue extends Value {

    /** Имя для диагностики и печати; у анонимной функции — что-то вроде {@code "def"}. */
    String name();

    Arity arity();

    /**
     * Выполняет функцию. Число аргументов уже проверено по {@link #arity()}.
     *
     * @param context среда выполнения: вывод и всё, что понадобится дальше
     * @param arguments вычисленные аргументы
     * @param span место вызова в скрипте — чтобы ошибка внутри функции указывала на него
     */
    Value call(CallContext context, List<Value> arguments, Span span);

    @Override
    default ValueType type() {
        return ValueType.FUNCTION;
    }

    /**
     * Печатается коротко — {@code def println}. Сколько функция берёт аргументов,
     * человек узнаёт в тот момент, когда это важно: из сообщения об ошибке вызова.
     */
    @Override
    default String display() {
        return "def " + name();
    }
}
