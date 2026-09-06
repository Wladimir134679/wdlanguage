package ru.wds.wdl.bridge;

import ru.wds.wdl.module.Library;
import ru.wds.wdl.runtime.BuiltinFunction;
import ru.wds.wdl.runtime.Environment;
import ru.wds.wdl.value.Arity;
import ru.wds.wdl.value.ClassValue;
import ru.wds.wdl.value.MemberRegistry;
import ru.wds.wdl.value.MemberSet;
import ru.wds.wdl.value.Signature;
import ru.wds.wdl.value.Value;
import ru.wds.wdl.value.ValueType;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Всё, что приложение отдаёт скрипту, одним объявлением: типы, функции, константы
 * и члены значений.
 * <p>
 * Это обычная {@link Library} — и остаётся ею: имена кладутся в область видимости,
 * отдельной таблицы модулей у языка не появляется. Построитель здесь затем, что
 * <b>установка у всех была одинаковой и писалась руками</b>: собрать класс, спросить
 * его у области (вдруг он там уже есть), положить под своим именем, повторить —
 * и так в каждом модуле, девять раз подряд в {@code sys.gui}. Теперь это одна строка
 * на тип, а правило «один класс на запуск» соблюдается само.
 *
 * <pre>{@code
 * public static Library library() {
 *     return Module.named("sys/net/socket")
 *             .type("Socket", NativeSocket::build)
 *             .type("Server", NativeServerSocket::build)
 *             .function("connect", Arity.exactly(2), (context, args, span) -> ...)
 *             .constant("DEFAULT_PORT", IntValue.of(9088))
 *             .build();
 * }
 * }</pre>
 *
 * <h2>Собирается на запуск</h2>
 * {@code library()} зовут на каждый запуск, и построитель — тоже: классы принадлежат
 * запуску, потому что скрипт вправе писать в их поля ({@code File.mark = 1}).
 * По той же причине фабрика типа — это {@link Factory}, а не готовое значение.
 *
 * <h2>Тип берётся у области, если он там уже есть</h2>
 * {@code std} кладёт {@code File} в корень, {@code sys.io} отдаёт его же, и
 * {@code f is File} обязано быть правдой, откуда бы {@code File} ни взяли. Поэтому
 * перед сборкой модуль спрашивает область: стоит ли там класс с этим именем и того же
 * вида. Область модуля стоит на корне запуска, значит {@code sys.io} видит то, что
 * положил туда {@code std}. Не нашлось — собирается свой, и это верно: приложение,
 * давшее скрипту один {@code sys.io} без {@code std}, получит рабочий {@code File}.
 * <p>
 * Вид класса спрашивается вместе с именем не из педантизма: {@code class File},
 * объявленный самим скриптом, называется так же, и принимать его за свой нельзя.
 * <p>
 * <b>На мост это правило не распространяется</b> — и это записано здесь, а не
 * замолчано. {@code JavaBridge.installTo} кладёт свои имена голым {@code define},
 * не спрашивая область: его классы живут ещё и во внутренних таблицах обёрток,
 * и взять чужой значило бы развести то, что стоит в области, с тем, чем мост
 * оборачивает возвращённые объекты. Для {@code is} расхождение безобидно
 * ({@code conformsTo} у класса над Java-типом отвечает по {@code isAssignableFrom},
 * а не по ссылке), для {@code statics} — нет: {@code time.Date.mark = 1}
 * из двух разных мостов даст два разных {@code mark}.
 *
 * <h2>Порядок объявления — это порядок установки</h2>
 * Типы ставятся по одному и сразу становятся видны области, поэтому второй тип может
 * найти первый ({@code Server} — свой {@code Socket}), а функция — оба. Фабрика для
 * того и получает {@link Environment}: всё, что модулю нужно от соседей, он спрашивает
 * у области, а не получает аргументами через полмодуля.
 *
 * <h2>Чего здесь нет</h2>
 * Ничего, кроме установки. Модулю, которому нужно что-то сверх перечисленного —
 * замкнуть функции на собранный класс, как {@code sys.gui}, — открыт
 * {@link Builder#install}: обычный кусок кода, получающий область. Фасад не должен
 * становиться вторым языком описания библиотек ради последнего процента случаев.
 */
public final class Module implements Library, ru.wds.wdl.value.Documented {

    private final String name;
    private final List<Consumer<Environment>> steps;
    private final List<Runnable> closers;
    /** Описание самого модуля и его имён: читает только редактор. См. {@link Builder#doc}. */
    private final String documentation;
    private final java.util.Map<String, String> documentations;

    private Module(Builder builder) {
        this.name = builder.name;
        this.steps = List.copyOf(builder.steps);
        this.closers = List.copyOf(builder.closers);
        this.documentation = builder.documentation;
        this.documentations = java.util.Map.copyOf(builder.documentations);
    }

    @Override
    public String documentation() {
        return documentation;
    }

    /**
     * Описание имени модуля: {@code io.read} — «читает файл целиком».
     * <p>
     * Спрашивается каталогом там, где само значение описания не несёт: константу
     * и класс кладут в область готовыми, и приписать им фразу можно только рядом
     * с объявлением — здесь.
     */
    @Override
    public String documentation(String member) {
        return documentations.get(member);
    }

    /**
     * Построитель модуля.
     *
     * @param name имя для диагностики и ключ реестра встроенных модулей:
     *             {@code "sys/io"}, {@code "std"}
     */
    public static Builder named(String name) {
        return new Builder(name);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Environment installTo(Environment scope) {
        Objects.requireNonNull(scope, "scope");
        for (Consumer<Environment> step : steps) {
            step.accept(scope);
        }
        return scope;
    }

    /**
     * Отпускает живое, заведённое модулем.
     * <p>
     * Обработчики зовутся по порядку объявления, и <b>один упавший не отменяет
     * остальных</b>: закрытие — последнее, что делает уже отработавший скрипт,
     * и терять на нём открытый сокет из-за чужого исключения незачем. Правило то же,
     * по которому {@code Modules.shutdown} переживает ошибку одной библиотеки
     * и продолжает закрывать другие.
     */
    @Override
    public void close() {
        RuntimeException failure = null;
        for (Runnable closer : closers) {
            try {
                closer.run();
            } catch (RuntimeException error) {
                if (failure == null) {
                    failure = error;
                } else {
                    failure.addSuppressed(error);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    /**
     * Класс, который модуль уже поставил в область.
     * <p>
     * Нужен внутри {@link Builder#install}: функции {@code sys.gui} замыкаются
     * на собранные классы, и взять их надо там же, где они лежат. Модуль ставит типы
     * до этого шага, поэтому здесь класс всегда находится.
     * <p>
     * <b>Не находит — отказ, а не сборка второго.</b> Раньше сюда передавали запасную
     * фабрику «на случай, если не нашлось», и случай этот был объявлен невозможным.
     * Но если бы он всё же настал, ветка собрала бы <b>второй, неидентичный</b> класс
     * — и {@code is} начал бы врать ровно там, ради чего весь этот поиск и заведён.
     * Ветка «не может случиться» обязана падать, а не молча делать не то.
     *
     * @throws IllegalStateException если класса с этим именем в области нет
     */
    public static NativeClass typeIn(Environment scope, String typeName) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(typeName, "typeName");
        NativeClass declared = declaredIn(scope, typeName);
        if (declared == null) {
            throw new IllegalStateException("класса '" + typeName + "' нет в области: "
                    + "его ставит сам модуль, и объявлен он должен быть до install(...)");
        }
        return declared;
    }

    /**
     * Класс этого имени, уже стоящий в области, или {@code null}.
     * <p>
     * Вид класса спрашивается вместе с именем не из педантизма: {@code class File},
     * объявленный самим скриптом, называется так же, и принимать его за свой нельзя.
     */
    private static NativeClass declaredIn(Environment scope, String typeName) {
        return scope.lookup(typeName) instanceof NativeClass declared
                && declared.name().equals(typeName) ? declared : null;
    }

    /**
     * Сборка значения для этого запуска: класса, трейта, чего угодно ещё.
     * <p>
     * Область приходит аргументом, потому что собираемому бывает нужен сосед: трейт
     * {@code Closeable} из прелюдии, ранее объявленный тип, класс из другого модуля.
     */
    @FunctionalInterface
    public interface Factory<T> {

        T build(Environment scope);
    }

    /** Построитель: см. {@link Module}. */
    public static final class Builder {

        private final String name;
        private final List<Consumer<Environment>> steps = new ArrayList<>();
        private final List<Runnable> closers = new ArrayList<>();
        private final java.util.Map<String, String> documentations = new java.util.LinkedHashMap<>();
        private String documentation;
        /** Имя, объявленное последним: к нему относится следующий {@link #doc}. */
        private String described;
        private boolean anyDeclared;

        private Builder(String name) {
            this.name = Objects.requireNonNull(name, "name");
        }

        /**
         * Описание словами — <b>того, что объявлено прямо перед ним</b>:
         *
         * <pre>{@code
         * Module.named("sys/io")
         *         .doc("файлы: чтение, запись, обход каталога")
         *         .function("read", Signature.of(required("path")), body)
         *         .doc("читает файл целиком в строку")
         * }</pre>
         *
         * Первый {@code doc} в цепочке описывает сам модуль — описывать ему больше
         * нечего, ни одного имени ещё не объявлено.
         * <p>
         * Отдельным звеном, а не лишним параметром у каждого объявления: описание
         * появляется не у всех имён и не сразу, а строка «{@code null} вместо фразы»
         * в двадцати вызовах — цена, которую платили бы все ради немногих.
         *
         * @throws IllegalStateException если описывать нечего: {@code doc} стоит
         *                               после {@code install}, {@code members}
         *                               или {@code onClose}
         */
        public Builder doc(String text) {
            Objects.requireNonNull(text, "text");
            if (!anyDeclared) {
                documentation = text;
                return this;
            }
            if (described == null) {
                throw new IllegalStateException("doc(): описывать нечего — предыдущее звено"
                        + " цепочки имени не объявляет");
            }
            documentations.put(described, text);
            return this;
        }

        /**
         * Класс: {@code .type("Socket", scope -> NativeSocket.build())}.
         * <p>
         * Вид указывать не надо и нельзя: класс от приложения теперь один — тот,
         * у которого члены могут прийти хоть лямбдами, хоть от Java-типа
         * ({@code bridge.reflect.FromJava}). Отдельного «класса, открытого мостом»
         * не существует, и перегрузка с {@code Class<T> kind} вместе с ним ушла.
         */
        public Builder type(String typeName, Factory<NativeClass> factory) {
            Objects.requireNonNull(typeName, "typeName");
            Objects.requireNonNull(factory, "factory");
            declares(typeName);
            return step(scope -> {
                NativeClass declared = declaredIn(scope, typeName);
                scope.define(typeName, declared != null ? declared : factory.build(scope));
            });
        }

        /**
         * Трейт от приложения: контракт, который скрипт обязуется выполнить.
         * <p>
         * Ищется в области по тем же правилам, что класс: {@code is} сравнивает формы
         * по ссылке, значит трейт с одним именем в одном запуске должен быть один.
         */
        public Builder trait(String traitName, Factory<NativeTrait> factory) {
            Objects.requireNonNull(traitName, "traitName");
            Objects.requireNonNull(factory, "factory");
            declares(traitName);
            return step(scope -> {
                Value declared = scope.lookup(traitName);
                scope.define(traitName, declared instanceof NativeTrait found
                        && found.name().equals(traitName) ? found : factory.build(scope));
            });
        }

        /** Встроенная функция: имя, число аргументов, тело. */
        public Builder function(String functionName, Arity arity, BuiltinFunction.Body body) {
            Objects.requireNonNull(functionName, "functionName");
            declares(functionName);
            return step(scope -> scope.define(functionName,
                    BuiltinFunction.of(functionName, arity, body)
                            .documented(documentations.get(functionName))));
        }

        /**
         * Встроенная функция с именами параметров — тогда её можно звать именованными
         * аргументами: {@code grid(rows: 2, cols: 3)}.
         */
        public Builder function(String functionName, Signature signature, BuiltinFunction.Body body) {
            Objects.requireNonNull(functionName, "functionName");
            declares(functionName);
            return step(scope -> scope.define(functionName,
                    BuiltinFunction.of(functionName, signature, body)
                            .documented(documentations.get(functionName))));
        }

        /** Готовое значение под именем: {@code .define("VERSION", StringValue.of("1"))}. */
        public Builder define(String valueName, Value value) {
            Objects.requireNonNull(valueName, "valueName");
            Objects.requireNonNull(value, "value");
            declares(valueName);
            return step(scope -> scope.define(valueName, value));
        }

        /** Значение, которое нельзя переприсвоить: {@code io.SEPARATOR}. */
        public Builder constant(String valueName, Value value) {
            Objects.requireNonNull(valueName, "valueName");
            Objects.requireNonNull(value, "value");
            declares(valueName);
            return step(scope -> scope.defineConstant(valueName, value));
        }

        /**
         * Члены всем значениям этого типа: {@code "текст".slug}.
         * <p>
         * Если запуск расширять членами не даёт ({@code scope.members()} пуст),
         * набор молча не ставится — как и раньше, когда это писали руками.
         */
        public Builder members(ValueType type, MemberSet set) {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(set, "set");
            declares(null);
            return step(scope -> {
                MemberRegistry registry = scope.members();
                if (registry != null) {
                    registry.install(type, set);
                }
            });
        }

        /**
         * Всё остальное: кусок установки, написанный руками.
         * <p>
         * Нужен там, где функции замыкаются на собранный класс, — модуль ставит типы
         * до этого шага, поэтому взять их из области можно прямо здесь.
         */
        public Builder install(Consumer<Environment> step) {
            Objects.requireNonNull(step, "step");
            declares(null);
            return step(step);
        }

        /**
         * Что отпустить, когда запуск кончится: пул, клиент, окно, соединение.
         * <p>
         * Вызывается из {@link Library#close()}, то есть хозяином запуска.
         */
        public Builder onClose(Runnable closer) {
            closers.add(Objects.requireNonNull(closer, "closer"));
            declares(null);
            return this;
        }

        public Module build() {
            return new Module(this);
        }

        private Builder step(Consumer<Environment> step) {
            steps.add(step);
            return this;
        }

        /**
         * Запоминает, к чему относится следующий {@link #doc}: {@code null} — звено
         * имени не объявляет, и описывать после него нечего.
         */
        private void declares(String declaredName) {
            described = declaredName;
            anyDeclared = true;
        }
    }
}
