package ru.wds.wdl.tools.catalog;

import ru.wds.wdl.module.Library;
import ru.wds.wdl.runtime.Environment;
import ru.wds.wdl.runtime.ExecutionContext;
import ru.wds.wdl.runtime.members.BuiltinMembers;
import ru.wds.wdl.tools.analysis.SymbolKind;
import ru.wds.wdl.value.Arity;
import ru.wds.wdl.value.ClassValue;
import ru.wds.wdl.value.Documented;
import ru.wds.wdl.value.FunctionValue;
import ru.wds.wdl.value.Member;
import ru.wds.wdl.value.MemberSet;
import ru.wds.wdl.value.Signature;
import ru.wds.wdl.value.TraitValue;
import ru.wds.wdl.value.Value;
import ru.wds.wdl.value.ValueType;
import ru.wds.wdl.value.types.ModuleValue;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Снятие каталога с движка: область видимости, библиотека и реестр модулей —
 * в дескрипторы.
 * <p>
 * <b>Здесь нет ни одного списка имён, написанного руками</b>, и в этом весь смысл
 * слоя. Состав модуля спрашивается у установленной библиотеки, имена параметров —
 * у {@link Signature}, форма класса — у {@link ClassValue}, члены типа —
 * у {@link BuiltinMembers}. Второй, рукописный список рядом с первым умеет ровно
 * одно — разойтись с ним; описание словами — единственное, чего в значениях нет,
 * и потому единственное, что объявляется руками (см. {@link Documented}).
 * <p>
 * <b>Пользовательский код не выполняется никогда.</b> Снимаются только библиотеки
 * движка, и только в выброшенную область: файловый модуль — это чужой скрипт,
 * и каталог про него не знает вовсе.
 */
public final class Catalogs {

    private Catalogs() {
    }

    /**
     * Каталог самого языка: встроенные функции, дескрипторы типов, классы прелюдии.
     * <p>
     * Снимается один раз на процесс и годится без всякой конфигурации: это то,
     * что есть в любом запуске.
     */
    public static Catalog builtins() {
        return Builtin.CATALOG;
    }

