package ru.wds.wdl.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import ru.wds.wdl.ast.Program;
import ru.wds.wdl.diagnostic.Diagnostics;
import ru.wds.wdl.lexer.Lexer;
import ru.wds.wdl.parser.Parser;
import ru.wds.wdl.source.Source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Члены значений: {@code a.size}, {@code text.upper}, {@code 213.toString()}.
 * <p>
 * Тесты собраны так, чтобы по ним читалось само правило, а не только его следствия:
 * сначала «что это вообще такое», потом граница свойства и метода, потом порядок
 * разрешения (данные раньше членов) и только затем диагностика.
 */
class MemberTest {

    /** Запускает скрипт и возвращает напечатанное без завершающего перевода строки. */
    private static String run(String code) {
        StringBuilder printed = new StringBuilder();
        Source source = Source.ofString(code);
        Diagnostics diagnostics = new Diagnostics(source);
        Program program = Parser.parseProgram(Lexer.tokenize(source, diagnostics), diagnostics);
        assertFalse(diagnostics.hasErrors(), () -> "ошибки разбора:\n" + diagnostics.renderAll());
        new Interpreter().run(program, ExecutionContext.fresh(printed::append));
        return printed.toString().strip();
    }

    private static String show(String expression) {
        return run("println(" + expression + ")");
    }

    private static WdlRuntimeError errorOf(String code) {
        return assertThrows(WdlRuntimeError.class, () -> run(code));
    }

    @Nested
    @DisplayName("Одно правило: член — это то же обращение по ключу")
    class OneRule {

        @Test
        @DisplayName("точка и скобки читают член одинаково, а имя можно вычислить")
        void memberIsAccess() {
            assertEquals("3", show("[1, 2, 3].size"));
            assertEquals("3", show("[1, 2, 3][\"size\"]"));
            assertEquals("3", show("[1, 2, 3][\"si\" + \"ze\"]"));
        }

        @Test
        @DisplayName("метод — обычное значение: читается в переменную и не теряет получателя")
        void methodIsAValue() {
            assertEquals("[1, 2]", run("a = [1]\npush = a.push\npush(2)\nprintln(a)"));
        }

        @Test
        @DisplayName("каждое чтение метода даёт свою обёртку, как и у метода класса")
        void boundMethodIsFresh() {
            assertEquals("false", show("[1].push == [1].push"));
            assertEquals("false", run("a = [1]\nprintln(a.push == a.push)"));
        }
    }

    @Nested
    @DisplayName("Свойство или метод: верить ли ответу через строку")
    class PropertyOrMethod {

        @Test
        @DisplayName("свойство отвечает о значении, каким оно видно сейчас")
        void propertyReadsNow() {
            assertEquals("3", show("[1, 2, 3].size"));
            assertEquals("1", show("[1, 2, 3].first"));
            assertEquals("true", show("[].empty"));
            assertEquals("5", show("\"Hello\".size"));
            assertEquals("5", show("(-5).abs"));
        }

        @Test
        @DisplayName("менять получателя вправе только метод, и в имени у него глагол")
        void methodChanges() {
            assertEquals("[1, 2, 3]", run("a = [3, 1, 2]\na.sort()\nprintln(a)"));
            assertEquals("[3, 1, 2]", run("a = [3, 1, 2]\nsorted = a.sorted\nprintln(a)"));
        }

        @Test
        @DisplayName("свойство меняется только от действия, написанного в тексте")
        void propertyChangesOnlyByAction() {
            assertEquals("2 3", run("a = [1, 2]\nbefore = a.size\na.push(3)\nprintln(before, \" \", a.size)"));
        }

        @Test
        @DisplayName("пара «свойство даёт новое, метод переставляет на месте»")
        void snapshotAndInPlace() {
            assertEquals("[3, 2, 1] [1, 2, 3]",
                    run("a = [1, 2, 3]\nprintln(a.reversed, \" \", a)"));
            assertEquals("[3, 2, 1] [3, 2, 1]",
                    run("a = [1, 2, 3]\na.reverse()\nprintln(a, \" \", a)"));
        }
    }

