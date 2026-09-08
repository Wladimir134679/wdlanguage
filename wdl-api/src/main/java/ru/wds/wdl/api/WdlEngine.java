package ru.wds.wdl.api;

import ru.wds.wdl.ast.Program;
import ru.wds.wdl.bridge.Module;
import ru.wds.wdl.bridge.reflect.FromJava;
import ru.wds.wdl.bridge.reflect.JavaBridge;
import ru.wds.wdl.bridge.reflect.JavaPolicy;
import ru.wds.wdl.diagnostic.Diagnostics;
import ru.wds.wdl.module.Library;
import ru.wds.wdl.runtime.Limits;
import ru.wds.wdl.lexer.Lexer;
import ru.wds.wdl.lexer.Token;
import ru.wds.wdl.metrics.Measure;
import ru.wds.wdl.metrics.Measurement;
import ru.wds.wdl.metrics.Metrics;
import ru.wds.wdl.metrics.MetricsCollector;
import ru.wds.wdl.metrics.Stage;
import ru.wds.wdl.profile.CallProfiler;
import ru.wds.wdl.profile.Profiler;
import ru.wds.wdl.module.ModuleSource;
import ru.wds.wdl.module.Unit;
import ru.wds.wdl.parser.Parser;
import ru.wds.wdl.runtime.Output;
import ru.wds.wdl.runtime.WdlRuntimeError;
import ru.wds.wdl.source.Source;
import ru.wds.wdl.value.Value;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
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
 * Отсюда же и потоки: движок неизменяем и годится для любого их числа. Экземпляр —
 * это состояние, но <b>не «один поток за раз»</b>: внутри запуска потоки работают
 * одновременно, а правила игры описаны в {@code docs/threads.md}.
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
 *
 * // 4. приложение отдаёт скрипту себя
 * WdlEngine embedded = WdlEngine.builder()
 *         .expose(Game.class)        // тип: методы, поля, создание
 *         .define("game", game)      // готовый объект приложения
 *         .build();
 * }</pre>
 *
 * <h2>Что переводится само, а что открывает приложение</h2>
 * {@link Builder#define} принимает строку, число, логическое, список и карту — их
 * язык знает сам. Свой тип он не знает и знать не должен: открыть его — решение
 * приложения, и принимается оно {@link Builder#expose}. Поэтому {@code define}
 * с чужим объектом без {@code expose} — не молчаливая строка {@code "Game@1a2b"}
 * в скрипте, а отказ на сборке движка.
 */
public final class WdlEngine {

    /**
     * Под каким именем движок ставит мост приложения. Тем же, что зовёт себя
     * {@link JavaBridge}: библиотека в корне одна, и имя ей нужно только
     * для диагностики.
     */
    private static final String HOST_LIBRARY = "java";

    private final Output output;
    private final ModuleSource sources;
    private final Map<String, Supplier<Library>> modules;
    private final Map<String, Supplier<Library>> rootLibraries;
    private final Map<String, Value> globals;
    /** Схемы типов, открытых скрипту, — по одной свежей на запуск (см. {@link #hostLibrary}). */
    private final List<Supplier<FromJava>> exposed;
    /** Объекты приложения, которым нужен мост: имя → объект. Общие на все запуски. */
    private final Map<String, Object> hosted;
    /** Что мосту позволено. */
    private final JavaPolicy javaPolicy;
    /** Считать ли время стадий. По умолчанию — нет: движок не считает того, о чём не просили. */
    private final boolean metricsEnabled;
    /** Куда сообщать о каждой законченной стадии, или {@code null}. */
    private final Consumer<Measurement> metricsListener;
    /** Пределы выполнения: шаги, время, квота потоков. */
    private final Limits limits;
    /** Считать ли вызовы. По умолчанию — нет: профиль дороже метрик и просят его реже. */
    private final boolean profileEnabled;

    private WdlEngine(Builder builder) {
        this.output = builder.output;
        this.sources = builder.sources;
        this.modules = Map.copyOf(builder.modules);
        this.globals = Map.copyOf(builder.globals);
        this.exposed = List.copyOf(builder.exposed);
        this.hosted = Collections.unmodifiableMap(new LinkedHashMap<>(builder.hosted));
        this.javaPolicy = builder.javaPolicy;
        this.metricsEnabled = builder.metricsEnabled;
        this.metricsListener = builder.metricsListener;
        this.profileEnabled = builder.profileEnabled;
        this.limits = builder.limits();
        this.rootLibraries = Collections.unmodifiableMap(withHostBridge(builder.rootLibraries));
    }

    /**
     * Добавляет к библиотекам корня мост приложения, если приложению есть что отдать.
     * <p>
     * <b>Последней</b>, и это существенно: библиотеки ставятся по порядку, поэтому
     * объект приложения перекроет одноимённое имя из {@code stdlib}, а не наоборот.
     * Тем же порядком, каким {@link Builder#define} перекрывает всё вообще.
     */
    private Map<String, Supplier<Library>> withHostBridge(Map<String, Supplier<Library>> declared) {
        Map<String, Supplier<Library>> roots = new LinkedHashMap<>(declared);
        if (exposed.isEmpty() && hosted.isEmpty()) {
            return roots;
        }
        if (!hosted.isEmpty() && exposed.isEmpty() && !javaPolicy.wrapUnknown()) {
            // Отказ здесь, а не на первом запуске: обернуть объект нечем ни при каких
            // данных, и ждать выполнения ради предсказуемой ошибки незачем.
            throw new IllegalStateException("объект приложения нечем показать скрипту: "
                    + "имена " + hosted.keySet() + " заданы define(...), но ни один тип "
                    + "не открыт. Откройте их expose(Тип.class) или разрешите обёртки "
                    + "неизвестных типов: policy(JavaPolicy.builder().wrapUnknown(true).build())");
        }
        if (roots.putIfAbsent(HOST_LIBRARY, this::hostLibrary) != null) {
            throw new IllegalStateException("имя '" + HOST_LIBRARY + "' занято своей "
                    + "библиотекой: expose(...) и library(\"" + HOST_LIBRARY + "\", ...) "
                    + "вместе не уживаются — оставьте что-то одно");
        }
        return roots;
    }

    /**
     * Мост этого запуска: открытые типы плюс объекты приложения, обёрнутые им же.
     * <p>
     * Собирается заново на каждый экземпляр — по той же причине, по которой библиотеки
     * задаются фабриками: классы моста принадлежат запуску (у класса свои {@code statics}),
     * и общий на процесс мост переносил бы состояние одного скрипта в следующий.
     * Схемы {@link FromJava} тоже берутся свежие: мост проставляет им свой переводчик,
     * и одна схема на два запуска связала бы их обёртки между собой.
     */
    private Library hostLibrary() {
        JavaBridge.Builder building = JavaBridge.open().policy(javaPolicy);
        exposed.forEach(schema -> building.expose(schema.get()));
        JavaBridge bridge = building.build();
        return Module.named(HOST_LIBRARY)
                .install(scope -> {
                    bridge.installTo(scope);
                    hosted.forEach((name, object) -> scope.define(name, wrapped(bridge, name, object)));
                })
                .onClose(bridge::close)
                .build();
    }

    /**
     * Объект приложения как значение языка — с отказом на языке того, кто вызвал API.
     * <p>
     * {@link WdlRuntimeError} здесь был бы неправдой: ошибся не автор скрипта, а тот,
     * кто собрал движок. То же правило, что и в {@link Values#of}.
     */
    private static Value wrapped(JavaBridge bridge, String name, Object object) {
        try {
            return bridge.wrap(object);
        } catch (WdlRuntimeError refused) {
            throw new IllegalArgumentException("нечем показать скрипту имя '" + name + "': "
                    + object.getClass().getName() + " мосту не открыт. Добавьте "
                    + "expose(" + object.getClass().getSimpleName() + ".class) или разрешите "
                    + "обёртки неизвестных типов: "
                    + "policy(JavaPolicy.builder().wrapUnknown(true).build())", refused);
        }
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

    /**
     * Движок для скрипта, которому не доверяют: считает и разбирает данные, мира
     * не трогает, работает под {@linkplain Limits#safeDefaults() пределами}.
     * <p>
     * Пределы здесь не украшение, а половина обещания: без них
     * {@code while (true) {}} висит навсегда, а {@code for (;;) th.spawn(...)} кладёт
     * приложение. Свои задаются {@link Builder#limits}, а снять их можно явным
     * {@code limits(Limits.none())} — это уже решение приложения, а не умолчание.
     */
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

    /**
     * Разбирает текст. Имя нужно только для сообщений об ошибках — но нужно:
     * приложение, грузящее сотню скриптов из своих ресурсов, обязано узнавать
     * в ошибке, который из них упал.
     */
    public WdlScript compile(String code, String name) {
        Objects.requireNonNull(name, "name");
        return compile(new Source(name, code),
                sources != null ? sources : ModuleSource.none());
    }

    /** Разбирает текст под именем {@code "<script>"}. */
    public WdlScript compile(String code) {
        return compile(code, "<script>");
    }

    private WdlScript compile(Source source, ModuleSource moduleSource) {
        // Замеры разбора принадлежат скрипту, а не движку: движок — рецепт, и одного
        // накопителя на все компиляции быть не может.
        MetricsCollector metrics = newCollector();
        Metrics sink = sinkOf(metrics);
        Diagnostics diagnostics = new Diagnostics(source);
        List<Token> tokens;
        Measure lexing = sink.begin(Stage.LEX, source.name());
        try {
            tokens = Lexer.tokenize(source, diagnostics);
        } finally {
            lexing.close();
        }
        Program program;
        Measure parsing = sink.begin(Stage.PARSE, source.name());
        try {
            program = Parser.parseProgram(tokens, diagnostics);
        } finally {
            parsing.close();
        }
        if (diagnostics.hasErrors()) {
            throw WdlException.syntax(diagnostics);
        }
        return new WdlScript(this, Unit.of(source, program), moduleSource, metrics);
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

    /** Пределы выполнения этого движка; по умолчанию — {@link Limits#none()}. */
    public Limits limits() {
        return limits;
    }

    /**
     * Новый накопитель замеров — по одному на скрипт и на экземпляр.
     * <p>
     * Создаётся всегда, даже с выключенными метриками: тогда в него просто ничего
     * не пишут ({@link #sinkOf} отдаёт конвейеру {@link Metrics#off()}), а приложение
     * получает пустой отчёт вместо {@code null}.
     */
    MetricsCollector newCollector() {
        return metricsListener == null ? Metrics.collecting() : Metrics.collecting(metricsListener);
    }

    /** Приёмник для конвейера: накопитель, если метрики включены, иначе выключенные. */
    Metrics sinkOf(MetricsCollector collector) {
        return metricsEnabled ? collector : Metrics.off();
    }

    /**
     * Новый накопитель профиля — по одному на экземпляр.
     * <p>
     * Создаётся всегда, как и накопитель замеров: с выключенным профилем в него просто
     * ничего не пишут ({@link #profilerOf} отдаёт запуску {@link Profiler#off()}),
     * а приложение получает пустой отчёт вместо {@code null}.
     * <p>
     * Скрипту, в отличие от метрик, накопитель не нужен вовсе: разбор вызовов
     * не делает, и втягивать в профиль оттуда нечего.
     */
    CallProfiler newProfiler() {
        return Profiler.collecting();
    }

    /** Приёмник для запуска: накопитель, если профиль включён, иначе выключенный. */
    Profiler profilerOf(CallProfiler collector) {
        return profileEnabled ? collector : Profiler.off();
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
        private final List<Supplier<FromJava>> exposed = new ArrayList<>();
        private final Map<String, Object> hosted = new LinkedHashMap<>();
        private JavaPolicy javaPolicy = JavaPolicy.strict();
        private boolean metricsEnabled;
        private Consumer<Measurement> metricsListener;
        private boolean profileEnabled;
        /** Пределы, заданные явно, или {@code null} — тогда их выбирает набор. */
        private Limits limits;
        /** Последний заданный набор стандартной библиотеки — от него зависит умолчание. */
        private Stdlib preset;

        private Builder() {
        }

        /**
         * Пределы выполнения: шаги, время, квота потоков.
         * <p>
         * По умолчанию их нет — движок не платит за то, о чём его не просили, — кроме
         * набора {@link Stdlib#SAFE}: он ставит {@link Limits#safeDefaults()} сам,
         * потому что своё обещание («скрипт от пользователя не уронит хозяина») без
         * пределов не держит. Явный вызов сильнее умолчания в обе стороны:
         * {@code limits(Limits.none())} снимает их и у безопасного набора.
         */
        public Builder limits(Limits value) {
            this.limits = Objects.requireNonNull(value, "limits");
            return this;
        }

        /** Короткая форма: сколько шагов можно скрипту; {@code 0} — без предела. */
        public Builder maxSteps(long steps) {
            return limits(current().toBuilder().maxSteps(steps).build());
        }

        /** Короткая форма: сколько может работать запуск; {@link Duration#ZERO} — без предела. */
        public Builder timeout(Duration limit) {
            return limits(current().toBuilder().timeout(limit).build());
        }

        /** Короткая форма: сколько потоков разрешено скрипту; {@code 0} — без предела. */
        public Builder maxThreads(int threads) {
            return limits(current().toBuilder().maxThreads(threads).build());
        }

        /**
         * Пределы, от которых отсчитываются короткие формы.
         * <p>
         * Заданные явно, иначе те, что дал бы набор: {@code stdlib(SAFE).maxSteps(1000)}
         * обязан оставить при себе таймаут и квоту потоков безопасного набора, а не
         * обнулить их за компанию.
         */
        private Limits current() {
            return limits != null ? limits : defaults();
        }

        /** Пределы по умолчанию для выбранного набора библиотек. */
        private Limits defaults() {
            return preset == Stdlib.SAFE ? Limits.safeDefaults() : Limits.none();
        }

        /** Пределы собранного движка: заданные явно или те, что следуют из набора. */
        private Limits limits() {
            return current();
        }

        /**
         * Считать ли время стадий: лексер, парсер, выполнение, закрытие.
         * <p>
         * По умолчанию — нет, и стоит это ровно ноль: выключенный приёмник не создаёт
         * объектов и не смотрит на часы. Отчёт потом спрашивают у {@link WdlScript#metrics()}
         * и {@link WdlInstance#metrics()}.
         */
        public Builder metrics(boolean enabled) {
            this.metricsEnabled = enabled;
            return this;
        }

        /**
         * То же самое плюс сообщение о каждой стадии сразу по её завершении.
         * <p>
         * Это и есть «включить логирование этапов»: приложению не нужно ждать конца
         * запуска — {@code .metrics(m -> log.info("{} {} — {} мс", ...))} пишет стадию
         * тогда, когда она закончилась. Зовётся слушатель в том потоке, где стадия шла.
         */
        public Builder metrics(Consumer<Measurement> listener) {
            this.metricsListener = Objects.requireNonNull(listener, "listener");
            this.metricsEnabled = true;
            return this;
        }

        /**
         * Считать ли вызовы: какая функция скрипта самая горячая.
         * <p>
         * Отдельным ключом от {@link #metrics(boolean)}, потому что это другой вопрос
         * и другая цена. Метрики отвечают «во что ушло время запуска» и стоят ноль;
         * профиль отвечает «где скрипт проводит время» и стоит двух обращений к часам
         * на каждый вызов — скрипт под профилем идёт медленнее. Включать их одной
         * кнопкой значило бы навязывать вторую цену тому, кто просил первое.
         * <p>
         * По умолчанию — нет. Отчёт потом спрашивают у {@link WdlInstance#profile()}.
         */
        public Builder profile(boolean enabled) {
            this.profileEnabled = enabled;
            return this;
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
            this.preset = preset;
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
            return projectRoot(root);
        }

        /** Корень всех файловых импортов; без него используется каталог исполняемого файла. */
        public Builder projectRoot(Path root) {
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
            String key = requireName(name);
            try {
                globals.put(key, Values.of(value));
                hosted.remove(key);
            } catch (IllegalArgumentException notPlain) {
                // Объект приложения: перевода «сам собой» ему нет, и заворачивает его
                // мост — но не сейчас, а на запуске. Классы моста принадлежат запуску,
                // как и всё живое, поэтому здесь остаётся сам объект.
                hosted.put(key, value);
                globals.remove(key);
            }
            return this;
        }

        /**
         * Открыть скрипту Java-тип: конструкторы, методы, поля, статику.
         * <p>
         * Это ответ на «как отдать движку свой движок»: {@code expose(Game.class)} —
         * и скрипт зовёт его методы, а {@code define("game", game)} кладёт рядом
         * готовый объект. Ни того, ни другого {@link #define} в одиночку не может:
         * сам собой в язык переводится строка, число и коллекция, а чужой тип —
         * решение приложения, и принимается оно здесь.
         * <pre>{@code
         * WdlEngine engine = WdlEngine.builder()
         *         .expose(Game.class)
         *         .define("game", game)
         *         .build();
         * }</pre>
         */
        public Builder expose(Class<?> type) {
            Objects.requireNonNull(type, "type");
            return expose(() -> FromJava.everythingOf(type));
        }

        /** То же под своим именем: {@code expose(GameEngine.class, "Game")}. */
        public Builder expose(Class<?> type, String scriptName) {
            Objects.requireNonNull(type, "type");
            String named = requireName(scriptName);
            return expose(() -> FromJava.everythingOf(type).as(named));
        }

        /**
         * Тип описанием: видно ровно то, что перечисляет {@link FromJava}.
         * <p>
         * Фабрика, а не готовая схема, по той же причине, по которой фабрикой
         * задаётся {@link #module}: схема принадлежит запуску — мост проставляет ей
         * свой переводчик, и одна схема на два запуска связала бы их обёртки.
         * <pre>{@code
         * .expose(() -> FromJava.of(Game.class).as("Game")
         *         .method("spawn").bean("score").noConstructors())
         * }</pre>
         */
        public Builder expose(Supplier<FromJava> schema) {
            exposed.add(Objects.requireNonNull(schema, "schema"));
            return this;
        }

        /**
         * Что мосту позволено сверх перечисленного: обёртки неизвестных типов,
         * поиск типа по имени. По умолчанию — {@link JavaPolicy#strict()}:
         * видно ровно то, что открыли.
         */
        public Builder policy(JavaPolicy value) {
            this.javaPolicy = Objects.requireNonNull(value, "policy");
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
