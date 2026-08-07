package ru.wds.wdl.cli;

import ru.wds.wdl.ast.Expr;
import ru.wds.wdl.ast.Program;
import ru.wds.wdl.diagnostic.Diagnostics;
import ru.wds.wdl.lexer.Lexer;
import ru.wds.wdl.lexer.Token;
import ru.wds.wdl.parser.Parser;
import ru.wds.wdl.runtime.ExecutionContext;
import ru.wds.wdl.runtime.Interpreter;
import ru.wds.wdl.runtime.Output;
import ru.wds.wdl.runtime.WdlRuntimeError;
import ru.wds.wdl.source.Source;
import ru.wds.wdl.tools.AstDumper;
import ru.wds.wdl.tools.TokenDumper;
import ru.wds.wdl.value.NullValue;
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

/**
 * Точка входа консольного интерпретатора.
 * <p>
 * Разбор аргументов сделан вручную и намеренно примитивно: внешних библиотек
 * в проекте нет. Если аргументов станет много — сюда придёт нормальный парсер команд.
 */
public final class Main {

    /** Ошибка в самом скрипте. */
    private static final int EXIT_SCRIPT_ERROR = 1;
    /** Ошибка в том, как запустили: нет файла, неизвестный ключ. */
    private static final int EXIT_USAGE_ERROR = 2;

    /** Кодировка вывода, если автоопределение не устраивает: {@code -Dwdl.console.encoding=cp866}. */
    private static final String ENCODING_PROPERTY = "wdl.console.encoding";

    /** Что делать со скриптом: докуда вести его по конвейеру. */
    private enum Mode {
        /** Разобрать и вычислить. */
        RUN,
        /** Показать поток токенов. */
        TOKENS,
        /** Показать синтаксическое дерево. */
        AST
    }

    private Main() {
    }

    public static void main(String[] args) {
        setUpOutputEncoding();
        PrintStream out = System.out;

        if (args.length == 0) {
            printUsage(out);
            return;
        }

        switch (args[0]) {
            case "--version", "-v" -> out.println("wdl " + version());
            case "--help", "-h" -> printUsage(out);
            case "--repl" -> System.exit(repl());
            case "--tokens" -> System.exit(executeWithArgument(args, Mode.TOKENS));
            case "--ast" -> System.exit(executeWithArgument(args, Mode.AST));
            default -> {
                if (args[0].startsWith("-")) {
                    fail("Неизвестный ключ: " + args[0]);
                }
                System.exit(execute(args[0], Mode.RUN));
            }
        }
    }

    private static int executeWithArgument(String[] args, Mode mode) {
        if (args.length < 2) {
            fail("Ключу " + args[0] + " нужен путь к файлу.");
        }
        return execute(args[1], mode);
    }

    /**
     * Прогоняет файл через конвейер: исходник → токены → дерево → выполнение.
     *
     * @return код возврата процесса
     */
    private static int execute(String fileName, Mode mode) {
        Source source;
        try {
            Path path = Path.of(fileName);
            if (!Files.isRegularFile(path)) {
                System.err.println("Файл не найден: " + path.toAbsolutePath());
                return EXIT_USAGE_ERROR;
            }
            source = Source.ofFile(path);
        } catch (InvalidPathException e) {
            System.err.println("Некорректный путь: " + fileName);
            return EXIT_USAGE_ERROR;
        } catch (IOException e) {
            System.err.println("Не удалось прочитать файл: " + e.getMessage());
            return EXIT_USAGE_ERROR;
        }

        Diagnostics diagnostics = new Diagnostics(source);
        List<Token> tokens = Lexer.tokenize(source, diagnostics);

        if (mode == Mode.TOKENS) {
            showDiagnostics(diagnostics);
            if (diagnostics.hasErrors()) {
                return EXIT_SCRIPT_ERROR;
            }
            System.out.print(TokenDumper.dump(source, tokens));
            return 0;
        }

        Program program = Parser.parseProgram(tokens, diagnostics);
        showDiagnostics(diagnostics);
        if (diagnostics.hasErrors()) {
            System.err.println("Разбор не удался: ошибок — " + diagnostics.errorCount() + ".");
            return EXIT_SCRIPT_ERROR;
        }

        if (mode == Mode.AST) {
            System.out.print(AstDumper.dump(program));
            return 0;
        }

        try {
            // Вывод скрипта идёт в консоль процесса — это решение консольного запуска,
            // а не ядра: встроенный движок по умолчанию не печатает никуда.
            new Interpreter().run(program, ExecutionContext.fresh(Output.standard()));
            return 0;
        } catch (WdlRuntimeError e) {
            // Ошибка выполнения показывается так же, как ошибка разбора: с местом в скрипте.
            System.err.println(diagnostics.render(e.toDiagnostic()));
            return EXIT_SCRIPT_ERROR;
        }
    }