    @Nested
    @DisplayName("Наборы типов")
    class Sets {

        @Test
        @DisplayName("массив: свойства-ответы и методы-действия")
        void array() {
            assertEquals("3", show("[1, 2, 3].last"));
            assertEquals("[1, 2, 3]", show("[3, 2, 1].sorted"));
            assertEquals("1, 2", show("[1, 2].join(\", \")"));
            assertEquals("1", show("[\"a\", \"b\"].indexOf(\"b\")"));
            assertEquals("-1", show("[\"a\"].indexOf(\"b\")"));
            assertEquals("true", show("[1, 2].contains(2)"));
            assertEquals("[2, 3]", show("[1, 2, 3, 4].slice(1, 3)"));
            assertEquals("3 [1, 2]", run("a = [1, 2, 3]\nprintln(a.pop(), \" \", a)"));
            assertEquals("[1, 9, 2]", run("a = [1, 2]\na.insert(1, 9)\nprintln(a)"));
            assertEquals("9 [1, 2]", run("a = [1, 9, 2]\nprintln(a.remove(1), \" \", a)"));
            assertEquals("[]", run("a = [1, 2]\na.clear()\nprintln(a)"));
        }

        @Test
        @DisplayName("члены с позицией считают отрицательный аргумент от конца")
        void positionsCountFromTheEnd() {
            // Правило «число значит позицию» одно на весь язык: разойдись здесь
            // член с обращением по индексу — расходились бы уже поведения,
            // а не тексты сообщений.
            assertEquals("[3, 4]", show("[1, 2, 3, 4].slice(-2)"));
            assertEquals("4 [1, 2, 3]", run("a = [1, 2, 3, 4]\nprintln(a.remove(-1), \" \", a)"));
            assertEquals("[1, 2, 9, 3]", run("a = [1, 2, 3]\na.insert(-1, 9)\nprintln(a)"));
            // Вставка отличается от адресации ровно верхней границей: в конец можно.
            assertEquals("[1, 2, 9]", run("a = [1, 2]\na.insert(2, 9)\nprintln(a)"));
            assertTrue(errorOf("[1, 2, 3].insert(-4, 9)").getMessage().contains("наименьший здесь -3"));
        }

        @Test
        @DisplayName("строка неизменяема, поэтому меняющих членов у неё нет вовсе")
        void string() {
            assertEquals("HELLO", show("\"Hello\".upper"));
            assertEquals("hello", show("\"Hello\".lower"));
            assertEquals("hi", show("\"  hi  \".trimmed"));
            assertEquals("[\"a\", \"b\"]", show("\"a-b\".split(\"-\")"));
            assertEquals("true", show("\"Hello\".startsWith(\"He\")"));
            assertEquals("2", show("\"Hello\".indexOf(\"l\")"));
            assertEquals("Hey", show("\"Hello\".replace(\"llo\", \"y\")"));
            assertEquals("ababab", show("\"ab\".repeat(3)"));
            assertEquals("ell", show("\"Hello\".slice(1, 4)"));
            assertEquals("42", show("\"42\".toNumber()"));
            assertEquals("null", show("\"nope\".toNumber()"));
        }

        @Test
        @DisplayName("строка: размер в кодовых единицах, обход — по кодовым точкам")
        void stringUnits() {
            // Суррогатная пара: size считает единицы UTF-16 (так работает индексация,
            // и это O(1)), а chars не рвёт символ пополам. Два вопроса — два ответа.
            assertEquals("2", show("\"😀\".size"));
            assertEquals("1", show("\"😀\".chars.size"));
        }

