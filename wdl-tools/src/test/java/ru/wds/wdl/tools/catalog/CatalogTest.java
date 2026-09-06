package ru.wds.wdl.tools.catalog;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.wds.wdl.module.Library;
import ru.wds.wdl.runtime.BuiltinFunction;
import ru.wds.wdl.runtime.Environment;
import ru.wds.wdl.runtime.members.BuiltinMembers;
import ru.wds.wdl.tools.analysis.SymbolKind;
import ru.wds.wdl.value.Arity;
import ru.wds.wdl.value.Member;
import ru.wds.wdl.value.MemberSet;
import ru.wds.wdl.value.Signature;
import ru.wds.wdl.value.ValueType;
import ru.wds.wdl.value.types.NullValue;
import ru.wds.wdl.value.types.StringValue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Каталог снимается с движка: ни одного списка имён, написанного руками.
 */
class CatalogTest {

    @Test
    @DisplayName("Встроенные функции попадают в каталог сами")
    void builtinsAreSnapshotted() {
        Catalog catalog = Catalogs.builtins();

        SymbolDescriptor println = catalog.root("println");
        assertNotNull(println, "println обязан быть в каталоге языка");
        assertEquals(SymbolKind.FUNCTION, println.kind());
        assertEquals(Origin.BUILTIN, println.origin());
        // Имён параметров у println нет и быть не может — многоточие честнее выдумки.
        assertEquals("println(…)", println.signature());
        assertTrue(println.isCallable());
    }

    @Test
    @DisplayName("Дескрипторы типов — классы, а не константы")
    void typeDescriptorsAreClasses() {
        SymbolDescriptor number = Catalogs.builtins().root("Number");

        assertNotNull(number);
        assertEquals(SymbolKind.CLASS, number.kind());
        assertTrue(number.signature().startsWith("class Number"), number.signature());
    }

    @Test
    @DisplayName("Классы и трейты прелюдии видны в каталоге языка")
    void preludeIsPartOfTheLanguage() {
        Catalog catalog = Catalogs.builtins();

        SymbolDescriptor exception = catalog.root("Exception");
        assertNotNull(exception, "иерархия ошибок кладётся в область до первой строки скрипта");
        assertEquals(SymbolKind.CLASS, exception.kind());
        // Заголовок класса на wdl знает имена параметров и их умолчания.
        assertTrue(exception.signature().startsWith("class Exception(message = "),
                exception.signature());
        assertNotNull(exception.member("report"), "методы класса перечисляются вместе с ним");

        SymbolDescriptor closeable = catalog.root("Closeable");
        assertNotNull(closeable);
        assertEquals(SymbolKind.TRAIT, closeable.kind());
        assertEquals("trait Closeable", closeable.signature());
    }

    @Test
    @DisplayName("Члены типа перечисляются без запуска")
    void membersOfTypeAreListed() {
        List<MemberDescriptor> members = Catalogs.members(ValueType.ARRAY);

        MemberDescriptor size = find(members, "size");
        assertEquals(MemberDescriptor.Kind.PROPERTY, size.kind());
        assertEquals("size", size.signature());

        MemberDescriptor push = find(members, "push");
        assertEquals(MemberDescriptor.Kind.METHOD, push.kind());
        assertTrue(push.isCallable());
        assertEquals("push(…)", push.signature());
    }

    @Test
    @DisplayName("Снимок помечен снимком: подсказка обязана показать цену")
    void snapshotMembersAreMarked() {
        MemberDescriptor keys = find(Catalogs.members(ValueType.OBJECT), "keys");

        assertEquals(MemberDescriptor.Kind.SNAPSHOT, keys.kind(),
                "keys собирает новый массив на каждое чтение — это видно из члена");
        assertFalse(keys.isCallable());
    }

    @Test
    @DisplayName("Библиотека снимается установкой в выброшенную область")
    void librarySnapshotReadsWhatItInstalls() {
        AtomicBoolean closed = new AtomicBoolean();

        ModuleDescriptor module = Catalogs.snapshot("test/lib", () -> library(closed, new AtomicInteger()));

        assertEquals("test/lib", module.key());
        assertEquals("lib", module.name(), "короткое имя — последнее звено пути");

        SymbolDescriptor read = module.get("read");
        assertNotNull(read);
        assertEquals(SymbolKind.FUNCTION, read.kind());
        assertEquals("read(path)", read.signature(), "имена параметров берутся из сигнатуры");
        assertEquals("читает файл целиком", read.documentation());
        assertEquals(Origin.MODULE, read.origin());

        SymbolDescriptor separator = module.get("SEPARATOR");
        assertNotNull(separator);
        assertEquals(SymbolKind.CONSTANT, separator.kind());
        assertEquals("SEPARATOR = \"/\"", separator.signature());

        assertTrue(closed.get(), "снятая копия библиотеки обязана быть закрыта");
    }

