package ru.wds.wdl.interop;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.wds.wdl.ast.Program;
import ru.wds.wdl.diagnostic.Diagnostics;
import ru.wds.wdl.interop.samples.Counter;
import ru.wds.wdl.interop.samples.Handlers;
import ru.wds.wdl.interop.samples.Overloaded;
import ru.wds.wdl.lexer.Lexer;
import ru.wds.wdl.module.NativeModules;
import ru.wds.wdl.module.Unit;
import ru.wds.wdl.parser.Parser;
import ru.wds.wdl.runtime.ErrorKind;
import ru.wds.wdl.runtime.ExecutionContext;
import ru.wds.wdl.runtime.Interpreter;
import ru.wds.wdl.runtime.WdlRuntimeError;
import ru.wds.wdl.source.Source;
import ru.wds.wdl.value.Value;

import java.time.LocalDate;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Java-класс и Java-объект глазами скрипта.
 * <p>
 * Проверяется главное обещание моста: для языка это <b>обычный класс и обычный
 * объект</b>. Ничего специального в скрипте не пишется — {@code new}, точка,
 * {@code is}, {@code println} работают так же, как с классом на wdl.
 */
class JavaBridgeTest {

    private static final String NL = System.lineSeparator();

    private String run(String code, Consumer<JavaBridge.Builder> setup) {
        JavaBridge.Builder builder = JavaBridge.open();
        setup.accept(builder);
        return run(code, builder.build(), Map.of());
    }

    private String run(String code, JavaBridge bridge, Map<String, Object> objects) {
        StringBuilder printed = new StringBuilder();
        ExecutionContext context = ExecutionContext.fresh(printed::append)
                .withNativeModules(NativeModules.of(Map.of("sys/java", bridge::lookupModule)));
        bridge.installTo(context.scope());
        objects.forEach((name, object) -> context.scope().define(name, bridge.wrap(object)));

        Source source = Source.ofString(code);
        Diagnostics diagnostics = new Diagnostics(source);
        Program program = Parser.parseProgram(Lexer.tokenize(source, diagnostics), diagnostics);
        assertFalse(diagnostics.hasErrors(), () -> "ошибки разбора:\n" + diagnostics.renderAll());
        new Interpreter().run(Unit.of(source, program), context);
        return printed.toString();
    }

    private WdlRuntimeError errorOf(String code, Consumer<JavaBridge.Builder> setup) {
        return assertThrows(WdlRuntimeError.class, () -> run(code, setup));
    }

    // --- готовый объект -----------------------------------------------------

    @Test
    @DisplayName("Готовый объект приходит в скрипт и отвечает на методы")
    void wrappedObject() {
        JavaBridge bridge = JavaBridge.open().expose(Counter.class).build();
        assertEquals("0" + NL + "0" + NL + "1" + NL + "счёт=1" + NL,
                run("""
                        println(c.getValue())
                        println(c.getAndIncrement())
                        println(c.getValue())
                        println(c)
                        """, bridge, Map.of("c", new Counter("счёт"))));
    }

    @Test
    @DisplayName("Печать обёртки — это toString объекта")
    void printingUsesToString() {
        JavaBridge bridge = JavaBridge.open().expose(Counter.class).build();
        assertEquals("счёт=5" + NL, run("println(c)", bridge, Map.of("c", counter(5))));
    }

    @Test
    @DisplayName("Обёртка — это объект языка: typeof даёт object")
    void wrapperIsObject() {
        JavaBridge bridge = JavaBridge.open().expose(Counter.class).build();
        assertEquals("object|true" + NL,
                run("""
                        println(typeof(c), "|", c is Counter)
                        """, bridge, Map.of("c", counter(0))));
    }

    @Test
    @DisplayName("Публичное поле читается точкой, final писать нельзя")
    void publicField() {
        JavaBridge bridge = JavaBridge.open().expose(Counter.class).build();
        assertEquals("счёт" + NL, run("println(c.name)", bridge, Map.of("c", counter(0))));

        WdlRuntimeError error = assertThrows(WdlRuntimeError.class,
                () -> run("c.name = \"другое\"", bridge, Map.of("c", counter(0))));
        assertTrue(error.getMessage().contains("только для чтения"), error.getMessage());
    }

    @Test
    @DisplayName("Неоткрытый тип оборачивается только при wrapUnknown")
    void wrapUnknown() {
        JavaBridge strict = JavaBridge.open().expose(Counter.class).build();
        WdlRuntimeError refused = assertThrows(WdlRuntimeError.class,
                () -> run("println(o)", strict, Map.of("o", new Overloaded())));
        assertEquals(ErrorKind.TYPE, refused.kind());
        assertTrue(refused.getMessage().contains("Overloaded"), refused.getMessage());

        JavaBridge open = JavaBridge.open()
                .expose(Counter.class)
                .policy(JavaPolicy.builder().wrapUnknown(true).build())
                .build();
        assertEquals("long:5" + NL,
                run("println(o.take(5))", open, Map.of("o", new Overloaded())));
    }

    // --- создание из скрипта ------------------------------------------------

