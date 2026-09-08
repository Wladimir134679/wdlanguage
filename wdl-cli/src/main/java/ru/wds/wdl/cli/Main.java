package ru.wds.wdl.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import ru.wds.wdl.ast.Program;
import ru.wds.wdl.ast.expr.Expr;
import ru.wds.wdl.diagnostic.Diagnostics;
import ru.wds.wdl.lexer.Lexer;
import ru.wds.wdl.lexer.LexerMode;
import ru.wds.wdl.lexer.Token;
import ru.wds.wdl.metrics.Measure;
import ru.wds.wdl.metrics.Measurement;
import ru.wds.wdl.metrics.Metrics;
import ru.wds.wdl.metrics.MetricsCollector;
import ru.wds.wdl.metrics.Stage;
import ru.wds.wdl.parser.Parser;
import ru.wds.wdl.profile.CallEdge;
import ru.wds.wdl.profile.CallProfile;
import ru.wds.wdl.profile.CallProfiler;
import ru.wds.wdl.profile.CallSite;
import ru.wds.wdl.profile.Profiler;
import ru.wds.wdl.module.ModuleSource;
import ru.wds.wdl.module.ModuleUnits;
import ru.wds.wdl.module.Unit;
import ru.wds.wdl.runtime.ExecutionContext;
import ru.wds.wdl.runtime.Limits;
import ru.wds.wdl.runtime.Interpreter;
import ru.wds.wdl.runtime.Output;
import ru.wds.wdl.runtime.WdlError;
import ru.wds.wdl.runtime.WdlRuntimeError;
import ru.wds.wdl.source.Source;
import ru.wds.wdl.stdlib.JsonWriter;
import ru.wds.wdl.stdlib.Std;
import ru.wds.wdl.stdlib.Sys;
import ru.wds.wdl.tools.AstDumper;
import ru.wds.wdl.tools.catalog.Catalog;
import ru.wds.wdl.tools.catalog.Catalogs;
import ru.wds.wdl.tools.catalog.ModuleDescriptor;
import ru.wds.wdl.tools.catalog.Origin;
import ru.wds.wdl.tools.catalog.SymbolDescriptor;
import ru.wds.wdl.tools.TokenDumper;
import ru.wds.wdl.value.types.ArrayValue;
import ru.wds.wdl.value.types.IntValue;
import ru.wds.wdl.value.types.MapValue;
import ru.wds.wdl.value.types.NullValue;
import ru.wds.wdl.value.types.StringValue;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
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
                + "свои функции: def имя(a, b) => a + b, классы и трейты.%n"
                + "Модули подключаются через import lib.math или import lib.math as m;%n"
                + "путь считается от корня проекта (--project-root).%n"
                + "Встроенные модули: sys.io (файлы), sys.json, sys.net.http, std.%n%n"
                + "Время стадий: wdl --metrics script.wdl;%n"
                + "строка на каждую законченную стадию — wdl --metrics-each script.wdl.%n"
                + "Горячие функции: wdl --profile script.wdl;%n"
                + "тот же профиль машине — wdl --profile-out profile.json script.wdl."
)
public final class Main implements Callable<Integer> {

    /** Ошибка в самом скрипте. */
    private static final int EXIT_SCRIPT_ERROR = 1;
    /** Ошибка в том, как запустили: нет файла, неизвестный ключ. */
    private static final int EXIT_USAGE_ERROR = 2;

    /**
     * Сколько ждать потоки скрипта после того, как он дочитан.
     * <p>
     * Ждать вечно нельзя: один зациклившийся поток превратил бы {@code wdl script.wdl}
     * в процесс, который не выходит. Не ждать вовсе тоже нельзя: поток, честно выходящий
     * по {@code defer}, обязан успеть закрыть своё. Столько же ждёт и {@code WdlInstance}.
     */
    private static final long THREAD_STOP_TIMEOUT_MILLIS = 5000;

    /** Кодировка вывода, если автоопределение не устраивает: {@code -Dwdl.console.encoding=cp866}. */
    private static final String ENCODING_PROPERTY = "wdl.console.encoding";