    @Test
    @DisplayName("Модуль снимается лениво и один раз")
    void modulesAreSnapshottedLazilyAndOnce() {
        AtomicInteger installs = new AtomicInteger();
        Catalog catalog = Catalogs.ofModules(Map.of(
                "test/lib", () -> library(new AtomicBoolean(), installs)));

        assertEquals(0, installs.get(), "до вопроса о модуле его никто не устанавливает");

        ModuleDescriptor first = catalog.module("test/lib");
        ModuleDescriptor second = catalog.module("test/lib");

        assertEquals(1, installs.get(), "второй вопрос отвечается из кэша");
        assertSame(first, second);
        assertNull(catalog.module("test/none"));
        assertEquals(List.of("test/lib"), List.copyOf(catalog.moduleKeys()));
    }

    @Test
    @DisplayName("Неудачная установка даёт пустой модуль, а не отказ")
    void brokenLibraryGivesEmptyModule() {
        ModuleDescriptor module = Catalogs.snapshot("test/broken", () -> {
            throw new IllegalStateException("здесь нет графики");
        });

        assertTrue(module.isEmpty());
        assertEquals("test/broken", module.key());
    }

    @Test
    @DisplayName("Снятие каталога ничего не выполняет: тела функций не зовутся")
    void snapshotRunsNothing() {
        AtomicInteger calls = new AtomicInteger();
        Catalogs.snapshot("test/quiet", () -> new Library() {

            @Override
            public String name() {
                return "test/quiet";
            }

            @Override
            public Environment installTo(Environment scope) {
                scope.define("act", BuiltinFunction.of("act", Arity.exactly(0),
                        (context, arguments, span) -> {
                            calls.incrementAndGet();
                            return NullValue.NULL;
                        }));
                return scope;
            }
        });

        assertEquals(0, calls.get(), "каталогу нужна форма функции, а не её работа");
    }

    @Test
    @DisplayName("Члены перечисляются у каждого типа и совпадают с таблицей ядра")
    void everyTypeIsListed() {
        for (ValueType type : ValueType.values()) {
            MemberSet set = BuiltinMembers.of(type);
            List<MemberDescriptor> members = Catalogs.members(type);

            assertEquals(set.names().size(), members.size(), type.title());
            for (MemberDescriptor member : members) {
                Member declared = set.get(member.name());
                assertNotNull(declared, type.title() + "." + member.name());
                assertEquals(declared.isProperty(), !member.isCallable());
                assertEquals(declared.snapshot(),
                        member.kind() == MemberDescriptor.Kind.SNAPSHOT);
                assertEquals(declared.arity(), member.arity());
            }
        }
    }

    @Test
    @DisplayName("Пустой каталог ничего не знает и не притворяется")
    void emptyCatalogKnowsNothing() {
        Catalog empty = Catalog.empty();

        assertTrue(empty.roots().isEmpty());
        assertNull(empty.module("sys/io"));
        assertTrue(empty.members(ValueType.ARRAY).isEmpty());
        assertFalse(empty.complete());
    }

    @Test
    @DisplayName("В сложении первый каталог сильнее")
    void mergedPrefersTheFirst() {
        Catalog mine = Catalogs.of(scopeWith("println", "своё"), Origin.HOST);

        Catalog merged = Catalog.merged(mine, Catalogs.builtins());

        assertEquals("своё", merged.root("println").documentation());
        assertEquals(Origin.HOST, merged.root("println").origin());
        assertNotNull(merged.root("Number"), "остальное берётся из второго каталога");
    }

    @Test
    @DisplayName("Сложение с пустым не меняет каталог")
    void mergedWithEmptyIsTheSame() {
        Catalog builtins = Catalogs.builtins();

        assertSame(builtins, Catalog.merged(builtins, Catalog.empty()));
        assertSame(Catalog.empty(), Catalog.merged(Catalog.empty(), Catalog.empty()));
    }

    @Test
    @DisplayName("Полноту заявляет тот, кто собрал запуск, а не снимок")
    void completenessIsDeclared() {
        Catalog builtins = Catalogs.builtins();

        assertFalse(builtins.complete());
        assertTrue(Catalogs.complete(builtins).complete());
        assertEquals(builtins.roots().size(), Catalogs.complete(builtins).roots().size());
    }

    private static Environment scopeWith(String name, String documentation) {
        Environment scope = ru.wds.wdl.runtime.Scope.root();
        scope.define(name, BuiltinFunction.of(name, ru.wds.wdl.value.Arity.any(),
                (context, arguments, span) -> NullValue.NULL).documented(documentation));
        return scope;
    }

    /** Библиотека на одних типах ядра: каталогу большего и не нужно. */
    private static Library library(AtomicBoolean closed, AtomicInteger installs) {
        return new Library() {

            @Override
            public String name() {
                return "test/lib";
            }

            @Override
            public Environment installTo(Environment scope) {
                installs.incrementAndGet();
                scope.define("read", BuiltinFunction.of("read",
                                Signature.of(Signature.Param.required("path")),
                                (context, arguments, span) -> NullValue.NULL)
                        .documented("читает файл целиком"));
                scope.defineConstant("SEPARATOR", StringValue.of("/"));
                return scope;
            }

            @Override
            public void close() {
                closed.set(true);
            }
        };
    }

    private static MemberDescriptor find(List<MemberDescriptor> members, String name) {
        return members.stream()
                .filter(member -> member.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("нет члена '" + name + "' среди " + members));
    }
}