    /**
     * Снимок области видимости: то, что реально лежит в корне запуска.
     * <p>
     * Имена читаются без выполнения кода — {@link Environment#lookup(String)}
     * не трогает свойства, в отличие от перегрузки с контекстом.
     *
     * @param origin откуда эти имена взялись; область сама этого не знает
     */
    public static Catalog of(Environment scope, Origin origin) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(origin, "origin");
        List<SymbolDescriptor> roots = new ArrayList<>();
        for (String name : sorted(scope.names())) {
            Value value = scope.lookup(name);
            if (value != null) {
                roots.add(describe(name, value, origin));
            }
        }
        return new SnapshotCatalog(roots);
    }

    /**
     * Каталог встроенных модулей по тому же реестру, из которого их берёт
     * {@code import}. Снимок каждого модуля — ленивый: см. {@link RegistryCatalog}.
     */
    public static Catalog ofModules(Map<String, Supplier<Library>> registry) {
        return new RegistryCatalog(registry);
    }

    /**
     * Тот же каталог, но заявляющий полноту: перечисленным исчерпывается всё, что
     * окажется в корневой области.
     * <p>
     * Отдельным решением, а не свойством снимка, потому что знает об этом не снимок,
     * а тот, кто собрал запуск: имя можно завести и после снятия. Только на такой
     * каталог вправе опереться инспекция «имя не определено».
     */
    public static Catalog complete(Catalog catalog) {
        return new CompleteCatalog(catalog);
    }

    /**
     * Члены типа: {@code a.size}, {@code text.upper()}, {@code f.name}.
     * <p>
     * Таблица одна на процесс и уже перечислима — ни одной новой строки в ядре
     * для этого не понадобилось.
     */
    public static List<MemberDescriptor> members(ValueType type) {
        Objects.requireNonNull(type, "type");
        return Members.BY_TYPE.get(type);
    }

    /**
     * Снимок модуля: библиотека ставится в выброшенную область, имена читаются,
     * библиотека закрывается.
     * <p>
     * <b>Неудачная установка даёт пустой модуль, а не отказ.</b> {@code sys.gui}
     * на машине без графики не соберётся, и это не повод оставить редактор без
     * дополнения во всём остальном файле: путь модуля по-прежнему известен,
     * а состав — нет. Пустой ответ кэшируется вместе с остальными: повторять
     * установку, которая уже не удалась, незачем.
     */
    public static ModuleDescriptor snapshot(String key, Supplier<Library> factory) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(factory, "factory");
        ExecutionContext context = ExecutionContext.fresh();
        Library library = null;
        try {
            library = factory.get();
            Environment scope = context.scope().child();
            library.installTo(scope);
            // Описание берётся у значения, а если оно его не несёт — у самой библиотеки:
            // константу и класс кладут в область готовыми, и фраза про них объявлена
            // рядом с объявлением имени (Module.Builder.doc).
            Documented described = library instanceof Documented documented ? documented : null;
            List<SymbolDescriptor> names = new ArrayList<>();
            for (String name : sorted(scope.namesHere())) {
                Value value = scope.lookupHere(name);
                if (value != null) {
                    names.add(describe(name, value, Origin.MODULE,
                            described == null ? null : described.documentation(name)));
                }
            }
            return new ModuleDescriptor(key, null,
                    described == null ? null : described.documentation(), names);
        } catch (RuntimeException | LinkageError failure) {
            return ModuleDescriptor.empty(key);
        } finally {
            close(library);
            context.closeRun();
        }
    }

    /**
     * Одно имя в дескриптор: вид берётся из значения, а не из записи, — каталог
     * снимается с живой области, где имя уже чем-то стало.
     */
    public static SymbolDescriptor describe(String name, Value value, Origin origin) {
        return describe(name, value, origin, null);
    }

    /**
     * То же, но с описанием со стороны: его подаёт тот, кто имя объявил, когда само
     * значение фразы не несёт — константа, класс из чужого построителя.
     */
    public static SymbolDescriptor describe(String name, Value value, Origin origin,
                                            String declaredDocumentation) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(value, "value");
        String own = documentationOf(value);
        String documentation = own != null ? own : declaredDocumentation;
        return switch (value) {
            case FunctionValue function -> new SymbolDescriptor(name, SymbolKind.FUNCTION,
                    callable(name, function.signature()), documentation, origin);
            case ClassValue type -> new SymbolDescriptor(name, SymbolKind.CLASS,
                    "class " + callable(name, type.signature()), documentation, origin,
                    membersOf(type));
            case TraitValue trait -> new SymbolDescriptor(name, SymbolKind.TRAIT,
                    "trait " + name, documentation, origin, membersOf(trait));
            case ModuleValue module -> new SymbolDescriptor(name, SymbolKind.MODULE,
                    "module " + module.name(), documentation, origin);
            default -> new SymbolDescriptor(name, SymbolKind.CONSTANT,
                    constant(name, value), documentation, origin);
        };
    }

    /** Члены класса: методы с именами параметров, свойства и поля — как их видит скрипт. */
    private static List<MemberDescriptor> membersOf(ClassValue type) {
        Documented described = type instanceof Documented documented ? documented : null;
        List<MemberDescriptor> members = new ArrayList<>();
        for (String method : sorted(type.methodNames())) {
            Signature signature = type.methodSignature(method);
            members.add(MemberDescriptor.method(method, signature.arity(),
                    callable(method, signature), documentationOf(described, method)));
        }
        for (String property : sorted(type.propertyNames())) {
            members.add(MemberDescriptor.property(property, false,
                    documentationOf(described, property)));
        }
        for (String field : sorted(type.fieldNames())) {
            members.add(MemberDescriptor.property(field, false, documentationOf(described, field)));
        }
        return members;
    }

    /** Члены трейта: то, что он обещает всякому, кто его принял. */
    private static List<MemberDescriptor> membersOf(TraitValue trait) {
        Documented described = trait instanceof Documented documented ? documented : null;
        List<MemberDescriptor> members = new ArrayList<>();
        for (String method : sorted(trait.methodNames())) {
            members.add(MemberDescriptor.method(method, Arity.any(),
                    documentationOf(described, method)));
        }
        for (String property : sorted(trait.propertyNames())) {
            members.add(MemberDescriptor.property(property, false,
                    documentationOf(described, property)));
        }
        return members;
    }

    /**
     * Запись вызова: {@code read(path)}, когда имена параметров известны, и
     * {@code println(…)}, когда нет.
     * <p>
     * Имена не выдумываются. Многоточие честно говорит «сюда что-то идёт», а сколько
     * именно — знает {@link Signature#arity()}, и это разные вопросы: подсказка
     * с {@code pow(a, b)} полезна, подсказка с {@code pow(ровно 2)} — нет.
     */
    private static String callable(String name, Signature signature) {
        if (signature.namesKnown()) {
            return name + signature;
        }
        return signature.arity().max() == 0 ? name + "()" : name + "(…)";
    }

    /**
     * Запись константы: {@code SEPARATOR = "\\"}.
     * <p>
     * Значение печатается только у простых типов. Массив и объект бывают какими
     * угодно, а подсказка — одна строка; звать {@code display()} у чужого значения
     * ради неё незачем.
     * <p>
     * Берётся отладочный вид ({@code toString}), а не {@link Value#display()}:
     * в подсказке нужна запись, как она выглядит в коде, — с кавычками у строки,
     * иначе {@code SEPARATOR = \} читается как обрыв строки.
     */
    private static String constant(String name, Value value) {
        return switch (value.type()) {
            case NULL, BOOL, NUMBER, STRING, RANGE -> {
                String shown = value.toString();
                yield shown.length() > 40 || shown.indexOf('\n') >= 0 ? name : name + " = " + shown;
            }
            default -> name;
        };
    }

    private static String documentationOf(Value value) {
        return value instanceof Documented documented ? documented.documentation() : null;
    }

    private static String documentationOf(Documented described, String member) {
        return described == null ? null : described.documentation(member);
    }

    /**
     * Порядок области видимости не обещан никем, а каталог обязан быть устойчивым:
     * дополнение, меняющее порядок от запуска к запуску, читается как случайное.
     */
    private static List<String> sorted(java.util.Collection<String> names) {
        return names.stream().sorted().toList();
    }

    /**
     * Закрытие снятой библиотеки: снимок уже сделан, и ронять его из-за ошибки
     * закрытия выброшенной копии незачем — правило то же, по которому
     * {@code Modules.shutdown} переживает ошибку одной библиотеки.
     */
    private static void close(Library library) {
        if (library == null) {
            return;
        }
        try {
            library.close();
        } catch (RuntimeException | LinkageError ignored) {
            // Закрывать было нечего или не получилось: наружу эта копия не видна.
        }
    }

    /** Снимок языка — один на процесс, потому что и сам язык один. */
    private static final class Builtin {

        private static final Catalog CATALOG = snapshot();

        private static Catalog snapshot() {
            ExecutionContext context = ExecutionContext.fresh();
            try {
                return Catalogs.of(context.scope(), Origin.BUILTIN);
            } finally {
                context.closeRun();
            }
        }
    }

    /** Члены типов — тоже один на процесс: таблица {@link BuiltinMembers} статическая. */
    private static final class Members {

        private static final Map<ValueType, List<MemberDescriptor>> BY_TYPE = build();

        private static Map<ValueType, List<MemberDescriptor>> build() {
            Map<ValueType, List<MemberDescriptor>> table = new EnumMap<>(ValueType.class);
            for (ValueType type : ValueType.values()) {
                MemberSet set = BuiltinMembers.of(type);
                List<MemberDescriptor> members = new ArrayList<>(set.names().size());
                for (String name : sorted(set.names())) {
                    Member member = set.get(name);
                    if (member != null) {
                        members.add(MemberDescriptor.of(member, member.documentation()));
                    }
                }
                table.put(type, List.copyOf(members));
            }
            return Map.copyOf(table);
        }
    }
}