    /**
     * Интерактивный режим: строка — выражение — значение.
     * <p>
     * Каждая строка разбирается отдельно и со своей диагностикой: ошибка в одной
     * не должна мешать следующим. Окружение при этом общее — когда появятся
     * переменные, они переживут ввод строки.
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
     * <p>
     * Строка может быть и выражением, и инструкцией, а различить их заранее нельзя:
     * {@code f(1)} — и то, и другое. Поэтому сначала пробуем разобрать как выражение
     * с отдельной диагностикой, которую в случае неудачи просто выбрасываем, — и если
     * получилось, печатаем значение. Не получилось — это инструкция, выполняем её.
     */
    private static void evaluateLine(String line, Interpreter interpreter, ExecutionContext context) {
        Source source = Source.ofString(line);
        List<Token> tokens = Lexer.tokenize(source, new Diagnostics(source));

        Diagnostics asExpression = new Diagnostics(source);
        Expr expr = Parser.parseExpression(tokens, asExpression);
        if (!asExpression.hasErrors()) {
            try {
                Value value = interpreter.eval(expr, context);
                // println уже всё напечатал и вернул null — печатать его ещё раз незачем.
                if (value != NullValue.NULL) {
                    // В отладочном виде: строку в кавычках здесь видеть полезнее.
                    System.out.println(value);
                }
            } catch (WdlRuntimeError e) {
                System.err.println(asExpression.render(e.toDiagnostic()));
            }
            return;
        }

        Diagnostics diagnostics = new Diagnostics(source);
        Program program = Parser.parseProgram(tokens, diagnostics);
        showDiagnostics(diagnostics);
        if (diagnostics.hasErrors()) {
            return;
        }
        try {
            interpreter.run(program, context);
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
     * <p>
     * Полагаться на {@code -Dstdout.encoding} из {@code build.gradle.kts} нельзя:
     * эти аргументы получают только {@code gradlew run} и стартовые скрипты
     * дистрибутива. Запуск из IDE их не видит, и русский текст превращается в мусор.
     * <p>
     * Правило выбора простое. Если процесс подключён к настоящему терминалу
     * ({@link System#console()} не {@code null}) — пишем в кодировке этого терминала,
     * иначе в консоли Windows получилась бы каша. Если вывод перенаправлен — в трубу
     * IDE, в файл, в другую программу — пишем UTF-8: единственный разумный выбор
     * для того, что будут читать не глазами. Переопределяется свойством
     * {@value #ENCODING_PROPERTY}.
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
                // Печатать предупреждение ещё нечем — вывод не настроен. Молча берём UTF-8.
                return StandardCharsets.UTF_8;
            }
        }
        Console console = System.console();
        return console != null ? console.charset() : StandardCharsets.UTF_8;
    }

    private static void fail(String message) {
        System.err.println(message);
        printUsage(System.err);
        System.exit(EXIT_USAGE_ERROR);
    }

    private static void printUsage(PrintStream out) {
        out.println("""
                wdl — встраиваемый скриптовый язык для JVM

                Использование:
                  wdl <файл.wdl>          выполнить скрипт
                  wdl --ast <файл>        показать синтаксическое дерево
                  wdl --tokens <файл>     показать поток токенов
                  wdl --repl              интерактивный режим
                  wdl --version           версия
                  wdl --help              эта справка

                Скрипт — это присваивания и вызовы: println, print, typeof, len.
                Ветвления, циклы и свои функции появятся на следующих шагах.""");
    }

    private static String version() {
        String v = Main.class.getPackage().getImplementationVersion();
        return v != null ? v : "dev";
    }
}
