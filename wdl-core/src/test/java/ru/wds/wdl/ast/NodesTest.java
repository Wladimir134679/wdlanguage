package ru.wds.wdl.ast;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import ru.wds.wdl.Corpus;
import ru.wds.wdl.ast.expr.AccessExpr;
import ru.wds.wdl.ast.expr.ErrorExpr;
import ru.wds.wdl.ast.expr.Expr;
import ru.wds.wdl.ast.expr.FunctionExpr;
import ru.wds.wdl.ast.stmt.ClassDeclStmt;
import ru.wds.wdl.ast.stmt.DefDeclStmt;
import ru.wds.wdl.ast.stmt.Stmt;
import ru.wds.wdl.diagnostic.Diagnostics;
import ru.wds.wdl.lexer.Lexer;
import ru.wds.wdl.parser.Parser;
import ru.wds.wdl.source.Source;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Единый обход дерева: состав детей, путь до курсора, поиск объявления по имени. */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class NodesTest {

    /** Файл, где встречается каждый вид узла: на нём проверяется полнота обхода. */
    private static final String EVERYTHING = """
            import lib.math as math

            const LIMIT = 10

            @{table: "users"}
            class User(@{column: "id"} id, name = "гость", *tags, **extra) : Person(name)
                    with Printable {
                def User() { this.created = true }
                def greet(greeting = "привет") => greeting + ", " + name
                def User.guest() => new User(0)
                property title => name + "!"
                property level = 1 {
                    def get() => field
                    def set(value) { field = value }
                }
            }

            trait Printable {
                def print()
                def label() => "печатное"
            }

            extend Array {
                property second => this[1]
                def firstOr(fallback) => len(this) > 0 ? this[0] : fallback
            }

            @[math.trace("info")]
            def process(items, *rest) {
                total = 0
                for (i = 0; i < len(items); i = i + 1) {
                    total += items[i]
                    if (total > LIMIT) { break } else { continue }
                }
                for (key, item in items) {
                    println(key, item)
                }
                while (total > 0) { total -= 1 }
                first, second, **others = **{a: 1, b: 2}
                x, y = *[1, 2]
                handler = def(event) => event
                data = {name: "имя", "ключ": [1, 2, *items], **extra}
                use (file = open("data.txt")) {
                    defer file.close()
                    try {
                        throw new Error("нет")
                    } catch (e is Error, math.Fail) {
                        println(e)
                    } finally {
                        println("готово")
                    }
                }
                safe = try? parse(data)
                sure = try! parse(data)
                kind = match (total) {
                    case 0 => "пусто"
                    case > LIMIT if handler != null => "много"
                    else => "обычно"
                }
                match (kind) {
                    case "пусто" { println("ничего") }
                    else { println(kind) }
                }
                return -total + ~1;
            }
            """;

    private static Program parse(Source source) {
        Diagnostics diagnostics = new Diagnostics(source);
        return Parser.parseProgram(Lexer.tokenize(source, diagnostics), diagnostics);
    }

    private static Program parse(String code) {
        Source source = Source.ofString(code);
        Diagnostics diagnostics = new Diagnostics(source);
        Program program = Parser.parseProgram(Lexer.tokenize(source, diagnostics), diagnostics);
        assertFalse(diagnostics.hasErrors(), () -> "неожиданные ошибки:\n" + diagnostics.renderAll());
        return program;
    }

    @Test
    @DisplayName("каждый узел обходится ровно один раз")
    void everyNodeVisitedOnce() {
        for (Path file : Corpus.all()) {
            Set<Node> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            Nodes.walk(parse(Corpus.sourceOf(file)), node ->
                    assertTrue(seen.add(node), () -> "узел встретился дважды: " + node
                            + " в " + file.getFileName()));
        }
    }

    @Test
    @DisplayName("у составных узлов дети есть, и они идут слева направо")
    void childrenAreOrdered() {
        Program program = parse(EVERYTHING);
        List<Node> composite = new ArrayList<>();

        Nodes.walk(program, node -> {
            List<Node> children = Nodes.children(node);
            if (!children.isEmpty()) {
                composite.add(node);
            }
            int previous = -1;
            for (Node child : children) {
                if (child.span().isNone()) {
                    continue;
                }
                assertTrue(child.span().start() >= previous,
                        () -> "дети идут не слева направо у " + node);
                previous = child.span().start();
            }
        });

        // Проверка от обратного: если бы ветка switch вернула пустой список там, где
        // дети есть, компилятор бы этого не заметил — а счёт составных узлов заметит.
        assertTrue(composite.size() > 100, "в файле со всеми конструкциями составных узлов много");
    }

    @Test
    @DisplayName("в дереве есть все три ветви: выражения, инструкции и фрагменты")
    void allBranchesAppear() {
        Program program = parse(EVERYTHING);
        boolean[] found = new boolean[3];

        Nodes.walk(program, node -> {
            switch (node) {
                case Program ignored -> { }
                case Expr ignored -> found[0] = true;
                case Stmt ignored -> found[1] = true;
                case Fragment ignored -> found[2] = true;
            }
        });

        assertTrue(found[0] && found[1] && found[2], "обход обязан доходить до всех ветвей");
    }

    @Test
    @DisplayName("путь до курсора непрерывен: каждый следующий — ребёнок предыдущего")
    void pathIsContinuous() {
        for (Path file : Corpus.all()) {
            Source source = Corpus.sourceOf(file);
            Program program = parse(source);
            for (int offset = 0; offset <= source.length(); offset++) {
                List<Node> path = Nodes.pathAt(program, offset);
                for (int i = 1; i < path.size(); i++) {
                    Node parent = path.get(i - 1);
                    Node child = path.get(i);
                    assertTrue(Nodes.children(parent).contains(child),
                            () -> "разрыв пути в " + file.getFileName() + ": " + child
                                    + " не ребёнок " + parent);
                }
            }
        }
    }

    @Test
    @DisplayName("поиск по смещению не падает на недописанном тексте")
    void deepestAtSurvivesBrokenCode() {
        for (Path file : Corpus.malformed()) {
            Source source = Corpus.sourceOf(file);
            Program program = parse(source);
            for (int offset = 0; offset <= source.length(); offset++) {
                Nodes.deepestAt(program, offset);
            }
        }
    }

    @Test
    @DisplayName("смещение внутри имени даёт то объявление, чьё это имя")
    void declarationNameAt() {
        String code = "def total(price, count) => price * count";
        Program program = parse(code);

        Node function = Nodes.declarationNameAt(program, code.indexOf("total") + 2);
        assertInstanceOf(FunctionExpr.class, function);

        Node parameter = Nodes.declarationNameAt(program, code.indexOf("price") + 2);
        FunctionExpr.Param param = assertInstanceOf(FunctionExpr.Param.class, parameter);
        assertEquals("price", param.name());

        assertEquals(null, Nodes.declarationNameAt(program, code.indexOf("=>")),
                "стрелка ничьё имя не образует");
    }

    @Test
    @DisplayName("самый глубокий узел под курсором — тот, что написан в этом месте")
    void deepestAt() {
        String code = "class Rect(width) { def area() => width * width }";
        Program program = parse(code);

        Node inName = Nodes.deepestAt(program, code.indexOf("area") + 1);
        assertInstanceOf(FunctionExpr.class, inName);

        Node inParam = Nodes.deepestAt(program, code.indexOf("width") + 1);
        assertInstanceOf(FunctionExpr.Param.class, inParam);

        assertEquals(null, Nodes.deepestAt(program, code.length() + 1), "за концом файла узла нет");
    }

    @Test
    @DisplayName("курсор в конце недописанной строки попадает в тот узел, который дополняют")
    void caretAtEndOfDocument() {
        String code = "x = obj.";
        Program program = parse(Source.ofString(code));

        assertEquals(null, Nodes.deepestAt(program, code.length()),
                "строго внутри документа этой позиции нет: интервал полуоткрыт");

        Node caret = Nodes.deepestAtCaret(program, code.length());
        assertNotNull(caret, "курсор после точки обязан попасть в узел: там и дополняют");
        assertInstanceOf(ErrorExpr.class, caret);
        assertInstanceOf(AccessExpr.class, Nodes.pathAtCaret(program, code.length())
                .get(Nodes.pathAtCaret(program, code.length()).size() - 2),
                "а над ним — то обращение, чей ключ дополняют");
    }

    @Test
    @DisplayName("пустой узел на месте недописанного накрывает свою точку")
    void deepestAtEmptyNode() {
        String code = "{ x = obj.\n}";
        Program program = parse(Source.ofString(code));

        Node node = Nodes.deepestAtCaret(program, code.indexOf('.') + 1);
        assertNotNull(node, "точка после '.' принадлежит пустому узлу-ошибке");
        assertInstanceOf(ErrorExpr.class, node);
        assertEquals(0, node.span().length());
    }

    @Test
    @DisplayName("путь ведёт от корня к узлу через настоящих родителей")
    void pathAtNamesContainer() {
        String code = "class Rect(width) { def area() => width * width }";
        Program program = parse(code);

        List<Node> path = Nodes.pathAt(program, code.indexOf("area") + 1);
        assertSame(program, path.get(0));
        assertInstanceOf(ClassDeclStmt.class, path.get(1));
        assertInstanceOf(FunctionExpr.class, path.get(path.size() - 1));
    }

    @Test
    @DisplayName("объявления верхнего уровня перечисляются одним обходом")
    void topLevelDeclarations() {
        Program program = parse("def a() => 1\nconst B = 2\nclass C() { }");

        List<String> names = new ArrayList<>();
        Nodes.walk(program, node -> {
            if (node instanceof DefDeclStmt || node instanceof ClassDeclStmt) {
                names.add(String.valueOf(Nodes.nameSpanOf(node) != null));
            }
        });

        assertEquals(List.of("true", "true"), names, "у объявлений есть место имени");
    }
}