    @Test
    @DisplayName("new создаёт Java-объект, конструктор выбирается по аргументам")
    void construction() {
        assertEquals("a=0|b=7" + NL, run("""
                println(new Counter("a"), "|", new Counter("b", 7))
                """, bridge -> bridge.expose(Counter.class)));
    }

    @Test
    @DisplayName("Схема без конструкторов отказывает внятно и называет фабрики")
    void constructionRefused() {
        WdlRuntimeError error = errorOf("new Counter(\"a\")", bridge -> bridge.expose(
                JavaSchema.allOf(Counter.class).noConstructors().factory("from").build()));
        assertEquals(ErrorKind.CALL, error.kind());
        assertTrue(error.getMessage().contains("схема запрещает конструкторы"), error.getMessage());
        assertTrue(error.getMessage().contains("from"), error.getMessage());
    }

    @Test
    @DisplayName("Фабрика схемы стоит полем класса — как def User.of в языке")
    void factory() {
        assertEquals("from=3" + NL, run("println(Counter.from(3))",
                bridge -> bridge.expose(JavaSchema.allOf(Counter.class)
                        .noConstructors().factory("from").build())));
    }

    // --- статика ------------------------------------------------------------

    @Test
    @DisplayName("Константа класса видна, статический метод зовётся через класс")
    void statics() {
        assertEquals("10|from=1" + NL, run("""
                println(Counter.LIMIT, "|", Counter.from(1))
                """, bridge -> bridge.expose(Counter.class)));
    }

    @Test
    @DisplayName("Писать в статику Java-класса нельзя")
    void staticsAreFrozen() {
        WdlRuntimeError error = errorOf("Counter.LIMIT = 5", bridge -> bridge.expose(Counter.class));
        assertTrue(error.getMessage().contains("Counter"), error.getMessage());
    }

    // --- схема --------------------------------------------------------------

    @Test
    @DisplayName("Схема показывает только перечисленное")
    void schemaHidesRest() {
        String code = """
                c = new Counter("s", 1)
                println(c.getValue(), "|", c.getAndIncrement)
                """;
        assertEquals("1|null" + NL, run(code, bridge -> bridge.expose(
                JavaSchema.of(Counter.class).method("getValue").build())));
    }

    @Test
    @DisplayName("Пара аксессоров становится свойством по просьбе схемы")
    void beanProperty() {
        assertEquals("1" + NL + "4" + NL, run("""
                c = new Counter("s", 1)
                println(c.value)
                c.value = 4
                println(c.value)
                """, bridge -> bridge.expose(
                        JavaSchema.of(Counter.class).bean("value").build())));
    }

    @Test
    @DisplayName("Метод под другим именем: methodAs переименовывает")
    void renamedMethod() {
        assertEquals("2" + NL, run("""
                c = new Counter("s", 2)
                println(c.значение())
                """, bridge -> bridge.expose(
                        JavaSchema.of(Counter.class).methodAs("значение", "getValue").build())));
    }

    @Test
    @DisplayName("readOnly запрещает запись в поле, которое Java писать разрешает")
    void readOnlyProperty() {
        WdlRuntimeError error = errorOf("""
                c = new Counter("s", 1)
                c.value = 9
                """, bridge -> bridge.expose(
                        JavaSchema.of(Counter.class).bean("value").readOnly("value").build()));
        assertTrue(error.getMessage().contains("только для чтения"), error.getMessage());
    }

    // --- иерархия -----------------------------------------------------------

    @Test
    @DisplayName("is отвечает по живой иерархии Java — и для интерфейса")
    void isChecksJavaHierarchy() {
        assertEquals("true|true" + NL, run("""
                d = Date.of(2026, 8, 29)
                println(d is Date, "|", d is Comparable)
                """, bridge -> bridge
                        .expose(JavaSchema.allOf(LocalDate.class).as("Date").build())
                        .exposeTrait(Comparable.class, "Comparable")));
    }

    @Test
    @DisplayName("Открытый тип служит шаблоном обёртки для своих наследников")
    void closestExposedTypeWins() {
        assertEquals("2026-09-01" + NL, run("""
                println(Date.of(2026, 8, 29).plusDays(3))
                """, bridge -> bridge.expose(
                        JavaSchema.allOf(LocalDate.class).as("Date").build())));
    }

    // --- запреты ------------------------------------------------------------

    @Test
    @DisplayName("getClass не виден даже когда открыт весь класс")
    void getClassIsNeverVisible() {
        assertEquals("null" + NL, run("println(c.getClass)",
                JavaBridge.open().expose(Counter.class).build(), Map.of("c", counter(0))));
    }

