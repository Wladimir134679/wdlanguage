package ru.wds.wdl.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import ru.wds.wdl.ast.Program;
import ru.wds.wdl.ast.expr.Expr;
import ru.wds.wdl.diagnostic.Diagnostics;
import ru.wds.wdl.lexer.Lexer;
import ru.wds.wdl.lexer.Token;
import ru.wds.wdl.parser.Parser;
import ru.wds.wdl.resolve.Resolution;
import ru.wds.wdl.resolve.Resolver;
import ru.wds.wdl.runtime.ExecutionContext;
import ru.wds.wdl.runtime.Interpreter;
import ru.wds.wdl.runtime.Output;
import ru.wds.wdl.runtime.WdlRuntimeError;
import ru.wds.wdl.source.Source;
import ru.wds.wdl.tools.AstDumper;
import ru.wds.wdl.tools.TokenDumper;
import ru.wds.wdl.value.types.NullValue;
import ru.wds.wdl.value.Value;

import java.io.BufferedReader;
import java.io.Console;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Точка входа консольного интерпретатора на базе библиотеки Picocli.
 */
@Command(
        name = "wdl",
        mixinStandardHelpOptions = true,
        versionProvider = Main.VersionProvider.class,
        description = "wdl — встраиваемый скриптовый язык для JVM",
        footer = "%nСкрипт — это присваивания и вызовы (println, print, typeof, len),%n"
                + "ветвления и циклы, свои функции: fun имя(a, b) => a + b.%n"
                + "Классы и модули появятся на следующих шагах."
)
public final class Main implements Callable<Integer> {

    /** Ошибка в самом скрипте. */
    private static final int EXIT_SCRIPT_ERROR = 1;
    /** Ошибка в том, как запустили: нет файла, неизвестный ключ. */
    private static final int EXIT_USAGE_ERROR = 2;

    /** Кодировка вывода, если автоопределение не устраивает: {@code -Dwdl.console.encoding=cp866}. */
    private static final String ENCODING_PROPERTY = "wdl.console.encoding";

    @Option(names = {"-t", "--tokens"}, description = "Показать поток токенов")
    private boolean showTokens;

    @Option(names = {"-a", "--ast"}, description = "Показать синтаксическое дерево (AST)")
    private boolean showAst;

    @Option(names = {"--repl"}, description = "Запустить интерактивный режим REPL")
    private boolean repl;

    @Parameters(index = "0", arity = "0..1", paramLabel = "<файл>", description = "Файл скрипта .wdl для выполнения")
    private Path scriptFile;

    static class VersionProvider implements CommandLine.IVersionProvider {
        @Override
        public String[] getVersion() {
            return new String[]{"wdl " + version()};
        }
    }

