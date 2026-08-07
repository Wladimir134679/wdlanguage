package ru.wds.wdl.cli;

import ru.wds.wdl.diagnostic.Diagnostics;
import ru.wds.wdl.lexer.Lexer;
import ru.wds.wdl.lexer.Token;
import ru.wds.wdl.source.Source;
import ru.wds.wdl.tools.TokenDumper;

import java.io.Console;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
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
            case "--repl" -> out.println("REPL ещё не реализован.");
            case "--tokens" -> {
                if (args.length < 2) {
                    fail("Ключу --tokens нужен путь к файлу.");
                }
                System.exit(execute(args[1], true));
            }
            default -> {
                if (args[0].startsWith("-")) {
                    fail("Неизвестный ключ: " + args[0]);
                }
                System.exit(execute(args[0], false));
            }
        }
    }

    /**
     * Прогоняет файл через конвейер компиляции.
     * <p>
     * Сейчас конвейер заканчивается на лексере, поэтому «выполнение» — это разбор
     * на токены и вывод результата. Когда появятся парсер и интерпретатор, отсюда
     * же пойдут следующие этапы, а поведение {@code --tokens} останется прежним.
     *
     * @return код возврата процесса
     */
    private static int execute(String fileName, boolean tokensOnly) {
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

        if (!diagnostics.isEmpty()) {
            System.err.print(diagnostics.renderAll());
            System.err.flush();
        }
        if (diagnostics.hasErrors()) {
            System.err.println("Разбор не удался: ошибок — " + diagnostics.errorCount() + ".");
            return EXIT_SCRIPT_ERROR;
        }

        System.out.print(TokenDumper.dump(source, tokens));
        if (!tokensOnly) {
            System.out.println();
            System.out.println("Токенов: " + tokens.size()
                    + ". Дальше лексера конвейер пока не идёт — парсер и интерпретатор в работе.");
        }
        return 0;
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
                  wdl <файл.wdl>          запустить скрипт
                  wdl --tokens <файл>     показать поток токенов
                  wdl --repl              интерактивный режим
                  wdl --version           версия
                  wdl --help              эта справка""");
    }

    private static String version() {
        String v = Main.class.getPackage().getImplementationVersion();
        return v != null ? v : "dev";
    }
}