    @Test
    @DisplayName("Тип из чёрного списка не открывается вовсе")
    void forbiddenTypeIsRefused() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> JavaBridge.open().expose(System.class).build());
        assertTrue(error.getMessage().contains("не открывается"), error.getMessage());
    }

    @Test
    @DisplayName("Исключение Java ловится скриптом как JavaException")
    void javaExceptionIsCatchable() {
        assertEquals("поймали" + NL, run("""
                c = new Counter("s")
                try {
                    c.fail()
                } catch (e) {
                    println("поймали")
                }
                """, bridge -> bridge.expose(Counter.class)));
    }

    // --- функции скрипта в Java ---------------------------------------------

    @Test
    @DisplayName("Функция скрипта становится Comparator и работает в сортировке Java")
    void functionBecomesComparator() {
        assertEquals("[3, 2, 1]" + NL, run("""
                h = new Handlers()
                println(h.sorted([1, 3, 2], def (a, b) => b - a))
                """, bridge -> bridge.expose(Handlers.class)));
    }

    @Test
    @DisplayName("Функция скрипта становится Runnable, вывод идёт туда же, куда обычный")
    void functionBecomesRunnable() {
        assertEquals("тик" + NL + "тик" + NL + "готово" + NL, run("""
                h = new Handlers()
                println(h.repeat(2, def () => println("тик")))
                """, bridge -> bridge.expose(Handlers.class)));
    }

    @Test
    @DisplayName("Обработчик живёт дольше вызова — и работает из чужого потока")
    void handlerOutlivesCall() {
        assertEquals("из потока" + NL, run("""
                h = new Handlers()
                h.keep(def () => println("из потока"))
                h.callKeptInThread()
                """, bridge -> bridge.expose(Handlers.class)));
    }

    @Test
    @DisplayName("Функция возвращает значение, и оно переводится в тип метода Java")
    void functionResultIsConverted() {
        assertEquals("[\"1!\", \"2!\"]" + NL, run("""
                h = new Handlers()
                println(h.map(["1", "2"], def (s) => s + "!"))
                """, bridge -> bridge.expose(Handlers.class)));
    }

    @Test
    @DisplayName("Интерфейс с двумя методами одной функцией не закрыть")
    void twoMethodsAreRefused() {
        WdlRuntimeError error = errorOf("""
                h = new Handlers()
                h.twoMethods(def () => "нет")
                """, bridge -> bridge.expose(Handlers.class));
        assertEquals(ErrorKind.TYPE, error.kind());
    }

    // --- доступ по имени ----------------------------------------------------

    @Test
    @DisplayName("Без разрешения политики тип по имени не достаётся")
    void lookupIsClosedByDefault() {
        JavaBridge bridge = JavaBridge.open().build();
        WdlRuntimeError error = assertThrows(WdlRuntimeError.class, () -> run("""
                import sys.java as java
                java.type("java.time.LocalDate")
                """, bridge, Map.of()));
        assertTrue(error.getMessage().contains("не разрешило"), error.getMessage());
    }

    @Test
    @DisplayName("Разрешённый пакет открывается по имени, чужой — нет")
    void lookupWithPermission() {
        JavaBridge bridge = JavaBridge.open()
                .policy(JavaPolicy.builder().allowLookup("java.time").wrapUnknown(true).build())
                .build();
        assertEquals("2026-08-29" + NL, run("""
                import sys.java as java
                Date = java.type("java.time.LocalDate")
                println(Date.of(2026, 8, 29))
                """, bridge, Map.of()));

        WdlRuntimeError error = assertThrows(WdlRuntimeError.class, () -> run("""
                import sys.java as java
                java.type("java.io.File")
                """, bridge, Map.of()));
        assertTrue(error.getMessage().contains("не разрешило"), error.getMessage());
    }

    @Test
    @DisplayName("Чёрный список не обходится и по имени")
    void lookupCannotReachForbidden() {
        JavaBridge bridge = JavaBridge.open()
                .policy(JavaPolicy.builder().allowLookup("java.lang").build())
                .build();
        WdlRuntimeError error = assertThrows(WdlRuntimeError.class, () -> run("""
                import sys.java as java
                java.type("java.lang.System")
                """, bridge, Map.of()));
        assertTrue(error.getMessage().contains("ни при какой политике"), error.getMessage());
    }

    // --- проверка схемы при сборке ------------------------------------------

    @Test
    @DisplayName("Опечатка в имени метода — отказ при старте, а не null у автора скрипта")
    void schemaIsCheckedAtBuild() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> JavaBridge.open()
                        .expose(JavaSchema.of(Counter.class).method("getVlaue").build())
                        .build());
        assertTrue(error.getMessage().contains("getVlaue"), error.getMessage());
        assertTrue(error.getMessage().contains("Counter"), error.getMessage());
    }

    @Test
    @DisplayName("Свойство без пары аксессоров — отказ, и в нём сказано, чего не хватает")
    void beanWithoutAccessorsIsRefused() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> JavaBridge.open()
                        .expose(JavaSchema.of(Counter.class).bean("размер").build())
                        .build());
        assertTrue(error.getMessage().contains("getРазмер()"), error.getMessage());
    }

    @Test
    @DisplayName("readOnly у имени, которого нет, — тоже отказ при старте")
    void readOnlyOfUnknownIsRefused() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> JavaBridge.open()
                        .expose(JavaSchema.of(Counter.class).readOnly("value").build())
                        .build());
        assertTrue(error.getMessage().contains("value"), error.getMessage());
    }

    private static Counter counter(int start) {
        return new Counter("счёт", start);
    }
}
