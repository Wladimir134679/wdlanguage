package ru.wds.wdl.interop;

import ru.wds.wdl.source.Span;
import ru.wds.wdl.value.CallContext;
import ru.wds.wdl.value.FunctionValue;
import ru.wds.wdl.value.Value;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Функция скрипта как Java-объект: {@code list.sort(def (a, b) => a - b)}.
 * <p>
 * Работает там, где Java просит интерфейс с одним методом, — {@code Runnable},
 * {@code Comparator}, слушатель, {@code Consumer}. Именно это почти всегда и нужно:
 * библиотеке нужен не «объект с методами», а «вот код, позови его», и в языке такой
 * код — обычная функция.
 *
 * <h2>Живёт дольше вызова — и это не проблема</h2>
 * Обработчик, отданный в {@code addListener}, переживёт и вызов, и весь скрипт.
 * Отдельного механизма для этого не понадобилось: функция несёт свой запуск с собой,
 * а {@code UserFunction.call} сам различает «уже внутри запуска» и «вход снаружи» —
 * считает вход и проверяет, не закрыт ли запуск. Поэтому вызов из чужого потока
 * работает, а вызов после закрытия отвечает ошибкой, как ему и полагается.
 * <p>
 * {@link CallContext} держится тот, в котором функцию передали: он нужен ровно
 * за одним — чтобы {@code println} внутри обработчика напечатал туда же, куда
 * печатает остальной скрипт.
 *
 * <h2>Что не заворачивается</h2>
 * Интерфейс с двумя абстрактными методами. Не потому, что технически нельзя,
 * а потому, что непонятно, какой из них звать: одна функция на два метода — это
 * догадка, а догадка в этом месте молча уходит не в тот метод.
 */
final class JavaProxy {

    private static final Map<Class<?>, Method> SAM = new ConcurrentHashMap<>();
    private static final Method NONE;

    static {
        try {
            NONE = Object.class.getMethod("hashCode");
        } catch (NoSuchMethodException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private JavaProxy() {
    }

    /**
     * Единственный абстрактный метод этого интерфейса или {@code null}.
     * <p>
     * {@code equals}, {@code hashCode} и {@code toString} не считаются: интерфейс
     * вправе их переобъявить, и {@code Comparator} это делает — а функциональным
     * от этого быть не перестаёт.
     */
    static Method sam(Class<?> type) {
        Method found = SAM.computeIfAbsent(type, JavaProxy::findSam);
        return found == NONE ? null : found;
    }

    private static Method findSam(Class<?> type) {
        if (!type.isInterface()) {
            return NONE;
        }
        Method single = null;
        for (Method method : type.getMethods()) {
            if (!Modifier.isAbstract(method.getModifiers()) || fromObject(method)) {
                continue;
            }
            if (single != null) {
                return NONE;
            }
            single = method;
        }
        return single == null ? NONE : single;
    }

    private static boolean fromObject(Method method) {
        try {
            Object.class.getMethod(method.getName(), method.getParameterTypes());
            return true;
        } catch (NoSuchMethodException own) {
            return false;
        }
    }

    /** Обёртка вокруг функции скрипта, реализующая этот интерфейс. */
    static Object of(Class<?> type, FunctionValue function, Marshal marshal,
                     CallContext context, Span span) {
        InvocationHandler handler = (proxy, method, arguments) -> {
            if (fromObject(method)) {
                return switch (method.getName()) {
                    case "equals" -> proxy == arguments[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    default -> "def " + function.name();
                };
            }
            List<Value> values = new ArrayList<>();
            if (arguments != null) {
                for (Object argument : arguments) {
                    values.add(marshal.toValue(argument, span));
                }
            }
            Value result = function.call(context, values, span);
            Class<?> returned = method.getReturnType();
            return returned == void.class ? null
                    : marshal.toJava(result, returned, "def " + function.name(), context, span);
        };
        return Proxy.newProxyInstance(loader(type), new Class<?>[]{type}, handler);
    }

    /**
     * Загрузчик интерфейса, а не моста: прокси обязан видеть тот интерфейс,
     * который реализует, а загрузчик приложения виден мосту не всегда.
     */
    private static ClassLoader loader(Class<?> type) {
        ClassLoader own = type.getClassLoader();
        return own != null ? own : JavaProxy.class.getClassLoader();
    }
}
