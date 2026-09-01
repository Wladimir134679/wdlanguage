package ru.wds.wdl.bridge;

import ru.wds.wdl.module.Library;
import ru.wds.wdl.resolve.DeclaredTrait;
import ru.wds.wdl.resolve.NativeTraitShape;
import ru.wds.wdl.resolve.TraitShape;
import ru.wds.wdl.value.Arity;
import ru.wds.wdl.value.PropertyRequirement;
import ru.wds.wdl.value.Requirement;
import ru.wds.wdl.value.TraitValue;
import ru.wds.wdl.value.Value;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Трейт, объявленный приложением: контракт, который скрипт обязуется выполнить.
 * <p>
 * Это ответ на «модуль даёт интерфейс, скрипт его реализует» — и отвечает на него
 * именно трейт, а не наследование: требования проверяются на строке {@code class},
 * а связывает трейт слабее, чем родитель.
 *
 * <h2>Как это выглядит</h2>
 * <pre>{@code
 * NativeTrait handler = NativeTrait.named("Handler")
 *         .requireMethod("onMessage", Arity.exactly(2))
 *         .requireMethod("onClose", Arity.exactly(0))
 *         .requireField("name")
 *         .field("retries", IntValue.of(3))
 *         .build();
 *
 * scope.define(handler.name(), handler);
 * }</pre>
 * <pre>{@code
 * import sys.net as net
 *
 * class EchoHandler(name) with net.Handler {
 *     def onMessage(client, text) => client.send(text)
 *     def onClose() => println("отключился, попыток было ", retries)
 * }
 * }</pre>
 * Забытый {@code onClose} — ошибка на строке {@code class}, тем же текстом и в тот же
 * момент, что у трейта на wdl: проверяет их один и тот же {@code Linker}. Java-часть
 * при этом о скрипте ничего знать не обязана — она получает объект и зовёт
 * {@code instance.owner().method(instance, "onMessage")}, обычное значение-функцию.
 *
 * <h2>Правила те же, что у трейта языка</h2>
 * Трейт устроен как класс, у которого забрали конструктор: в заголовке переменные,
 * в теле методы, и то и другое бывает требованием. Объявленное со значением
 * ({@link Builder#field}) достаётся классу готовым полем; объявленное без значения
 * ({@link Builder#requireField}) — обязанность класса. С методами так же, с одной
 * оговоркой ниже.
 *
 * <h2>Чего у нативного трейта нет</h2>
 * <b>Методов с телом.</b> Метод в плоской таблице класса держит кусок дерева,
 * а у Java-метода дерева нет. Нативный трейт — это требования и заготовки полей;
 * готовые методы даёт класс ({@link NativeClass}), а не трейт.
 *
 * <h2>Собирается на запуск</h2>
 * Как и {@link NativeClass}: {@code is} сравнивает формы по ссылке, поэтому трейт,
 * собранный в {@link Library#installTo}, принадлежит своему запуску — и ничего
 * между запусками не переносит.
 */
public final class NativeTrait implements DeclaredTrait {

    private final NativeTraitShape shape;

    private NativeTrait(Builder builder) {
        this.shape = new NativeTraitShape(builder.name, builder.requiredFields,
                builder.requiredMethods, builder.requiredProperties, builder.fields);
    }

    public static Builder named(String name) {
        return new Builder(name);
    }

    /** Форма трейта: то, чем его связывает {@code Linker} при объявлении класса. */
    public TraitShape shape() {
        return shape;
    }

    /** Поля со значением — те, что достаются подмешавшему классу готовыми. */
    public Map<String, Value> declaredFields() {
        return shape.declaredFields();
    }

    @Override
    public String name() {
        return shape.name();
    }

    @Override
    public List<Requirement> requiredMethods() {
        return shape.requiredMethods();
    }

    @Override
    public List<String> requiredFields() {
        return shape.requiredFields();
    }

    @Override
    public List<PropertyRequirement> requiredProperties() {
        return shape.requiredProperties();
    }

    @Override
    public String toString() {
        return display();
    }

    /** Построитель. Порядок вызовов свободен: у трейта нет заголовка, а значит и порядка. */
    public static final class Builder {

        private final String name;
        private final List<String> requiredFields = new ArrayList<>();
        private final List<Requirement> requiredMethods = new ArrayList<>();
        private final List<PropertyRequirement> requiredProperties = new ArrayList<>();
        private final Map<String, Value> fields = new LinkedHashMap<>();

        private Builder(String traitName) {
            this.name = requireName(traitName, "имя трейта");
        }

        /**
         * Требование: класс обязан объявить такой метод сам.
         *
         * @param methodArity сколько аргументов метод обязан принимать
         */
        public Builder requireMethod(String methodName, Arity methodArity) {
            requireName(methodName, "имя требуемого метода");
            Objects.requireNonNull(methodArity, "arity");
            if (requiredMethods.stream().anyMatch(required -> required.name().equals(methodName))) {
                throw new IllegalArgumentException("метод '" + methodName + "' у трейта '"
                        + name + "' уже требуется");
            }
            requiredMethods.add(new Requirement(methodName, methodArity));
            return this;
        }

        /** Требование: класс обязан объявить такое поле в своём заголовке. */
        public Builder requireField(String fieldName) {
            requireName(fieldName, "имя требуемого поля");
            checkFree(fieldName);
            requiredFields.add(fieldName);
            return this;
        }

        /**
         * Требование: имя должно читаться. Закрыть его класс волен чем угодно —
         * полем в заголовке или свойством с {@code get}.
         */
        public Builder requireReadable(String propertyName) {
            return require(PropertyRequirement.readable(propertyName));
        }

        /**
         * Требование: имя должно читаться <b>и</b> записываться. Закрывает его
         * обычное поле или свойство с {@code get} и {@code set}.
         */
        public Builder requireMutable(String propertyName) {
            return require(PropertyRequirement.mutable(propertyName));
        }

        private Builder require(PropertyRequirement requirement) {
            requireName(requirement.name(), "имя требуемого свойства");
            if (requiredProperties.stream()
                    .anyMatch(known -> known.name().equals(requirement.name()))) {
                throw new IllegalArgumentException("свойство '" + requirement.name()
                        + "' у трейта '" + name + "' уже требуется");
            }
            requiredProperties.add(requirement);
            return this;
        }

        /**
         * Поле с готовым значением: достаётся классу, который подмешал трейт.
         * <p>
         * Значение готовое, а не вычисляемое: у трейта на wdl здесь стоит выражение,
         * и считается оно на каждом создании, а у нативного считать нечего.
         */
        public Builder field(String fieldName, Value value) {
            requireName(fieldName, "имя поля");
            Objects.requireNonNull(value, "value");
            checkFree(fieldName);
            fields.put(fieldName, value);
            return this;
        }

        public NativeTrait build() {
            return new NativeTrait(this);
        }

        private void checkFree(String fieldName) {
            if (fields.containsKey(fieldName) || requiredFields.contains(fieldName)) {
                throw new IllegalArgumentException("поле '" + fieldName + "' у трейта '"
                        + name + "' уже объявлено");
            }
        }

        private static String requireName(String value, String what) {
            Objects.requireNonNull(value, what);
            if (value.isBlank()) {
                throw new IllegalArgumentException(what + " не может быть пустым");
            }
            return value;
        }
    }
}
