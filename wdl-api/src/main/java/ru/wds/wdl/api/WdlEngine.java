package ru.wds.wdl.api;

import ru.wds.wdl.ast.Program;
import ru.wds.wdl.diagnostic.Diagnostics;
import ru.wds.wdl.embed.Library;
import ru.wds.wdl.lexer.Lexer;
import ru.wds.wdl.module.ModuleSource;
import ru.wds.wdl.module.Unit;
import ru.wds.wdl.parser.Parser;
import ru.wds.wdl.resolve.Resolution;
import ru.wds.wdl.resolve.Resolver;
import ru.wds.wdl.runtime.Output;
import ru.wds.wdl.source.Source;
import ru.wds.wdl.value.Value;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Собранная база для выполнения скриптов: что им доступно, куда они печатают,
 * откуда берутся модули.
 *
 * <h2>Движок — это рецепт, а не запуск</h2>
 * Ключевое решение, из которого следует всё остальное. Движок не держит ни одной
 * переменной скрипта и ни одного открытого соединения — он держит <b>состав</b>:
 * какие библиотеки положить, какой набор модулей дать, куда направить вывод. Каждый
 * {@link WdlInstance} собирается по этому рецепту заново и не делит с соседями ничего.
 * <p>
 * Иначе было бы нельзя: «между запусками не переносится ничего» — правило языка,
 * а движок-как-запуск нарушал бы его первым же {@code File.mark = 1}, дошедшим
 * из одного скрипта в следующий. Поэтому здесь и лежат {@link Supplier} библиотек,
 * а не сами библиотеки: библиотека принадлежит запуску и закрывается вместе с ним.
 * <p>
 * Отсюда же и потоки: движок неизменяем и годится для любого их числа, а вот
 * {@link WdlInstance} — это состояние, и работает с ним один поток за раз.
 *
 * <h2>Три уровня использования</h2>
 * <pre>{@code
 * // 1. на один раз
 * Object answer = Wdl.eval("40 + 2");
 *
 * // 2. движок и скрипт
 * WdlEngine engine = WdlEngine.standard(Output.standard());
 * Value result = engine.run(Path.of("script.wdl"));
 *
 * // 3. разбор один раз, запусков много
 * WdlScript script = engine.compile(Path.of("handler.wdl"));
 * for (Player player : players) {
 *     try (WdlInstance instance = script.instance()) {
 *         instance.define("player", player.name());
 *         instance.execute();
 *         instance.function("onTick").call();
 *     }
 * }
 * }</pre>
 */
public final class WdlEngine {

    private final Output output;
    private final ModuleSource sources;
    private final Map<String, Supplier<Library>> modules;
    private final Map<String, Supplier<Library>> rootLibraries;
    private final Map<String, Value> globals;