    public static void main(String[] args) {
        setUpOutputEncoding();
        int exitCode = new CommandLine(new Main()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() {
        if (repl) {
            return repl();
        }

        if (scriptFile == null) {
            if (showTokens || showAst) {
                System.err.println("Ошибка: для флагов --tokens / --ast требуется указать путь к файлу.");
                return EXIT_USAGE_ERROR;
            }
            CommandLine.usage(this, System.out);
            return 0;
        }

        return execute(scriptFile);
    }

    /**
     * Прогоняет файл через конвейер: исходник → токены → дерево → выполнение.
     *
     * @return код возврата процесса
     */
    private int execute(Path path) {
        Source source;
        try {
            if (!Files.isRegularFile(path)) {
                System.err.println("Файл не найден: " + path.toAbsolutePath());
                return EXIT_USAGE_ERROR;
            }
            source = Source.ofFile(path);
        } catch (InvalidPathException e) {
            System.err.println("Некорректный путь: " + path);
            return EXIT_USAGE_ERROR;
        } catch (IOException e) {
            System.err.println("Не удалось прочитать файл: " + e.getMessage());
            return EXIT_USAGE_ERROR;
        }

        Diagnostics diagnostics = new Diagnostics(source);
        List<Token> tokens = Lexer.tokenize(source, diagnostics);

        if (showTokens) {
            showDiagnostics(diagnostics);
            if (diagnostics.hasErrors()) {
                return EXIT_SCRIPT_ERROR;
            }
            System.out.print(TokenDumper.dump(source, tokens));
            if (!showAst) {
                return 0;
            }
        }

        Program program = Parser.parseProgram(tokens, diagnostics);
        showDiagnostics(diagnostics);
        if (diagnostics.hasErrors()) {
            System.err.println("Разбор не удался: ошибок — " + diagnostics.errorCount() + ".");
            return EXIT_SCRIPT_ERROR;
        }

        if (showAst) {
            System.out.print(AstDumper.dump(program));
            return 0;
        }

        // Резолвер связывает классы и типажи и ловит то, что видно до выполнения:
        // невыполненное требование типажа, круг в наследовании, аргументы родителю.
        Resolution resolution = Resolver.resolve(program, diagnostics);
        showDiagnostics(diagnostics);
        if (diagnostics.hasErrors()) {
            System.err.println("Разбор не удался: ошибок — " + diagnostics.errorCount() + ".");
            return EXIT_SCRIPT_ERROR;
        }

        try {
            // Вывод скрипта идёт в консоль процесса — это решение консольного запуска,
            // а не ядра: встроенный движок по умолчанию не печатает никуда.
            new Interpreter().run(program, resolution, ExecutionContext.fresh(Output.standard()));
            return 0;
        } catch (WdlRuntimeError e) {
            // Ошибка выполнения показывается так же, как ошибка разбора: с местом в скрипте.
            System.err.println(diagnostics.render(e.toDiagnostic()));
            return EXIT_SCRIPT_ERROR;
        }
    }

    /**
     * Интерактивный режим: строка — выражение — значение.
     */
    private static int repl() {
        System.out.println("wdl " + version() + " — интерактивный режим. Выход: :q или Ctrl+D.");
        Interpreter interpreter = new Interpreter();
        ExecutionContext context = ExecutionContext.fresh(Output.standard());

        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, outputCharset()));
        while (true) {
            System.out.print("wdl> ");
            System.out.flush();
            String line;
            try {
                line = reader.readLine();
            } catch (IOException e) {
                System.err.println("Не удалось прочитать ввод: " + e.getMessage());
                return EXIT_USAGE_ERROR;
            }
            if (line == null || line.trim().equals(":q")) {
                System.out.println();
                return 0;
            }
            if (line.isBlank()) {
                continue;
            }
            evaluateLine(line, interpreter, context);
        }
    }

    /**
     * Выполняет строку REPL.
     */
    private static void evaluateLine(String line, Interpreter interpreter, ExecutionContext context) {
        Source source = Source.ofString(line);
        List<Token> tokens = Lexer.tokenize(source, new Diagnostics(source));

        Diagnostics asExpression = new Diagnostics(source);
        Expr expr = Parser.parseExpression(tokens, asExpression);
        if (!asExpression.hasErrors()) {
            try {
                Value value = interpreter.eval(expr, context);
                if (value != NullValue.NULL) {
                    System.out.println(value);
                }
            } catch (WdlRuntimeError e) {
                System.err.println(asExpression.render(e.toDiagnostic()));
            }
            return;
        }

        Diagnostics diagnostics = new Diagnostics(source);
        Program program = Parser.parseProgram(tokens, diagnostics);
        Resolution resolution = diagnostics.hasErrors()
                ? Resolution.none()
                : Resolver.resolve(program, diagnostics);
        showDiagnostics(diagnostics);
        if (diagnostics.hasErrors()) {
            return;
        }
        try {
            // Каждая строка REPL — своя программа, поэтому и формы у неё свои:
            // класс живёт в области сеанса, а наследоваться можно в пределах ввода.
            interpreter.run(program, resolution, context);
        } catch (WdlRuntimeError e) {
            System.err.println(diagnostics.render(e.toDiagnostic()));
        }
    }

    private static void showDiagnostics(Diagnostics diagnostics) {
        if (!diagnostics.isEmpty()) {
            System.err.print(diagnostics.renderAll());
            System.err.flush();
        }
    }

    /**
     * Настраивает кодировку вывода до первой напечатанной буквы.
     */
    private static void setUpOutputEncoding() {
        Charset charset = outputCharset();
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, charset));
        System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err), true, charset));
    }

    private static Charset outputCharset() {
        String requested = System.getProperty(ENCODING_PROPERTY);
        if (requested != null && !requested.isBlank()) {
            try {
                return Charset.forName(requested.trim());
            } catch (IllegalCharsetNameException | UnsupportedCharsetException e) {
                return StandardCharsets.UTF_8;
            }
        }
        Console console = System.console();
        return console != null ? console.charset() : StandardCharsets.UTF_8;
    }

    private static String version() {
        String v = Main.class.getPackage().getImplementationVersion();
        return v != null ? v : "dev";
    }
}
