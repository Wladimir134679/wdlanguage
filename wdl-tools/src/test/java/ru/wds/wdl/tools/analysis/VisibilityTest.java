package ru.wds.wdl.tools.analysis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import ru.wds.wdl.source.Source;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Правило видимости языка целиком: имя существует со своей строки, но тело функции
 * видит внешнее целиком, включая объявленное ниже.
 * <p>
 * Обе половины проверяются по отдельности: ошибка в первой заставит редактор
 * предлагать переменную, которой на этой строке ещё нет, ошибка во второй — не даст
 * дополнить рекурсивный вызов.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class VisibilityTest {

    private FileAnalysis analysis;

    private List<String> visibleAt(String code, String marker) {
        analysis = FileAnalysis.of(Source.ofString(code));
        return namesAt(code.indexOf(marker));
    }

    /** То же для последнего вхождения: одна и та же строка часто стоит и внутри, и снаружи. */
    private List<String> visibleAtLast(String code, String marker) {
        analysis = FileAnalysis.of(Source.ofString(code));
        return namesAt(code.lastIndexOf(marker));
    }

    private List<String> namesAt(int offset) {
        return analysis.visibleAt(offset).stream().map(Symbol::name).toList();
    }

    @Test
    @DisplayName("на верхнем уровне объявленное ниже не видно")
    void declaredBelowIsInvisible() {
        List<String> visible = visibleAt("""
                price = 120
                println(price)
                count = 2
                """, "println");

        assertTrue(visible.contains("price"));
        assertFalse(visible.contains("count"), "имя существует с той строки, где его завели");
    }

    @Test
    @DisplayName("из тела функции видно объявленное ниже по файлу")
    void functionBodySeesEverything() {
        List<String> visible = visibleAt("""
                def main() => helper(2)
                def helper(n) => n * 2
                """, "helper(2)");

        assertTrue(visible.contains("helper"), "имя ищется в момент вызова, а не разбора");
        assertTrue(visible.contains("main"), "и своё имя тоже — на этом стоит рекурсия");
    }

    @Test
    @DisplayName("взаимная рекурсия видна в обе стороны")
    void mutualRecursion() {
        String code = """
                def even(n) => n == 0 ? true : odd(n - 1)
                def odd(n) => n == 0 ? false : even(n - 1)
                """;

        assertTrue(visibleAt(code, "odd(n - 1)").contains("odd"));
        assertTrue(visibleAt(code, "even(n - 1)").contains("even"));
    }

    @Test
    @DisplayName("параметры видны везде в теле, локальные — только ниже объявления")
    void parametersAndLocals() {
        String code = """
                def total(price, count) {
                    subtotal = price * count
                    discount = subtotal * 0.1
                    return subtotal - discount;
                }
                """;

        List<String> atSubtotal = visibleAt(code, "price * count");
        assertTrue(atSubtotal.containsAll(List.of("price", "count")));
        assertFalse(atSubtotal.contains("discount"), "объявлено ниже — значит ещё не существует");
        assertTrue(visibleAt(code, "subtotal - discount").contains("discount"));
    }

    @Test
    @DisplayName("имя из блока наружу не выходит")
    void blockKeepsItsNames() {
        String code = """
                if (true) {
                    inner = 1
                    println(inner)
                }
                println(inner)
                """;

        assertTrue(visibleAt(code, "println(inner)").contains("inner"));
        assertFalse(visibleAtLast(code, "println(inner)").contains("inner"),
                "снаружи блока такого имени нет");
    }

    @Test
    @DisplayName("переменная цикла живёт в цикле, счётчик — в заголовке")
    void loopVariables() {
        String code = """
                items = [1, 2]
                for (item in items) {
                    println(item)
                }
                for (i = 0; i < 2; i = i + 1) {
                    println(i)
                }
                println(item)
                """;

        assertTrue(visibleAt(code, "println(item)").contains("item"));
        assertTrue(visibleAt(code, "println(i)").contains("i"));
        assertFalse(visibleAtLast(code, "println(item)").contains("item"),
                "после цикла переменной прохода нет");
    }

    @Test
    @DisplayName("имя 'catch' и ресурс 'use' живут только в своих телах")
    void handlerAndResource() {
        String code = """
                use (file = open("data.txt")) {
                    println(file)
                }
                try {
                    println(1)
                } catch (failure) {
                    println(failure)
                }
                println(file)
                """;

        assertTrue(visibleAt(code, "println(file)").contains("file"));
        assertTrue(visibleAt(code, "println(failure)").contains("failure"));
        assertFalse(visibleAtLast(code, "println(file)").contains("file"));
        assertFalse(visibleAt(code, "println(1)").contains("failure"),
                "имя ошибки существует только в обработчике");
    }

    @Test
    @DisplayName("ближняя область сильнее: имя встречается в ответе один раз")
    void shadowing() {
        String code = """
                name = "снаружи"
                def greet(name) {
                    return name;
                }
                """;

        List<String> visible = visibleAt(code, "return name");
        assertEquals(1, visible.stream().filter(candidate -> candidate.equals("name")).count(),
                "перекрытое имя показывать незачем — обратиться к нему отсюда нельзя");
        assertEquals(SymbolKind.PARAMETER,
                analysis.resolve(code.indexOf("return name") + 7).orElseThrow().kind());
    }

    @Test
    @DisplayName("поля класса видны его методам, а снаружи класса — нет")
    void classFields() {
        String code = """
                class Rect(width, height) {
                    def area() => width * height
                }
                println(width)
                """;

        assertTrue(visibleAt(code, "width * height").containsAll(List.of("width", "height")));
        assertFalse(visibleAt(code, "println(width)").contains("width"));
    }
}
