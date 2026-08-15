package ru.wds.wdl.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.wds.wdl.ast.Program;
import ru.wds.wdl.diagnostic.Diagnostics;
import ru.wds.wdl.lexer.Lexer;
import ru.wds.wdl.module.ModuleSource;
import ru.wds.wdl.module.ModuleUnits;
import ru.wds.wdl.module.Unit;
import ru.wds.wdl.parser.Parser;
import ru.wds.wdl.source.Source;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ленивая загрузка: файл модуля читается тогда, когда выполняется его {@code import},
 * и не раньше.
 * <p>
 * Ради этого свойства связывание классов и переехало в выполнение. Оно даёт то, чего
 * при подготовке не получить никак: модуля может не быть в момент запуска — его создаст
 * приложение, сгенерирует другой скрипт, отдаст база. Здесь проверяется и само свойство,
 * и то, что скрипт при этом не платит за импорты, до которых не дошёл.
 */
class LazyImportTest {

    private static final String NL = System.lineSeparator();

    /** Источник, который помнит, о чём его спрашивали: по нему и видно, что читается лениво. */
    private static final class Watched implements ModuleSource {

        private final Map<String, String> modules;
        private final List<String> asked = new ArrayList<>();

        Watched(Map<String, String> modules) {
            this.modules = modules;
        }

        @Override
        public Source find(String key) {
            asked.add(key);
            String text = modules.get(key);
            return text == null ? null : new Source(key + ".wdl", text);
        }
    }

    private static String run(String code, ModuleSource source) {
        StringBuilder printed = new StringBuilder();
        ModuleUnits units = new ModuleUnits(source);
        Source script = Source.ofString(code);
        Diagnostics diagnostics = new Diagnostics(script);
        Program program = Parser.parseProgram(Lexer.tokenize(script, diagnostics), diagnostics);
        assertFalse(diagnostics.hasErrors(), () -> "ошибки разбора:\n" + diagnostics.renderAll());
        Unit unit = Unit.of(script, program);

        new Interpreter().run(unit, ExecutionContext.fresh(printed::append).withModules(units));
        return printed.toString();
    }

    @Test
    @DisplayName("модуля нет, но импорт в невызванной функции скрипту не мешает")
    void missingModuleInsideUnusedFunction() {
        Watched source = new Watched(Map.of());

        assertEquals("скрипт работает, плагин ещё не нужен" + NL, run("""
                def loadPlugin() {
                    import plugins.generated
                    return start();
                }

                println("скрипт работает, плагин ещё не нужен")
                """, source));

        assertTrue(source.asked.isEmpty(), "модуль спрашивали, хотя до import дело не дошло: " + source.asked);
    }

    @Test
    @DisplayName("модуль, которого не было при запуске, появляется по ходу работы")
    void moduleAppearsWhileScriptRuns() {
        Map<String, String> generated = new HashMap<>();
        Watched source = new Watched(generated);

        // Приложение создаёт модуль уже после того, как скрипт разобран и запущен.
        // Строго говоря, здесь это делает сам тест — но так же выглядит и генератор
        // кода, и загрузка плагина из базы.
        String code = """
                def usePlugin() {
                    import plugins.greeter as p
                    return p.hello("мир");
                }

                println("до появления модуля")
                """;
        assertEquals("до появления модуля" + NL, run(code, source));

        generated.put("plugins/greeter", "def hello(who) => \"привет, \" + who");
        assertEquals("привет, мир" + NL, run("""
                def usePlugin() {
                    import plugins.greeter as p
                    return p.hello("мир");
                }

                println(usePlugin())
                """, source));
    }

    @Test
    @DisplayName("наследоваться можно от класса модуля, которого при разборе не существовало")
    void inheritFromLateModule() {
        Map<String, String> generated = new HashMap<>();
        Watched source = new Watched(generated);
        generated.put("plugins/shapes", "class Shape(title) { def text() => \"фигура \" + title }");

        assertEquals("фигура круг" + NL, run("""
                def describe() {
                    import plugins.shapes
                    class Circle : Shape("круг")
                    return new Circle().text();
                }

                println(describe())
                """, source));
    }

    @Test
    @DisplayName("модуль не тянет за собой свои импорты, пока до них не дойдёт выполнение")
    void moduleImportsAreLazyToo() {
        Watched source = new Watched(Map.of(
                "lib/front", """
                        def deep() {
                            import back
                            return back();
                        }

                        def shallow() => "мелко"
                        """,
                "lib/back", "def back() => \"глубоко\""));

        assertEquals("мелко" + NL, run("""
                import lib.front as f
                println(f.shallow())
                """, source));

        assertEquals(List.of("lib/front"), source.asked,
                "разбор модуля не должен спрашивать модули, которые он импортирует внутри функций");
    }

    @Test
    @DisplayName("до импорта дошли — тогда и ошибка, на своей строке")
    void missingModuleReportedWhereItIsWritten() {
        Watched source = new Watched(Map.of());

        WdlRuntimeError error = assertThrows(WdlRuntimeError.class, () -> run("""
                def loadPlugin() {
                    import plugins.generated
                    return 1;
                }

                println("до")
                loadPlugin()
                """, source));

        assertEquals("модуль 'plugins/generated' не найден", error.getMessage());
    }
}