        @Test
        @DisplayName("число: члены есть, и лексер к обращению у литерала готов")
        void number() {
            assertEquals("213", show("213.toString()"));
            assertEquals("5", show("(0 - 5).abs"));
            assertEquals("2", show("2.7.floor"));
            assertEquals("3", show("2.7.ceil"));
            assertEquals("3.14", show("3.14159.round(2)"));
            assertEquals("-1", show("(0 - 3).sign"));
            assertEquals("true", show("2.integer"));
            assertEquals("false", show("2.5.integer"));
            assertEquals("5", show("9.clamp(1, 5)"));
        }

        @Test
        @DisplayName("унарный минус связывает слабее обращения")
        void unaryMinusBindsLooser() {
            // -5.abs — это -(5.abs), и это то же правило, что в других языках.
            assertEquals("-5", show("-5.abs"));
            assertEquals("5", show("(-5).abs"));
        }

        @Test
        @DisplayName("объект: набор короткий, потому что каждый член отнимает имя ключа")
        void object() {
            assertEquals("2", show("{a: 1, b: 2}.size"));
            assertEquals("[\"a\", \"b\"]", show("{a: 1, b: 2}.keys"));
            assertEquals("[1, 2]", show("{a: 1, b: 2}.values"));
            assertEquals("true", show("{a: 1}.has(\"a\")"));
            assertEquals("1 {}", run("box = {a: 1}\nprintln(box.remove(\"a\"), \" \", box)"));
        }

        @Test
        @DisplayName("универсальный член 'тип' есть у всех, кроме null")
        void typeMember() {
            assertEquals("array", show("[1].type"));
            assertEquals("number", show("(1).type"));
            assertEquals("true", show("\"a\".type == String"));
            assertTrue(errorOf("println(null.type)").getMessage().contains("null"));
        }
    }

    @Nested
    @DisplayName("Порядок разрешения: данные раньше членов")
    class Order {

        @Test
        @DisplayName("ключ объекта перекрывает одноимённый член")
        void dataWins() {
            assertEquals("L", show("{size: \"L\"}.size"));
            assertEquals("1", show("len({size: \"L\"})"));
        }

        @Test
        @DisplayName("len берёт ответ из таблицы типа, поэтому от чужого ключа не ломается")
        void lenIgnoresData() {
            assertEquals("2", show("len({size: \"L\", color: \"red\"})"));
            assertEquals("3", show("len([1, 2, 3])"));
            assertEquals("2", show("len(\"ab\")"));
            // Один член на два способа спросить — разойтись им нечем.
            assertEquals("true", run("a = [1, 2]\nprintln(len(a) == a.size)"));
        }

        @Test
        @DisplayName("у экземпляра поле и метод класса идут раньше члена объекта")
        void instanceFirst() {
            assertEquals("L", run("class Box(size)\nprintln(new Box(\"L\").size)"));
            assertEquals("свой", run("class Box(a) { def keys() => \"свой\" }\n"
                    + "println(new Box(1).keys())"));
            // А чего класс не занял — отвечает набор объекта.
            assertEquals("[\"a\"]", run("class Box(a)\nprintln(new Box(1).keys)"));
        }

        @Test
        @DisplayName("статика класса перекрывает член класса — то же правило")
        void staticsWin() {
            assertEquals("Point", run("class Point(x)\nprintln(Point.name)"));
            assertEquals("своё", run("class Point(x)\nPoint.name = \"своё\"\nprintln(Point.name)"));
        }

        @Test
        @DisplayName("у строки, числа и массива своих ключей нет, поэтому конфликта нет вовсе")
        void noConflictForScalars() {
            assertEquals("4", show("\"abcd\".size"));
            assertEquals("2", show("[1, 2].size"));
        }
    }

    @Nested
    @DisplayName("Путь в обход данных: дескриптор типа")
    class Descriptor {

