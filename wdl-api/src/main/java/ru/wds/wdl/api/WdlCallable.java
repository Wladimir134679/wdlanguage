package ru.wds.wdl.api;

import ru.wds.wdl.runtime.ExecutionContext;
import ru.wds.wdl.runtime.WdlError;
import ru.wds.wdl.source.Source;
import ru.wds.wdl.source.Span;
import ru.wds.wdl.value.FunctionValue;
import ru.wds.wdl.value.Value;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Функция скрипта в руках приложения: обычный Java-объект, у которого есть
 * {@link #call}.
 * <p>
 * Это ответ на «пусть библиотека просто зовёт функцию и ничего не знает». Откуда
 * функция взялась — из главного файла, из метода класса, из модуля, из замыкания —
 * не спрашивается и не выражается в типе. Она несёт свой контекст с собой: область,
 * где объявлена, файл, где написана, и запуск, которому принадлежит. Поэтому её можно
 * положить в поле, отдать в Spring, поставить в очередь, позвать из другого потока —
 * и внутри будет ровно то, что было бы при вызове из скрипта.
 * <pre>{@code
 * try (WdlInstance script = engine.compile(path).instance()) {
 *     script.execute();
 *     WdlCallable onMessage = script.function("onMessage");
 *
 *     bus.subscribe(text -> onMessage.call(text));   // живёт дольше вызова
 * }
 * }</pre>
 *
 * <h2>Что видно изнутри</h2>
 * Всё, что было видно на месте объявления, и <b>в том состоянии, в каком оно сейчас</b>:
 * замыкание — это ссылка на область, а не снимок. Функция, считающая {@code counter},
 * увидит {@code counter}, изменённый скриптом после её объявления; метод класса увидит
 * поля своего объекта, {@code this} и {@code super}.
 *
 * <h2>Потоки</h2>
 * Звать можно из любого потока. Внутри запуска в каждый момент работает один: вход
 * снаружи берёт замок запуска, поэтому вызовы выстраиваются в очередь, а не портят
 * друг другу переменные. Параллельность даётся <b>несколькими экземплярами</b>
 * ({@link WdlScript#instance()}) — они не делят ничего.
 *
 * <h2>Время жизни</h2>
 * Привязан к {@link WdlInstance}, из которого получен. После {@code close()} экземпляра
 * библиотеки его модулей закрыты, и вызов функции, которая ими пользуется, ответит
 * ошибкой — как ей и полагается.
 */
public final class WdlCallable {

    /**
     * Место вызова для тех вызовов, у которых места в скрипте нет.
     * <p>
     * Позвали снаружи — значит, в тексте скрипта такой точки не существует, и врать
     * про неё нельзя. Ноль здесь означает «начало файла», и это самое честное, что
     * можно сказать: трассировка начнётся с первого кадра самой функции.
     */
    private static final Span OUTSIDE = Span.point(0);

    private final String name;
    private final FunctionValue function;
    private final ExecutionContext context;
    private final Source source;

    WdlCallable(String name, FunctionValue function, ExecutionContext context, Source source) {
        this.name = Objects.requireNonNull(name, "name");
        this.function = Objects.requireNonNull(function, "function");
        this.context = Objects.requireNonNull(context, "context");
        this.source = source;
    }

    /** Имя, под которым функция получена. */
    public String name() {
        return name;
    }

    /** Сколько аргументов функция принимает — если приложению надо проверить заранее. */
    public boolean accepts(int count) {
        return function.arity().accepts(count);
    }

    /** Значение-функция как есть: чтобы передать её дальше по языку, а не звать. */
    public Value value() {
        return function;
    }

    /**
     * Зовёт функцию значениями языка.
     *
     * @throws WdlException если скрипт упал — с местом в его тексте и путём по вызовам
     */
    public Value call(Value... arguments) {
        return call(List.of(arguments));
    }

    /** То же самое, когда аргументы уже собраны списком. */
    public Value call(List<Value> arguments) {
        try {
            return function.call(context, arguments, OUTSIDE);
        } catch (WdlError error) {
            throw WdlException.runtime(error, source);
        }
    }

    /**
     * Зовёт функцию обычными Java-значениями: строками, числами, списками, картами.
     * <p>
     * Преобразование — то же самое, что у {@link WdlInstance#define}: приложение,
     * которому нечего сказать про типы языка, не должно их изучать ради одного вызова.
     */
    public Object invoke(Object... arguments) {
        List<Value> values = new ArrayList<>(arguments.length);
        for (Object argument : arguments) {
            values.add(Values.of(argument));
        }
        return Values.toJava(call(values));
    }

    /** Функция как {@link Runnable}: для вызова без аргументов и без результата. */
    public Runnable asRunnable() {
        return this::call;
    }

    /** Функция как {@link Supplier}: без аргументов, с результатом. */
    public Supplier<Object> asSupplier() {
        return this::invoke;
    }

    /** Функция как {@link Consumer}: один аргумент, результат отбрасывается. */
    public Consumer<Object> asConsumer() {
        return argument -> invoke(argument);
    }

    /** Функция как {@link Function}: один аргумент, один результат. */
    public Function<Object, Object> asFunction() {
        return this::invoke;
    }

    /**
     * Функция как реализация любого интерфейса с одним методом.
     * <p>
     * Нужно затем, что приложение почти никогда не принимает {@code Function} — оно
     * принимает свой {@code MessageHandler} или {@code EventListener}. Скрипту при этом
     * знать про этот интерфейс незачем: он написал функцию, а совместить её с чужой
     * подписью — работа границы, то есть этого места.
     * <pre>{@code
     * MessageHandler handler = script.function("onMessage").as(MessageHandler.class);
     * bus.subscribe(handler);
     * }</pre>
     * {@code equals}, {@code hashCode} и {@code toString} остаются за прокси —
     * иначе объект нельзя было бы ни сравнить, ни напечатать, ни положить в множество.
     *
     * @param contract интерфейс с ровно одним абстрактным методом
     */
    public <T> T as(Class<T> contract) {
        Objects.requireNonNull(contract, "contract");
        if (!contract.isInterface()) {
            throw new IllegalArgumentException(contract.getName() + " — не интерфейс: "
                    + "функцию можно подставить только под интерфейс");
        }
        InvocationHandler handler = (proxy, method, arguments) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "equals" -> proxy == (arguments == null ? null : arguments[0]);
                    case "hashCode" -> System.identityHashCode(proxy);
                    default -> "wdl:" + name;
                };
            }
            return adapt(method, invoke(arguments == null ? new Object[0] : arguments));
        };
        return contract.cast(Proxy.newProxyInstance(
                contract.getClassLoader(), new Class<?>[]{contract}, handler));
    }

    /**
     * Подгоняет результат под то, что обещает метод интерфейса.
     * <p>
     * Метод объявлен {@code void} — результат скрипта отбрасывается: функция в языке
     * возвращает значение всегда, даже дойдя до конца тела, и настаивать на этом здесь
     * значило бы требовать от скрипта знать чужую подпись.
     */
    private static Object adapt(Method method, Object result) {
        Class<?> expected = method.getReturnType();
        if (expected == void.class || expected == Void.class) {
            return null;
        }
        return result;
    }

    @Override
    public String toString() {
        return "WdlCallable[" + name + "]";
    }
}
