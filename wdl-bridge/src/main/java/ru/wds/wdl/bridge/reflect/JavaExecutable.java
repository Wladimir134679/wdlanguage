package ru.wds.wdl.bridge.reflect;

import ru.wds.wdl.runtime.Foreign;
import ru.wds.wdl.runtime.WdlRuntimeError;
import ru.wds.wdl.source.Span;
import ru.wds.wdl.value.CallContext;
import ru.wds.wdl.value.Value;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Одна вызываемая штука Java: метод или конструктор.
 * <p>
 * Объединены не для краткости — их объединяет сама Java (
 * {@link Executable}), и объединяет по делу: выбор перегрузки, перевод аргументов,
 * упаковка {@code varargs} и заворачивание вылетевшего исключения у метода
 * и у конструктора устроены дословно одинаково. Разделять их значило бы написать
 * этот код дважды.
 *
 * <h2>Вызов идёт через {@link MethodHandle}</h2>
 * {@code Method.invoke} в цикле заметен, а {@code MethodHandle} JIT разворачивает
 * почти в прямой вызов. Ссылка на сам {@link Executable} при этом остаётся: она нужна
 * для сообщений и для случая, когда дескриптор получить не удалось — метод объявлен
 * в классе, чей пакет не экспортирован. Тогда работает {@code invoke}, и это лучше,
 * чем отказ.
 */
public final class JavaExecutable {

    private static final MethodHandles.Lookup LOOKUP = MethodHandles.publicLookup();

    /** Штраф за раскрытую форму {@code varargs}: точная всегда предпочтительнее. */
    private static final int VARARGS_PENALTY = 2;

    private final Executable member;
    private final MethodHandle handle;
    private final Class<?>[] parameters;
    private final boolean varargs;
    private final boolean isStatic;

    private JavaExecutable(Executable member, MethodHandle handle) {
        this.member = Objects.requireNonNull(member, "member");
        this.handle = handle;
        this.parameters = member.getParameterTypes();
        this.varargs = member.isVarArgs();
        this.isStatic = Modifier.isStatic(member.getModifiers());
    }

    static JavaExecutable of(Method method) {
        MethodHandle found;
        try {
            found = fixed(LOOKUP.unreflect(method));
        } catch (IllegalAccessException absent) {
            found = null;
        }
        return new JavaExecutable(method, found);
    }

    static JavaExecutable of(Constructor<?> constructor) {
        MethodHandle found;
        try {
            found = fixed(LOOKUP.unreflectConstructor(constructor));
        } catch (IllegalAccessException absent) {
            found = null;
        }
        return new JavaExecutable(constructor, found);
    }

    /**
     * Дескриптор без сборки хвоста: массив передаётся последним параметром как есть.
     * <p>
     * {@code unreflect} у {@code varargs}-метода отдаёт дескриптор, который сам
     * пакует хвост в массив, — а мост уже упаковал его, зная типы. Двойная упаковка
     * даёт массив из одного массива, и метод получает не то, что просили.
     */
    private static MethodHandle fixed(MethodHandle handle) {
        return handle.isVarargsCollector() ? handle.asFixedArity() : handle;
    }

    public String name() {
        return member instanceof Method method ? method.getName() : "new";
    }

    public boolean isStatic() {
        return isStatic;
    }

    public boolean isConstructor() {
        return member instanceof Constructor<?>;
    }

    /** Сколько аргументов принимает в точной форме. */
    public int count() {
        return parameters.length;
    }

    public boolean varargs() {
        return varargs;
    }

    /** Наименьшее допустимое число аргументов: у {@code varargs} хвост может быть пуст. */
    public int minimum() {
        return varargs ? parameters.length - 1 : parameters.length;
    }

    /** Наибольшее: у {@code varargs} его нет. */
    public int maximum() {
        return varargs ? Integer.MAX_VALUE : parameters.length;
    }

    /**
     * Имена параметров, если класс собран с {@code -parameters}, иначе {@code null}.
     * <p>
     * {@code null}, а не {@code arg0, arg1}: имена нужны затем, чтобы звать
     * по имени, и придуманные имена дали бы {@code new Date(arg0: 2026)} — запись,
     * которая хуже честного отсутствия именованных аргументов.
     */
    public List<String> parameterNames() {
        List<String> names = new ArrayList<>(parameters.length);
        for (var parameter : member.getParameters()) {
            if (!parameter.isNamePresent()) {
                return null;
            }
            names.add(parameter.getName());
        }
        return names;
    }

