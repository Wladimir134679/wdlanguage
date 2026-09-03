package ru.wds.wdl.parser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import ru.wds.wdl.ast.Program;
import ru.wds.wdl.ast.expr.FunctionExpr;
import ru.wds.wdl.ast.stmt.AssignStmt;
import ru.wds.wdl.ast.stmt.ClassDeclStmt;
import ru.wds.wdl.ast.stmt.DefDeclStmt;
import ru.wds.wdl.ast.stmt.ExprStmt;
import ru.wds.wdl.ast.stmt.Stmt;
import ru.wds.wdl.ast.stmt.UnpackStmt;
import ru.wds.wdl.diagnostic.Diagnostics;
import ru.wds.wdl.lexer.Lexer;
import ru.wds.wdl.source.Source;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Разбор распаковки и пропуска: форма дерева и все ошибки, которые видны прямо в тексте.
 * <p>
 * Форма сравнивается строкой через {@link SExprPrinter}: {@code x, y = *point} →
 * {@code (unpack * (x y) ((* point)))}. Вид записи стоит вторым словом специально —
 * три формы распаковки отличаются в тексте одной звёздочкой, и в ожидании теста
 * эта разница должна быть видна сразу.
 */
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class UnpackParserTest {

    private static Program parse(String code) {
        Source source = Source.ofString(code);
        Diagnostics diagnostics = new Diagnostics(source);
        Program program = Parser.parseProgram(Lexer.tokenize(source, diagnostics), diagnostics);
        assertFalse(diagnostics.hasErrors(), () -> "неожиданные ошибки:\n" + diagnostics.renderAll());
        return program;
    }

    private static Stmt single(String code) {
        Program program = parse(code);
        assertEquals(1, program.statements().size(), "ожидалась одна инструкция");
        return program.statements().get(0);
    }

    /** Форма дерева распаковки скобочной записью. */
    private static String tree(String code) {
        return SExprPrinter.print(assertInstanceOf(UnpackStmt.class, single(code)));
    }

    private static Diagnostics diagnose(String code) {
        Source source = Source.ofString(code);
        Diagnostics diagnostics = new Diagnostics(source);
        Parser.parseProgram(Lexer.tokenize(source, diagnostics), diagnostics);
        return diagnostics;
    }

    private static String errorOf(String code) {
        Diagnostics diagnostics = diagnose(code);
        assertTrue(diagnostics.hasErrors(), () -> "ожидалась ошибка разбора для: " + code);
        return diagnostics.renderAll();
    }

    // --- пропуск '_' ---------------------------------------------------------

    @Test
    @DisplayName("'_' в списке параметров — дырка, а не параметр по имени")
    void holeParameter() {
        FunctionExpr function = assertInstanceOf(DefDeclStmt.class,
                single("def onClick(_, event) => event")).function();

        assertEquals("(def onClick _ event)", SExprPrinter.print(function));
        assertTrue(function.params().get(0).isHole());
        assertFalse(function.params().get(1).isHole());
    }

    @Test
    @DisplayName("дырок в заголовке бывает сколько угодно: сталкиваться им нечем")
    void holesRepeat() {
        FunctionExpr function = assertInstanceOf(DefDeclStmt.class,
                single("def handler(_, _, value) => value")).function();

        assertEquals("(def handler _ _ value)", SExprPrinter.print(function));
        assertEquals(3, function.params().size());
    }

    @Test
    @DisplayName("дырка в заголовке класса занимает позицию, но поля не заводит")
    void holeInClassHeader() {
        ClassDeclStmt declaration = assertInstanceOf(ClassDeclStmt.class,
                single("class Slot(_, y) { }"));

        assertEquals(2, declaration.params().size());
        assertTrue(declaration.params().get(0).isHole());
    }

    @Test
    @DisplayName("читать '_' нельзя: ошибка разбора, а не тихий null при выполнении")
    void holeIsNotReadable() {
        assertTrue(errorOf("println(_)")
                .contains("'_' — это пропуск, а не переменная: читать его нельзя"));
        assertTrue(errorOf("x = _ + 1")
                .contains("'_' — это пропуск, а не переменная: читать его нельзя"));
    }

    @Test
    @DisplayName("у дырки не бывает значения по умолчанию: умолчаться нечему")
    void holeHasNoDefault() {
        assertTrue(errorOf("def f(_ = 1) => 1")
                .contains("у пропуска '_' не может быть значения по умолчанию"));
    }

    @Test
    @DisplayName("в заголовке трейта дырка запрещена: там параметр — это поле")
    void holeIsNotATraitField() {
        assertTrue(errorOf("trait Counted(_) { }")
                .contains("в заголовке трейта пропуск '_' не имеет смысла"));
    }

    // --- форма дерева: по позициям -------------------------------------------

    @Test
    @DisplayName("распаковка массива по позициям")
    void positionalArray() {
        assertEquals("(unpack * (x y) ((* (array 10 20))))", tree("x, y = *[10, 20]"));
    }

    @Test
    @DisplayName("дырки и отказ от остатка: 'n1, n2, _, _, _, n6, *_'")
    void holesAndDroppedRest() {
        assertEquals("(unpack * (n1 n2 _ _ _ n6 (* _)) ((* row)))",
                tree("n1, n2, _, _, _, n6, *_ = *row"));
    }

    @Test
    @DisplayName("остаток с именем собирается в новый массив")
    void namedRest() {
        assertEquals("(unpack * (head (* tail)) ((* items)))", tree("head, *tail = *items"));
    }

    @Test
    @DisplayName("одна цель со звёздочкой — тоже распаковка, а не присваивание")
    void singleTargetIsStillUnpack() {
        assertEquals("(unpack * (only) ((* (array 42))))", tree("only = *[42]"));
        assertEquals("(unpack ** (host) ((** config)))", tree("host = **config"));
    }

    @Test
    @DisplayName("цель — та же, что у присваивания: имя, a.b и a[i]")
    void targetsAreOrdinaryPlaces() {
        assertEquals("(unpack * ((get p \"x\") (get p \"y\")) ((* velocity)))",
                tree("p.x, p.y = *velocity"));
        assertEquals("(unpack * ((get grid 0) (get grid 1)) ((* pair)))",
                tree("grid[0], grid[1] = *pair"));
    }

    @Test
    @DisplayName("маркер относится ко всему выражению-источнику, а не к первому звену")
    void sourceIsAWholeExpression() {
        assertEquals("(unpack ** (x y z) ((** (call (get (call (get (get reacts i) \"getReact\"))"
                        + " \"getCenter\")))))",
                tree("x, y, z = **reacts[i].getReact().getCenter()"));
    }

    // --- форма дерева: по именам и попарно -----------------------------------

    @Test
    @DisplayName("распаковка по именам с остатком")
    void namedUnpack() {
        assertEquals("(unpack ** (host port (** options)) ((** config)))",
                tree("host, port, **options = **config"));
    }

    @Test
    @DisplayName("в '**'-форме ключом служит хвостовое имя цели, и скобки от точки не отличаются")
    void namedTargetsKeepTheirKey() {
        UnpackStmt dot = assertInstanceOf(UnpackStmt.class, single("p.x, p.y = **vec"));
        UnpackStmt brackets = assertInstanceOf(UnpackStmt.class, single("p[\"x\"], p[\"y\"] = **vec"));

        assertEquals("x", dot.targets().get(0).trailingName());
        assertEquals("x", brackets.targets().get(0).trailingName());
    }

    @Test
    @DisplayName("список справа без маркера — попарно")
    void pairwise() {
        assertEquals("(unpack пары (a b) (b a))", tree("a, b = b, a"));
        assertEquals("(unpack пары (a b c) (first (* pair)))", tree("a, b, c = first, *pair"));
    }

    @Test
    @DisplayName("'_ = f()' — вызвать и выбросить: распаковка на одну дырку")
    void singleHoleDiscards() {
        assertEquals("(unpack пары (_) ((call f)))", tree("_ = f()"));
    }

    @Test
    @DisplayName("без запятой и без звёздочки это по-прежнему обычное присваивание")
    void plainAssignmentIsUntouched() {
        assertInstanceOf(AssignStmt.class, single("x = 1"));
        assertInstanceOf(AssignStmt.class, single("x *= 2"));
        assertInstanceOf(ExprStmt.class, single("f(a, b)"));
    }

    // --- ошибки разбора ------------------------------------------------------

    @Test
    @DisplayName("остаток должен стоять последним")
    void restMustBeLast() {
        assertTrue(errorOf("p, *rest, q = *items")
                .contains("остаток '*rest' должен стоять последним в списке целей"));
    }

    @Test
    @DisplayName("остаток в списке целей может быть только один")
    void singleRestOnly() {
        assertTrue(errorOf("u, *first, *second = *items")
                .contains("остаток в списке целей может быть только один"));
    }

    @Test
    @DisplayName("'**rest' в '*'-форме и '*rest' в '**'-форме — ошибка со встречной подсказкой")
    void restMatchesTheStyle() {
        assertTrue(errorOf("n, **rest = *items")
                .contains("'**rest' собирает остаток по именам, а распаковка идёт по позициям"));
        assertTrue(errorOf("n, *rest = **config")
                .contains("'*rest' собирает остаток по позициям, а распаковка идёт по именам"));
    }

    @Test
    @DisplayName("в '**'-форме пропускать нечего: '_' там запрещён")
    void holeIsForbiddenInNamedForm() {
        assertTrue(errorOf("x, _, z = **vector3")
                .contains("в списке по именам пропускать нечего: уберите имя '_'"));
    }

    @Test
    @DisplayName("'**_' ничего не делает и потому запрещён")
    void namedRestHoleDoesNothing() {
        assertTrue(errorOf("host, **_ = **config")
                .contains("'**_' ничего не делает: лишние ключи в '**'-форме и так не ошибка"));
    }

    @Test
    @DisplayName("в '**'-форме у цели обязано быть имя: вычисленный ключ не годится")
    void namedTargetNeedsAName() {
        assertTrue(errorOf("grid[i] = **config")
                .contains("в '**'-форме ключ берётся из имени цели, а у 'grid[i]' имени нет"));
    }

    @Test
    @DisplayName("в попарной форме остатка не бывает: справа список значений, а не источник")
    void noRestInPairwise() {
        assertTrue(errorOf("a, *rest = 1, 2, 3")
                .contains("остаток '*rest' бывает только при распаковке"));
    }

    @Test
    @DisplayName("длина попарного списка сверяется при разборе, когда она видна")
    void pairwiseLengthIsChecked() {
        assertTrue(errorOf("a, b = 1, 2, 3").contains("слева 2 имени, а справа 3 значения"));
        assertTrue(errorOf("a, b, c = 1").contains("слева 3 имени, а справа 1 значение"));
        // За раскрытием сколько угодно значений — считать их будет выполнение.
        assertFalse(diagnose("a, b, c = first, *pair").hasErrors());
    }

    @Test
    @DisplayName("'**' в попарном списке не имеет позиции")
    void namedSpreadHasNoPositionInPairwise() {
        assertTrue(errorOf("a, b = **config, 1")
                .contains("раскрытие по именам '**' в списке справа не имеет позиции"));
    }

    @Test
    @DisplayName("составное присваивание с распаковкой не сочетается")
    void noCompoundUnpack() {
        assertTrue(errorOf("x, y += *v")
                .contains("распаковка не сочетается с '+=': непонятно, к чему прибавлять"));
    }

    @Test
    @DisplayName("целью распаковки бывает только имя или обращение")
    void targetMustBeWritable() {
        assertTrue(errorOf("f(), y = *pair")
                .contains("слева от '=' должно стоять имя переменной или обращение"));
        assertTrue(errorOf("this, y = *pair").contains("'this' нельзя присвоить"));
    }

    @Test
    @DisplayName("сломанная распаковка не съедает следующую строку")
    void recoveryKeepsNextStatement() {
        Source source = Source.ofString("x, y z = *pair\nprintln(1)\n");
        Diagnostics diagnostics = new Diagnostics(source);
        Program program = Parser.parseProgram(Lexer.tokenize(source, diagnostics), diagnostics);

        assertTrue(diagnostics.renderAll().contains("ожидалось '=' после списка целей"));
        assertEquals(2, program.statements().size());
        assertInstanceOf(ExprStmt.class, program.statements().get(1));
    }
}
