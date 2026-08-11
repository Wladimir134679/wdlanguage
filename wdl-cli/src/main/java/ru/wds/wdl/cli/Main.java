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
import ru.wds.wdl.module.ModuleSource;
import ru.wds.wdl.module.ModuleUnits;
import ru.wds.wdl.module.Unit;
import ru.wds.wdl.runtime.ExecutionContext;
import ru.wds.wdl.runtime.Interpreter;
import ru.wds.wdl.runtime.Output;
import ru.wds.wdl.runtime.WdlError;
import ru.wds.wdl.runtime.WdlRuntimeError;
import ru.wds.wdl.source.Source;
import ru.wds.wdl.stdlib.Std;
import ru.wds.wdl.stdlib.Sys;
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
        footer = "%nСкрипт — это присваивания, константы (const LIMIT = 10)%n"
                + "и вызовы (println, print, typeof, len), ветвления и циклы,%n"
                + "свои функции: fun имя(a, b) => a + b, классы и трейты.%n"
                + "Модули подключаются через import lib.math или import lib.math as m;%n"
                + "путь считается от каталога файла, где написан import.%n"
                + "Встроенные модули: sys.io (файлы), sys.json, sys.net.http, std."
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

    /**
     * Java-стек нужен не автору скрипта, а тому, кто чинит движок или библиотеку,
     * — поэтому он под флагом и печатается только там, где вообще есть: у ошибок,
     * прилетевших из Java.
     */
    @Option(names = {"--debug"}, description = "Показывать Java-стек у ошибок из библиотек")
    private boolean showJavaTrace;

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
            return repl(showJavaTrace);
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

        // Модули ищутся рядом со скриптом: 'wdl examples/modules/plain.wdl' находит
        // examples/modules/lib/*.wdl, откуда бы ни запустили сам процесс. Читаются они
        // только тогда, когда выполнится их 'import': файла может ещё и не быть.
        ModuleUnits modules = new ModuleUnits(ModuleSource.ofDirectory(home(path)));

        // Резолвер расставляет объявления типов верхнего уровня так, чтобы родитель
        // выполнялся раньше потомка, и ловит круг в наследовании. Всё остальное —
        // требования трейтов, аргументы родителю, неизвестные имена — называет
        // выполнение, в тот момент, когда объявление класса до него доходит.
        Resolution resolution = Resolver.resolve(program, diagnostics);
        showDiagnostics(diagnostics);
        if (diagnostics.hasErrors()) {
            System.err.println("Разбор не удался: ошибок — " + diagnostics.errorCount() + ".");
            return EXIT_SCRIPT_ERROR;
        }

        // Вывод скрипта идёт в консоль процесса — это решение консольного запуска,
        // а не ядра: встроенный движок по умолчанию не печатает никуда.
        ExecutionContext context = standardContext().withModules(modules);
        try {
            new Interpreter().run(Unit.of(source, program, resolution), context);
            return 0;
        } catch (WdlError e) {
            // Ошибка выполнения показывается так же, как ошибка разбора: с местом
            // в скрипте — и в том файле, которому это место принадлежит. С импортом
            // файлов много, и смещение в каждом из них указывает на своё. Ниже —
            // путь по скрипту: он про вызовы, а не про строку, где рвануло.
            System.err.println(report(e, source, showJavaTrace));
            return EXIT_SCRIPT_ERROR;
        } finally {
            // Встроенные модули могли завести живое — клиента, соединение, поток.
            // Закрывает их хозяин запуска, и здесь это мы, чем бы скрипт ни кончился.
            context.shutdownModules();
        }
    }

    /**
     * Ошибка выполнения так, как её видит человек: строка исходника с подчёркиванием,
     * а следом путь по скрипту.
     * <p>
     * Java-стек сюда не попадает вовсе: он описывал бы путь по методам интерпретатора,
     * который автору скрипта бесполезен.
     */
    private static String report(WdlError error, Source fallback, boolean withJavaTrace) {
        Source failed = error.source() != null ? error.source() : fallback;
        StringBuilder sb = new StringBuilder(256);
        sb.append(new Diagnostics(failed).render(error.toDiagnostic()));
        for (String frame : error.trace()) {
            sb.append(System.lineSeparator()).append("  ").append(frame);
        }
        if (withJavaTrace && error instanceof WdlRuntimeError runtime && runtime.javaCause() != null) {
            sb.append(System.lineSeparator()).append("  --- стек Java ---");
            for (StackTraceElement element : runtime.javaCause().getStackTrace()) {
                sb.append(System.lineSeparator()).append("  ").append(element);
            }
        }
        return sb.toString();
    }

    /** Каталог скрипта — корень для его импортов. */
    private static Path home(Path script) {
        Path parent = script.toAbsolutePath().getParent();
        return parent != null ? parent : Path.of("");
    }

    /**
     * Интерактивный режим: строка — выражение — значение.
     */
    private static int repl(boolean withJavaTrace) {
        System.out.println("wdl " + version() + " — интерактивный режим. Выход: :q или Ctrl+D.");
        Interpreter interpreter = new Interpreter();
        // У строки, набранной в REPL, файла нет, поэтому и каталога у неё нет:
        // импорты считаются от рабочей директории процесса. Реестр один на сеанс —
        // модуль, импортированный одной строкой, остаётся тем же самым для следующих.
        ModuleUnits modules = new ModuleUnits(ModuleSource.ofDirectory(Path.of("")));
        ExecutionContext context = standardContext().withModules(modules);

        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, outputCharset()));
        while (true) {
            System.out.print("wdl> ");
            System.out.flush();
            String line;
            try {
                line = reader.readLine();
            } catch (IOException e) {
                System.err.println("Не удалось прочитать ввод: " + e.getMessage());
                context.shutdownModules();
                return EXIT_USAGE_ERROR;
            }
            if (line == null || line.trim().equals(":q")) {
                System.out.println();
                context.shutdownModules();
                return 0;
            }
            if (line.isBlank()) {
                continue;
            }
            evaluateLine(line, interpreter, context, withJavaTrace);
        }
    }

    /**
     * Выполняет строку REPL.
     */
    private static void evaluateLine(String line, Interpreter interpreter, ExecutionContext context,
                                     boolean withJavaTrace) {
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
            } catch (WdlError e) {
                System.err.println(report(e, source, withJavaTrace));
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
        } catch (WdlError e) {
            System.err.println(report(e, source, withJavaTrace));
        }
    }

    /**
     * Контекст консольного запуска: вывод в консоль процесса и подключённая
     * стандартная библиотека.
     * <p>
     * Оба решения принимает запуск, а не ядро. Встроенный в чужое приложение движок
     * по умолчанию не печатает никуда и не получает ни одного имени сверх встроенных:
     * что положить скрипту в область видимости — дело приложения, и {@code std}
     * тут ничем не привилегированнее любой другой библиотеки.
     */
    private static ExecutionContext standardContext() {
        ExecutionContext context = ExecutionContext.fresh(Output.standard());
        Std.install(context.scope());
        // Встроенные модули (sys.io, sys.json, sys.net.http) даёт тот же запуск и тем же
        // способом: набором, а не флагом. Приложение, встраивающее движок, собирает свой —
        // и скрипту доступно ровно то, что в нём есть.
        return context.withNativeModules(Sys.modules());
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