    /** Числа в метриках печатаются по-русски: «12,4 мс», как и вся остальная диагностика. */
    private static final Locale RUSSIAN = Locale.forLanguageTag("ru");

    /** Версия формата файла профиля: растёт, когда состав полей меняется несовместимо. */
    private static final int PROFILE_FORMAT_VERSION = 1;

    @Option(names = {"-t", "--tokens"}, description = "Показать поток токенов")
    private boolean showTokens;

    @Option(names = {"--trivia"},
            description = "Вместе с --tokens: показать пробелы, комментарии и мусор")
    private boolean showTrivia;

    @Option(names = {"-a", "--ast"}, description = "Показать синтаксическое дерево (AST)")
    private boolean showAst;

    @Option(names = {"--repl"}, description = "Запустить интерактивный режим REPL")
    private boolean repl;

    /**
     * Метрики печатаются в поток ошибок, а не в вывод скрипта: то, что напечатал скрипт,
     * — это данные, и {@code wdl script.wdl > out.txt} не должен получить в файл ещё
     * и нашу таблицу.
     */
    @Option(names = {"--metrics"}, description = "Показать время стадий после выполнения")
    private boolean showMetrics;

    @Option(names = {"--metrics-each"},
            description = "То же плюс строка на каждую стадию по мере её завершения")
    private boolean showEachMeasurement;

    /**
     * Профиль печатается туда же, куда и метрики, и по той же причине: вывод скрипта —
     * это данные.
     * <p>
     * Отдельным флагом от {@code --metrics}, потому что это другая цена: профиль
     * считает каждый вызов, и скрипт под ним идёт медленнее. Просивший время стадий
     * за это платить не должен.
     */
    @Option(names = {"--profile"},
            description = "Показать самые горячие функции после выполнения")
    private boolean showProfile;

    /**
     * Тот же профиль машине — файлом, а не в поток.
     * <p>
     * Файлом затем, что читатель у него один: другая программа (плагин IDE, скрипт
     * сравнения прогонов), а стандартный вывод к этому моменту занят выводом самого
     * скрипта. Профиль при этом включается сам: просить его файлом и не получить
     * содержимого было бы странно.
     */
    @Option(names = {"--profile-out"}, paramLabel = "<файл>",
            description = "Записать профиль в файл JSON (включает профиль)")
    private Path profileFile;

    /**
     * Java-стек нужен не автору скрипта, а тому, кто чинит движок или библиотеку,
     * — поэтому он под флагом и печатается только там, где вообще есть: у ошибок,
     * прилетевших из Java.
     */
    @Option(names = {"--debug"}, description = "Показывать Java-стек у ошибок из библиотек")
    private boolean showJavaTrace;

    /**
     * Пределы выполнения: по умолчанию их нет.
     * <p>
     * Консольный {@code wdl} запускает свой скрипт, а не чужой, — и обрывать его
     * на середине без просьбы было бы наглостью. Просьба выглядит так:
     * {@code wdl --timeout=5 --max-steps=1000000 script.wdl}.
     */
    @Option(names = {"--max-steps"}, paramLabel = "<n>",
            description = "Оборвать скрипт после n шагов (итераций и вызовов)")
    private long maxSteps;

    @Option(names = {"--timeout"}, paramLabel = "<секунды>",
            description = "Оборвать скрипт через столько секунд")
    private double timeoutSeconds;

    @Option(names = {"--max-threads"}, paramLabel = "<n>",
            description = "Сколько потоков разрешено скрипту (th.spawn и th.pool)")
    private int maxThreads;

    @Option(names = {"--catalog"},
            description = "Показать имена, доступные скриптам: встроенные, std и модули sys.*")
    private boolean showCatalog;

    @Option(names = {"--json"}, description = "Машинный вывод (пока только для --catalog)")
    private boolean asJson;

    @Parameters(index = "0", arity = "0..1", paramLabel = "<файл/проект>",
            description = "Файл .wdl или каталог проекта с main.wdl")
    private Path scriptFile;