    private WdlEngine(Builder builder) {
        this.output = builder.output;
        this.sources = builder.sources;
        this.modules = Map.copyOf(builder.modules);
        this.rootLibraries = Map.copyOf(builder.rootLibraries);
        this.globals = Map.copyOf(builder.globals);
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Движок без единого лишнего имени и без вывода.
     * <p>
     * Ровно то же, что {@code builder().build()}, и это состояние по умолчанию
     * не случайно: движок, встроенный в чужое приложение, не печатает в его консоль
     * и не открывает ни диска, ни сети, пока его об этом не попросили.
     */
    public static WdlEngine minimal() {
        return builder().build();
    }

    /**
     * Движок со всей стандартной библиотекой — то же, что берёт консольный {@code wdl}.
     *
     * @param output куда печатает скрипт; {@link Output#standard()} для консоли
     */
    public static WdlEngine standard(Output output) {
        return builder().stdlib(Stdlib.STANDARD).output(output).build();
    }

    /** Движок для скрипта, которому не доверяют: считает и разбирает данные, мира не трогает. */
    public static WdlEngine safe(Output output) {
        return builder().stdlib(Stdlib.SAFE).output(output).build();
    }

    /**
     * Разбирает файл. Модули этого скрипта ищутся рядом с ним, если движку не задан
     * другой источник.
     *
     * @throws WdlException если скрипт не разобрался — со всеми ошибками сразу
     */
    public WdlScript compile(Path file) {
        Objects.requireNonNull(file, "file");
        try {
            Source source = Source.ofFile(file);
            Path parent = file.toAbsolutePath().getParent();
            // Модули ищутся рядом со скриптом, а не рядом с процессом: 'wdl app/main.wdl'
            // обязан находить app/lib/*.wdl, откуда бы его ни запустили.
            return compile(source, sources != null ? sources
                    : ModuleSource.ofDirectory(parent != null ? parent : Path.of("")));
        } catch (IOException e) {
            throw new UncheckedIOException("не удалось прочитать " + file, e);
        }
    }

    /** Разбирает текст. Имя нужно только для сообщений об ошибках. */
    public WdlScript compile(String code, String name) {
        return compile(Source.ofString(code), sources != null ? sources : ModuleSource.none());
    }

    /** Разбирает текст под именем {@code "<script>"}. */
    public WdlScript compile(String code) {
        return compile(code, "<script>");
    }

    private WdlScript compile(Source source, ModuleSource moduleSource) {
        Diagnostics diagnostics = new Diagnostics(source);
        Program program = Parser.parseProgram(Lexer.tokenize(source, diagnostics), diagnostics);
        if (diagnostics.hasErrors()) {
            throw WdlException.syntax(diagnostics);
        }
        // Резолвер расставляет объявления типов так, чтобы родитель выполнялся раньше
        // потомка, и ловит круг в наследовании. Ошибки его — такие же ошибки разбора.
        Resolution resolution = Resolver.resolve(program, diagnostics);
        if (diagnostics.hasErrors()) {
            throw WdlException.syntax(diagnostics);
        }
        return new WdlScript(this, Unit.of(source, program, resolution), moduleSource);
    }

    /**
     * Разбирает и выполняет файл разом, закрывая запуск за собой.
     * <p>
     * Короткий путь для того случая, когда скрипт запускают и забывают. Нужен доступ
     * к тому, что он объявил, или второй запуск того же дерева — {@link #compile}
     * и {@link WdlScript#instance()}.
     *
     * @return значение последней инструкции-выражения файла
     */
    public Value run(Path file) {
        return compile(file).run();
    }

    /** То же самое для текста скрипта. */
    public Value run(String code) {
        return compile(code).run();
    }

    /**
     * Вычисляет одно выражение — не скрипт.
     * <p>
     * Разница существенная, и вот почему. Инструкцией в языке может быть вызов или
     * присваивание, но не голое {@code 40 + 2}: строка, которая ничего не делает, —
     * почти всегда забытое присваивание, и парсер об этом говорит. Здесь же спрашивают
     * именно значение, и {@code 40 + 2} — законный вопрос.
     * <p>
     * Видны выражению встроенные имена, библиотеки движка и то, что положило приложение
     * через {@code define}. Переменных скрипта нет — скрипта тут и не было; чтобы считать
     * в его области, есть {@link WdlInstance#eval}.
     */
    public Value eval(String expression) {
        try (WdlInstance instance = compile("").instance()) {
            return instance.eval(expression);
        }
    }

    Output output() {
        return output;
    }

    Map<String, Supplier<Library>> modules() {
        return modules;
    }

    Map<String, Supplier<Library>> rootLibraries() {
        return rootLibraries;
    }

    Map<String, Value> globals() {
        return globals;
    }

    /**
     * Сборщик движка. Всё необязательно: {@code builder().build()} даёт рабочий движок,
     * которому просто нечего дать скрипту сверх самого языка.
     */
    public static final class Builder {

        private Output output = Output.discarding();
        private ModuleSource sources;
        private final Map<String, Supplier<Library>> modules = new LinkedHashMap<>();
        private final Map<String, Supplier<Library>> rootLibraries = new LinkedHashMap<>();
        private final Map<String, Value> globals = new LinkedHashMap<>();

        private Builder() {
        }

        /**
         * Куда печатают {@code print} и {@code println}.
         * <p>
         * По умолчанию — в никуда. Консоли движок сам себе не назначает: чужое приложение
         * не обязано терпеть чужую печать в своём выводе.
         * <p>
         * {@link Output} — функциональный интерфейс, поэтому лямбда и ссылка на метод
         * подходят без обёрток: {@code .output(logger::info)}, {@code .output(text -> {})}.
         */
        public Builder output(Output value) {
            this.output = Objects.requireNonNull(value, "output");
            return this;
        }

        /**
         * Что из стандартной библиотеки доступно скрипту. По умолчанию — ничего.
         * <p>
         * Зовётся раньше своих {@link #module}: пресет кладётся первым, поэтому своя
         * реализация под тем же именем побеждает — на этом и держится подмена
         * {@code sys/io} в тестах.
         */
        public Builder stdlib(Stdlib preset) {
            Objects.requireNonNull(preset, "preset");
            modules.putAll(preset.modules());
            rootLibraries.putAll(preset.rootLibraries());
            return this;
        }

        /**
         * Свой модуль: {@code import game} найдёт эту библиотеку.
         * <p>
         * Фабрика, а не библиотека, по той же причине, по которой ей же устроен пресет:
         * библиотека принадлежит запуску и закрывается вместе с ним.
         *
         * @param name имя так, как его напишут в {@code import}: {@code "game"},
         *             {@code "sys/io"}
         */
        public Builder module(String name, Supplier<Library> library) {
            modules.put(requireName(name), Objects.requireNonNull(library, "library"));
            return this;
        }

        /**
         * Библиотека, чьи имена ложатся <b>прямо в корень</b>, без {@code import}.
         * <p>
         * Разница с {@link #module} только в том, куда библиотека установлена, —
         * сама она об этом не знает. Имя нужно для диагностики.
         */
        public Builder library(String name, Supplier<Library> library) {
            rootLibraries.put(requireName(name), Objects.requireNonNull(library, "library"));
            return this;
        }

        /**
         * Откуда берутся модули-файлы. По умолчанию — из каталога самого скрипта,
         * а для скрипта-строки не берутся вовсе.
         */
        public Builder sourceRoot(Path root) {
            this.sources = ModuleSource.ofDirectory(Objects.requireNonNull(root, "root"));
            return this;
        }

        /** То же самое, когда модули лежат не на диске: карта «путь → исходник». */
        public Builder sources(ModuleSource value) {
            this.sources = Objects.requireNonNull(value, "sources");
            return this;
        }

        /**
         * Имя, которое скрипт увидит готовым: {@code define("appName", "demo")}.
         * <p>
         * Обычная переменная, а не константа: скрипт вправе её перекрыть, и пространство
         * имён здесь одно, как и везде в языке.
         */
        public Builder define(String name, Object value) {
            globals.put(requireName(name), Values.of(value));
            return this;
        }

        /** То же самое готовым значением языка — для классов и функций от приложения. */
        public Builder defineValue(String name, Value value) {
            globals.put(requireName(name), Objects.requireNonNull(value, "value"));
            return this;
        }

        public WdlEngine build() {
            return new WdlEngine(this);
        }

        private static String requireName(String name) {
            Objects.requireNonNull(name, "name");
            if (name.isBlank()) {
                throw new IllegalArgumentException("имя не может быть пустым");
            }
            return name;
        }
    }
}