    /**
     * Насколько хорошо аргументы подходят этой перегрузке; {@link Marshal#IMPOSSIBLE},
     * если не подходят вовсе.
     */
    public int cost(List<Value> arguments, Marshal marshal) {
        if (!varargs) {
            if (arguments.size() != parameters.length) {
                return Marshal.IMPOSSIBLE;
            }
            return sum(arguments, parameters, marshal, 0, parameters.length);
        }
        // Точная форма: массив передали массивом. Проверяется первой, чтобы
        // 'f(list)' у метода f(Object...) не паковалось в массив из одного списка.
        if (arguments.size() == parameters.length) {
            int exact = sum(arguments, parameters, marshal, 0, parameters.length);
            if (exact != Marshal.IMPOSSIBLE) {
                return exact;
            }
        }
        if (arguments.size() < parameters.length - 1) {
            return Marshal.IMPOSSIBLE;
        }
        int head = sum(arguments, parameters, marshal, 0, parameters.length - 1);
        if (head == Marshal.IMPOSSIBLE) {
            return Marshal.IMPOSSIBLE;
        }
        Class<?> element = parameters[parameters.length - 1].getComponentType();
        int tail = 0;
        for (int i = parameters.length - 1; i < arguments.size(); i++) {
            int one = marshal.cost(arguments.get(i), element);
            if (one == Marshal.IMPOSSIBLE) {
                return Marshal.IMPOSSIBLE;
            }
            tail += one;
        }
        return head + tail + VARARGS_PENALTY;
    }

    private static int sum(List<Value> arguments, Class<?>[] types, Marshal marshal,
                           int from, int to) {
        int total = 0;
        for (int i = from; i < to; i++) {
            int one = marshal.cost(arguments.get(i), types[i]);
            if (one == Marshal.IMPOSSIBLE) {
                return Marshal.IMPOSSIBLE;
            }
            total += one;
        }
        return total;
    }

    /**
     * Зовёт: переводит аргументы, вызывает и заворачивает то, что вылетело.
     *
     * @param self    объект-получатель; {@code null} у статического метода
     *                и у конструктора
     * @param subject имя для сообщений: {@code "Date.plusDays()"}
     * @param context контекст вызова — нужен, чтобы аргумент-функция стала
     *                интерфейсом Java, который потом можно позвать
     */
    public Object invoke(Object self, List<Value> arguments, Marshal marshal,
                         String subject, CallContext context, Span span) {
        Object[] converted = convert(arguments, marshal, subject, context, span);
        return Foreign.call(span, subject, null, () -> {
            try {
                return call(self, converted);
            } catch (InvocationTargetException wrapped) {
                // Рефлексия прячет настоящее исключение внутрь своего; для скрипта
                // важно как раз оно, а обёртка не значит ничего.
                throw wrapped.getCause();
            }
        });
    }

    private Object call(Object self, Object[] arguments) throws Throwable {
        if (handle != null) {
            List<Object> all = new ArrayList<>(arguments.length + 1);
            if (self != null && !isStatic && !isConstructor()) {
                all.add(self);
            }
            all.addAll(java.util.Arrays.asList(arguments));
            return handle.invokeWithArguments(all);
        }
        if (member instanceof Constructor<?> constructor) {
            return constructor.newInstance(arguments);
        }
        return ((Method) member).invoke(self, arguments);
    }

    private Object[] convert(List<Value> arguments, Marshal marshal, String subject,
                             CallContext context, Span span) {
        boolean spread = varargs && !(arguments.size() == parameters.length
                && marshal.accepts(arguments.get(arguments.size() - 1),
                        parameters[parameters.length - 1]));
        int fixed = spread ? parameters.length - 1 : parameters.length;
        Object[] result = new Object[parameters.length];
        for (int i = 0; i < fixed; i++) {
            result[i] = marshal.toJava(arguments.get(i), parameters[i],
                    subject + ": аргумент " + (i + 1), context, span);
        }
        if (spread) {
            Class<?> element = parameters[parameters.length - 1].getComponentType();
            int tail = arguments.size() - fixed;
            Object rest = Array.newInstance(element, tail);
            for (int i = 0; i < tail; i++) {
                Array.set(rest, i, marshal.toJava(arguments.get(fixed + i), element,
                        subject + ": аргумент " + (fixed + i + 1), context, span));
            }
            result[parameters.length - 1] = rest;
        }
        return result;
    }

    /**
     * Почему аргументы не подошли <b>именно этой</b> перегрузке.
     * <p>
     * Прогоняет приведение и даёт сказать тому, кто знает подробности:
     * «ожидалось целое число, а здесь 1.5» вместо общего «не подошло ни одному
     * методу Java». Смысл в том, что у большинства методов перегрузка одна,
     * и общий отказ там — потеря диагностики на ровном месте.
     *
     * @return {@code null}, если приведение неожиданно удалось: тогда объяснять
     *         нечего и отказ остаётся общим
     */
    RuntimeException explain(List<Value> arguments, Marshal marshal, String subject, Span span) {
        if (arguments.size() < minimum() || arguments.size() > maximum()) {
            return null;
        }
        try {
            convert(arguments, marshal, subject, null, span);
            return null;
        } catch (WdlRuntimeError refused) {
            return refused;
        }
    }

    /** Подпись для сообщения о неоднозначности: {@code plusDays(long)}. */
    public String describe() {
        return name() + java.util.Arrays.stream(parameters)
                .map(Class::getSimpleName)
                .collect(Collectors.joining(", ", "(", ")"));
    }

    @Override
    public String toString() {
        return describe();
    }
}