    @Option(names = "--project-root", paramLabel = "<каталог>",
            description = "Корень импортов; по умолчанию каталог исполняемого файла")
    private Path projectRoot;

    @Parameters(index = "1..*", arity = "0..*", paramLabel = "<args>",
            description = "Аргументы скрипта (после --), доступны как args")
    private List<String> scriptArguments = new ArrayList<>();

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
        if (projectRoot != null && !Files.isDirectory(projectRoot)) {
            System.err.println("Корень проекта не является каталогом: " + projectRoot);
            return EXIT_USAGE_ERROR;
        }
        if (repl) {
            return repl(showJavaTrace);
        }

        if (showCatalog) {
            return printCatalog();
        }

        if (asJson) {
            // '--json' сам по себе ничего не выводит: это форма ответа, а не вопрос.
            System.err.println("Ошибка: флаг --json работает вместе с --catalog.");
            return EXIT_USAGE_ERROR;
        }

        if (showTrivia && !showTokens) {
            // '--trivia' без '--tokens' — описка: тривия видна только в дампе токенов,
            // и молча ничего не показать значило бы соврать про выполненный флаг.
            System.err.println("Ошибка: флаг --trivia работает вместе с --tokens.");
            return EXIT_USAGE_ERROR;
        }

        if (scriptFile == null) {
            if (showTokens || showAst) {
                System.err.println("Ошибка: для флагов --tokens / --ast требуется указать путь к файлу.");
                return EXIT_USAGE_ERROR;
            }
            CommandLine.usage(this, System.out);
            return 0;
        }

