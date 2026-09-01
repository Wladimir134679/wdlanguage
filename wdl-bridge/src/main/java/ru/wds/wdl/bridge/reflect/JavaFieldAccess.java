package ru.wds.wdl.bridge.reflect;

import ru.wds.wdl.runtime.Foreign;
import ru.wds.wdl.source.Span;
import ru.wds.wdl.value.CallContext;
import ru.wds.wdl.value.Value;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * Поле Java: прочитать и записать.
 * <p>
 * Отдельно от {@link JavaExecutable}, потому что поле — не вызов: у него нет
 * ни аргументов, ни перегрузок, ни выбора. Общего у них ровно столько, сколько
 * видно здесь — дескриптор с запасным путём через рефлексию.
 */
public final class JavaFieldAccess implements JavaAccess {

    private static final MethodHandles.Lookup LOOKUP = MethodHandles.publicLookup();

    private final Field field;
    private final MethodHandle getter;
    private final MethodHandle setter;

    private JavaFieldAccess(Field field, MethodHandle getter, MethodHandle setter) {
        this.field = field;
        this.getter = getter;
        this.setter = setter;
    }

    static JavaFieldAccess of(Field field) {
        MethodHandle read;
        MethodHandle write;
        try {
            read = LOOKUP.unreflectGetter(field);
        } catch (IllegalAccessException absent) {
            read = null;
        }
        try {
            write = Modifier.isFinal(field.getModifiers()) ? null : LOOKUP.unreflectSetter(field);
        } catch (IllegalAccessException absent) {
            write = null;
        }
        return new JavaFieldAccess(field, read, write);
    }

    public String name() {
        return field.getName();
    }

    public Class<?> type() {
        return field.getType();
    }

    public boolean isStatic() {
        return Modifier.isStatic(field.getModifiers());
    }

    /** Можно ли писать: у {@code final} нельзя, и это не запрет моста, а запрет Java. */
    @Override
    public boolean writable() {
        return !Modifier.isFinal(field.getModifiers());
    }

    /**
     * Чтение по общему протоколу доступа. Переводчик и контекст полю не нужны —
     * у него нет ни аргументов, ни выбора перегрузки, — но подпись одна на всех.
     */
    @Override
    public Object read(Object self, Marshal marshal, String subject,
                       CallContext context, Span span) {
        return get(self, subject, span);
    }

    /** Чтение без лишних аргументов: то, чем пользуется сборка статики. */
    public Object get(Object self, String subject, Span span) {
        return Foreign.call(span, subject, null, () -> getter != null
                ? (isStatic() ? getter.invoke() : getter.invoke(self))
                : field.get(self));
    }

    @Override
    public void write(Object self, Value value, Marshal marshal, String subject,
                      CallContext context, Span span) {
        Object converted = marshal.toJava(value, field.getType(), subject, context, span);
        Foreign.call(span, subject, null, () -> {
            if (setter != null) {
                if (isStatic()) {
                    setter.invoke(converted);
                } else {
                    setter.invoke(self, converted);
                }
            } else {
                field.set(self, converted);
            }
            return null;
        });
    }

    @Override
    public String toString() {
        return field.getDeclaringClass().getSimpleName() + "." + field.getName();
    }
}