        @Test
        @DisplayName("член типа виден через дескриптор функцией от получателя")
        void throughDescriptor() {
            assertEquals("1", show("Object.size({size: \"L\"})"));
            assertEquals("2", show("Array.size([1, 2])"));
            assertEquals("ABC", show("String.upper(\"abc\")"));
            assertEquals("[2, 1]", show("Array.reversed([1, 2])"));
        }

        @Test
        @DisplayName("метод через дескриптор берёт получателя первым аргументом")
        void methodThroughDescriptor() {
            assertEquals("[1, 2]", run("a = [1]\nArray.push(a, 2)\nprintln(a)"));
            assertEquals("a-b", show("Array.join([\"a\", \"b\"], \"-\")"));
        }

        @Test
        @DisplayName("получатель чужого типа — внятный отказ, а не поломка внутри")
        void wrongReceiver() {
            assertTrue(errorOf("println(Array.size(\"текст\"))").getMessage()
                    .contains("первым аргументом идёт массив"));
        }

        @Test
        @DisplayName("справка о самом типе лежит отдельно от его членов — под ключом info")
        void infoIsSeparate() {
            assertEquals("number", show("Number.info.name"));
            assertEquals("число", show("Number.info.title"));
            // Поэтому член 'name' у функции и имя типа Function друг другу не мешают.
            assertEquals("f", run("def f() => 1\nprintln(Function.name(f))"));
        }

        @Test
        @DisplayName("промах по дескриптору — ошибка: справочник не отвечает null")
        void missOnDescriptor() {
            assertTrue(errorOf("println(Array.sze)").getMessage().contains("нет члена 'sze'"));
        }
    }

    @Nested
    @DisplayName("Диагностика")
    class Diagnostic {

        @Test
        @DisplayName("промах по имени называет тип, промах и похожее имя")
        void missSuggests() {
            String message = errorOf("println([1].sze)").getMessage();
            assertTrue(message.contains("нет члена 'sze'"), message);
            assertTrue(message.contains("Похоже на 'size'"), message);
        }

        @Test
        @DisplayName("когда похожего нет — перечисляется, что вообще есть")
        void missLists() {
            String message = errorOf("println((1).qqqqqq)").getMessage();
            assertTrue(message.contains("Есть: "), message);
            assertTrue(message.contains("abs"), message);
        }

        @Test
        @DisplayName("строковый ключ у массива — это член, а не индекс")
        void stringKeyIsMember() {
            // Иначе на 'a.size' человек получил бы «индекс должен быть целым числом».
            assertEquals("1", show("[1, 2][\"first\"]"));
            assertTrue(errorOf("println([1, 2].nope)").getMessage().contains("нет члена 'nope'"));
            assertTrue(errorOf("println([1, 2][true])").getMessage().contains("целым числом"));
        }

        @Test
        @DisplayName("свойство со скобками говорит про скобки, а не про тип результата")
        void parenthesesHint() {
            String message = errorOf("println([1, 2].size())").getMessage();
            assertTrue(message.contains("свойство"), message);
            assertTrue(message.contains("скобки лишние"), message);
        }

        @Test
        @DisplayName("подсказки про скобки нет там, где имя перекрыто данными")
        void noHintWhenShadowed() {
            String message = errorOf("box = {size: \"L\"}\nprintln(box.size())").getMessage();
            assertTrue(message.contains("вызвать можно только функцию"), message);
        }

        @Test
        @DisplayName("запись в член — отказ, и он объясняет, есть ли такой член")
        void writeIsRefused() {
            assertTrue(errorOf("a = [1]\na.size = 5").getMessage().contains("только для чтения"));
            assertTrue(errorOf("a = [1]\na.nope = 5").getMessage().contains("такого члена нет"));
            assertTrue(errorOf("\"текст\".size = 1").getMessage().contains("только для чтения"));
        }

        @Test
        @DisplayName("у null членов нет: обращение ломается там, где написано")
        void nullHasNoMembers() {
            assertTrue(errorOf("println(null.size)").getMessage().contains("null"));
        }
    }
}