        return execute(Files.isDirectory(scriptFile) ? scriptFile.resolve("main.wdl") : scriptFile);
    }

    /**
     * Прогоняет файл через конвейер и, если просили, печатает время стадий.
     * <p>
     * Таблица печатается в {@code finally}: время до падения — тоже ответ, и запуск,
     * кончившийся ошибкой скрипта, отчёта не лишается. И печатается она <b>после</b>
     * закрытия запуска — иначе стадия {@code SHUTDOWN} в неё не попала бы, а она
     * бывает основной ({@code sys.gui} ждёт, пока пользователь закроет окна).
     *
     * @return код возврата процесса
     */
    private int execute(Path path) {
        MetricsCollector metrics = collectingMetrics() ? newCollector() : null;
        CallProfiler profile = collectingProfile() ? Profiler.collecting() : null;
        try {
            return runPipeline(path, metrics != null ? metrics : Metrics.off(),
                    profile != null ? profile : Profiler.off());
        } finally {
            if (metrics != null) {
                System.err.print(metrics.finish().render());
                System.err.flush();
            }
            if (profile != null) {
                reportProfile(profile.finish(), path);
            }
        }
    }

    private boolean collectingProfile() {
        return showProfile || profileFile != null;
    }

    /**
     * Показывает профиль: таблицей человеку, файлом машине — или и тем, и другим.
     * <p>
     * Файл пишется и тогда, когда скрипт упал: профиль до падения — тоже ответ,
     * и ровно он нужен, когда скрипт оборвался по таймауту.
     */
    private void reportProfile(CallProfiler profile, Path script) {
        if (showProfile) {
            System.err.print(profile.render());
            System.err.flush();
        }
        if (profileFile == null) {
            return;
        }
        try {
            Files.writeString(profileFile, renderProfileAsJson(profile, script),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            // Не код возврата: скрипт-то отработал, и подменять его результат неудачей
            // записи отчёта было бы неправдой о самом запуске.
            System.err.println("Не удалось записать профиль в " + profileFile
                    + ": " + e.getMessage());
        }
    }

    /**
     * Профиль машине: тем же {@code JsonWriter}, что и каталог имён.
     * <p>
     * Времена — в наносекундах, целыми: округляет тот, кто показывает, а не тот,
     * кто отдаёт. Рёбра ссылаются на записи номерами в массиве {@code sites} — так
     * дерево вызовов собирается на стороне читателя без сверки имён.
     */
    private static String renderProfileAsJson(CallProfiler profile, Path script) {
        List<CallProfile> sites = profile.all();
        Map<CallSite, Integer> numbers = new LinkedHashMap<>();
        List<Value> described = new ArrayList<>(sites.size());
        for (CallProfile entry : sites) {
            numbers.put(entry.site(), numbers.size());
            described.add(siteAsJson(entry));
        }
        List<Value> links = new ArrayList<>();
        for (CallEdge edge : profile.edges()) {
            Integer caller = numbers.get(edge.caller());
            Integer callee = numbers.get(edge.callee());
            if (caller == null || callee == null) {
                continue;
            }
            MapValue link = new MapValue();
            link.put("caller", IntValue.of(caller));
            link.put("callee", IntValue.of(callee));
            link.put("calls", IntValue.of(edge.calls()));
            link.put("total_ns", IntValue.of(edge.totalNanos()));
            links.add(link);
        }
        MapValue root = new MapValue();
        // Версия формата — первым полем и с первого дня: читатель у файла внешний
        // (плагин IDE), обновляется он отдельно от wdl, и «поле пропало» он обязан
        // отличать от «файл не тот».
        root.put("version", IntValue.of(PROFILE_FORMAT_VERSION));
        root.put("script", StringValue.of(script.toString()));
        root.put("calls", IntValue.of(profile.calls()));
        root.put("self_ns", IntValue.of(profile.self().toNanos()));
        root.put("wall_ns", IntValue.of(profile.wall().toNanos()));
        root.put("threads", IntValue.of(profile.threads()));
        root.put("sites", ArrayValue.of(described));
        root.put("edges", ArrayValue.of(links));
        return JsonWriter.stringify(root, 2) + System.lineSeparator();
    }

    /** Одна запись профиля: что это, где объявлено и во что обошлось. */
    private static MapValue siteAsJson(CallProfile entry) {
        CallSite site = entry.site();
        MapValue described = new MapValue();
        described.put("kind", StringValue.of(site.kind().name().toLowerCase(Locale.ROOT)));
        described.put("name", StringValue.of(site.name()));
        described.put("file", StringValue.of(site.file()));
        described.put("line", IntValue.of(site.line()));
        // Смещение — то, чем позиционируется редактор: пересчитывать строку и столбец
        // обратно ему не нужно. Минус один — «места в тексте нет».
        described.put("offset", IntValue.of(site.span().isNone() ? -1 : site.span().start()));
        described.put("calls", IntValue.of(entry.calls()));
        described.put("total_ns", IntValue.of(entry.totalNanos()));
        described.put("self_ns", IntValue.of(entry.selfNanos()));
        described.put("max_ns", IntValue.of(entry.maxNanos()));
        return described;
    }

    private boolean collectingMetrics() {
        // '--metrics-each' без '--metrics' — почти наверняка описка: строка на стадию
        // нужна вдобавок к итогу, а не вместо него.
        return showMetrics || showEachMeasurement;
    }

    private MetricsCollector newCollector() {
        return showEachMeasurement ? Metrics.collecting(Main::logMeasurement) : Metrics.collecting();
    }

    /** Строка о законченной стадии — туда же, куда и таблица. */
    private static void logMeasurement(Measurement measurement) {
        System.err.printf(RUSSIAN, "  · %s %s — %.1f мс%n", measurement.stage().title(),
                measurement.subject(), measurement.durationNanos() / 1_000_000.0);
    }

    /**
     * Конвейер: исходник → токены → дерево → выполнение.
     *
     * @param metrics приёмник времени стадий; {@link Metrics#off()}, когда не просили
     * @param profile приёмник вызовов; {@link Profiler#off()}, когда не просили
     * @return код возврата процесса
     */
    private int runPipeline(Path path, Metrics metrics, Profiler profile) {
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
        List<Token> tokens;
        // Стадию меряет тот, кто её запускает: сам лексер о метриках не знает.
        Measure lexing = metrics.begin(Stage.LEX, source.name());
        try {
            tokens = Lexer.tokenize(source, diagnostics);
        } finally {
            lexing.close();
        }

        if (showTokens) {
            showDiagnostics(diagnostics);
            if (diagnostics.hasErrors()) {
                return EXIT_SCRIPT_ERROR;
            }
            // Поток с тривией лексируется отдельно: тот, что уйдёт в парсер, менять
            // нельзя, а диагностику второй раз копить незачем — она уже показана.
            System.out.print(TokenDumper.dump(source, showTrivia
                    ? Lexer.tokenize(source, new Diagnostics(source), LexerMode.LOSSLESS)
                    : tokens));
            if (!showAst) {
                return 0;
            }
        }

        Program program;
        Measure parsing = metrics.begin(Stage.PARSE, source.name());
        try {
            program = Parser.parseProgram(tokens, diagnostics);
        } finally {
            parsing.close();
        }
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
        // Приёмник метрик достаётся реестру модулей отдельно от контекста: разбор
        // модуля случается здесь, а выполнение — в запуске, и объекты это разные.
        ModuleUnits modules = new ModuleUnits(ModuleSource.ofDirectory(
                projectRoot != null ? projectRoot.toAbsolutePath().normalize() : home(path)), metrics);

        // Вывод скрипта идёт в консоль процесса — это решение консольного запуска,
        // а не ядра: встроенный движок по умолчанию не печатает никуда.
        ExecutionContext context = standardContext().withMetrics(metrics)
                .withProfiler(profile).withModules(modules);
        context.scope().define("args", ArrayValue.of(scriptArguments.stream()
                .<Value>map(StringValue::of).toList()));
        try {
            new Interpreter().run(Unit.of(source, program), context);
            return 0;
        } catch (WdlError e) {
            // Ошибка выполнения показывается так же, как ошибка разбора: с местом
            // в скрипте — и в том файле, которому это место принадлежит. С импортом
            // файлов много, и смещение в каждом из них указывает на своё. Ниже —
            // путь по скрипту: он про вызовы, а не про строку, где рвануло.
            System.err.println(report(e, source, showJavaTrace));
            return EXIT_SCRIPT_ERROR;
        } finally {
            // Порядок тот же, что у WdlInstance.close(), и по той же причине:
            // потоки скрипта — пока модули открыты (их 'defer' обязан отработать
            // по живым ресурсам), потом модули — и вход в скрипт при этом ещё открыт,
            // потому что модуль вправе звать скрипт во время своего закрытия
            // (sys.gui ждёт закрытия окон, а обработчики кнопок всё это время
            // работают), потом потоки ещё раз — те, что завелись за это время, —
            // и только последним вход.
            //
            // Скрипт, которому нужен результат своих потоков, дожидается их сам:
            // 't.join()'. Здесь же — граница запуска, и висеть на ней нельзя.
            //
            // Всё это — тоже время запуска, и меряется оно целиком: ожидание потоков
            // и закрытие модулей на 'sys.gui' бывают дольше самого скрипта.
            Measure closing = metrics.begin(Stage.SHUTDOWN, source.name());
            try {
                stopThreads(context);
                // Встроенные модули могли завести живое — клиента, соединение, поток.
                // Закрывает их хозяин запуска, и здесь это мы, чем бы скрипт ни кончился.
                context.shutdownModules();
                stopThreads(context);
                context.closeRun();
            } finally {
                closing.close();
            }
        }
    }

    /** Прерывает потоки скрипта и ждёт их; не завершившихся называет по именам. */
    private static void stopThreads(ExecutionContext context) {
        List<String> stubborn = context.stopScriptThreads(THREAD_STOP_TIMEOUT_MILLIS);
        if (!stubborn.isEmpty()) {
            System.err.println("Потоки скрипта не завершились за "
                    + THREAD_STOP_TIMEOUT_MILLIS + " мс: " + String.join(", ", stubborn));
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
    private int repl(boolean withJavaTrace) {
        System.out.println("wdl " + version()
                + " — интерактивный режим. Имена: :names [начало]. Выход: :q или Ctrl+D.");
        Interpreter interpreter = new Interpreter();
        // У строки, набранной в REPL, файла нет, поэтому и каталога у неё нет:
        // импорты считаются от рабочей директории процесса. Реестр один на сеанс —
        // модуль, импортированный одной строкой, остаётся тем же самым для следующих.
        ModuleUnits modules = new ModuleUnits(ModuleSource.ofDirectory(
                projectRoot != null ? projectRoot.toAbsolutePath().normalize() : Path.of("")));
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
            if (line.trim().equals(":names") || line.trim().startsWith(":names ")) {
                printNames(context, line.trim().substring(":names".length()).trim());
                continue;
            }
            evaluateLine(line, interpreter, context, withJavaTrace);
        }
    }

    /**
     * Имена, видимые сейчас: встроенные, библиотечные и заведённые самим сеансом.
     * <p>
     * Каталог снимается с <b>живой</b> области запуска, поэтому функция, объявленная
     * строкой выше, попадает в список сама — второго учёта имён REPL не ведёт.
     * Настоящее дополнение по Tab требует библиотеки строкового ввода
     * ({@code BufferedReader} редактировать строку не умеет); ответ на тот же вопрос
     * это даёт уже сейчас.
     */
    private static void printNames(ExecutionContext context, String prefix) {
        Catalog visible = Catalogs.of(context.scope(), Origin.LIBRARY);
        StringBuilder out = new StringBuilder(1024);
        int shown = 0;
        for (SymbolDescriptor descriptor : visible.roots()) {
            if (!prefix.isEmpty() && !descriptor.name().startsWith(prefix)) {
                continue;
            }
            SymbolDescriptor known = Catalogs.builtins().root(descriptor.name());
            appendName(out, "  ", known != null ? known : descriptor);
            shown++;
        }
        if (shown == 0) {
            out.append("  (ничего с таким началом)").append(System.lineSeparator());
        }
        System.out.print(out);
        System.out.flush();
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
        showDiagnostics(diagnostics);
        if (diagnostics.hasErrors()) {
            return;
        }
        try {
            // Каждая строка REPL — своя программа, и выполняется она так же, как файл:
            // инструкции подряд, объявление начинает существовать со своей строки.
            interpreter.run(program, context);
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
    /**
     * Печатает то, что доступно скриптам в этой сборке консоли: встроенное языка,
     * имена {@code std} и состав встроенных модулей.
     * <p>
     * Каталог здесь <b>снимается с той же конфигурации, которую собирает запуск</b>
     * ({@link #standardContext()}), а не пишется рядом с ней: разойтись со списком
     * имён, который получит скрипт, он поэтому не может. Это же и первый машинный
     * ответ на вопрос «что вообще есть в sys.io», которого до сих пор не было нигде,
     * кроме документации.
     */
    private int printCatalog() {
        ExecutionContext context = standardContext();
        Catalog roots;
        try {
            roots = Catalogs.of(context.scope(), Origin.LIBRARY);
        } finally {
            context.shutdownModules();
            context.closeRun();
        }
        Catalog modules = Catalogs.ofModules(Sys.registry());
        List<SymbolDescriptor> builtin = new ArrayList<>();
        List<SymbolDescriptor> library = new ArrayList<>();
        for (SymbolDescriptor descriptor : roots.roots()) {
            // Встроенное языка отделяется от библиотеки тем же снимком языка,
            // а не списком имён: список разошёлся бы с Builtins на первой же правке.
            // Оттуда же берётся и сам дескриптор — с честным происхождением.
            SymbolDescriptor known = Catalogs.builtins().root(descriptor.name());
            if (known != null) {
                builtin.add(known);
            } else {
                library.add(descriptor);
            }
        }
        System.out.print(asJson
                ? renderCatalogAsJson(builtin, library, modules)
                : renderCatalog(builtin, library, modules));
        System.out.flush();
        return 0;
    }

    private static String renderCatalog(List<SymbolDescriptor> builtin,
                                        List<SymbolDescriptor> library, Catalog modules) {
        StringBuilder out = new StringBuilder(4096);
        out.append("Встроенное в язык (").append(builtin.size()).append("):\n");
        builtin.forEach(descriptor -> appendName(out, "  ", descriptor));
        out.append("\nБиблиотека std (").append(library.size()).append("):\n");
        library.forEach(descriptor -> appendName(out, "  ", descriptor));
        out.append("\nВстроенные модули:\n");
        for (String key : modules.moduleKeys()) {
            ModuleDescriptor module = modules.module(key);
            out.append("  ").append(key);
            if (module.hasDocumentation()) {
                out.append(" — ").append(module.documentation());
            }
            out.append('\n');
            module.names().forEach(descriptor -> appendName(out, "    ", descriptor));
        }
        return out.toString();
    }

    private static void appendName(StringBuilder out, String indent, SymbolDescriptor descriptor) {
        out.append(indent).append(descriptor.signature());
        if (descriptor.hasDocumentation()) {
            int width = Math.max(1, 44 - indent.length() - descriptor.signature().length());
            out.append(" ".repeat(width)).append("— ").append(descriptor.documentation());
        }
        out.append('\n');
    }

    /** Тот же каталог машине: пишется тем же JsonWriter, что и sys.json. */
    private static String renderCatalogAsJson(List<SymbolDescriptor> builtin,
                                              List<SymbolDescriptor> library, Catalog modules) {
        MapValue root = new MapValue();
        root.put("builtins", namesAsJson(builtin));
        root.put("library", namesAsJson(library));
        List<Value> described = new ArrayList<>();
        for (String key : modules.moduleKeys()) {
            ModuleDescriptor module = modules.module(key);
            MapValue entry = new MapValue();
            entry.put("key", StringValue.of(module.key()));
            entry.put("name", StringValue.of(module.name()));
            entry.put("documentation", text(module.documentation()));
            entry.put("names", namesAsJson(module.names()));
            described.add(entry);
        }
        root.put("modules", ArrayValue.of(described));
        return JsonWriter.stringify(root, 2) + System.lineSeparator();
    }

    private static ArrayValue namesAsJson(List<SymbolDescriptor> names) {
        List<Value> items = new ArrayList<>(names.size());
        for (SymbolDescriptor descriptor : names) {
            MapValue entry = new MapValue();
            entry.put("name", StringValue.of(descriptor.name()));
            entry.put("kind", StringValue.of(descriptor.kind().name().toLowerCase(Locale.ROOT)));
            entry.put("signature", StringValue.of(descriptor.signature()));
            entry.put("documentation", text(descriptor.documentation()));
            entry.put("origin", StringValue.of(descriptor.origin().name().toLowerCase(Locale.ROOT)));
            items.add(entry);
        }
        return ArrayValue.of(items);
    }

    private static Value text(String value) {
        return value == null ? NullValue.NULL : StringValue.of(value);
    }

    private ExecutionContext standardContext() {
        ExecutionContext context = ExecutionContext.fresh(Output.standard());
        Std.install(context.scope());
        context.scope().define("args", ArrayValue.of(List.of()));
        // Встроенные модули (sys.io, sys.json, sys.net.http) даёт тот же запуск и тем же
        // способом: набором, а не флагом. Приложение, встраивающее движок, собирает свой —
        // и скрипту доступно ровно то, что в нём есть.
        return context.withNativeModules(Sys.modules()).withLimits(limits());
    }

    /**
     * Пределы из флагов командной строки.
     * <p>
     * Ничего не задано — {@link Limits#none()}: то же умолчание, что и у движка,
     * и по той же причине.
     */
    private Limits limits() {
        if (maxSteps <= 0 && timeoutSeconds <= 0 && maxThreads <= 0) {
            return Limits.none();
        }
        return Limits.builder()
                .maxSteps(Math.max(maxSteps, 0))
                .timeout(timeoutSeconds > 0
                        ? Duration.ofNanos((long) (timeoutSeconds * 1_000_000_000L))
                        : Duration.ZERO)
                .maxThreads(Math.max(maxThreads, 0))
                .build();
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
