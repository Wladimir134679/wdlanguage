package ru.wds.wdl.api;

import ru.wds.wdl.ast.expr.Expr;
import ru.wds.wdl.diagnostic.Diagnostics;
import ru.wds.wdl.module.Library;
import ru.wds.wdl.lexer.Lexer;
import ru.wds.wdl.metrics.Measure;
import ru.wds.wdl.metrics.Metrics;
import ru.wds.wdl.metrics.MetricsCollector;
import ru.wds.wdl.metrics.MetricsReport;
import ru.wds.wdl.metrics.Stage;
import ru.wds.wdl.module.ModuleSource;
import ru.wds.wdl.module.ModuleUnits;
import ru.wds.wdl.module.NativeModules;
import ru.wds.wdl.module.Unit;
import ru.wds.wdl.parser.Parser;
import ru.wds.wdl.runtime.Environment;
import ru.wds.wdl.runtime.ExecutionContext;
import ru.wds.wdl.runtime.Execution;
import ru.wds.wdl.runtime.Interpreter;
import ru.wds.wdl.runtime.WdlError;
import ru.wds.wdl.source.Source;
import ru.wds.wdl.value.FunctionValue;
import ru.wds.wdl.value.Value;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Один запуск скрипта: его переменные, его модули, его открытые ресурсы.
 * <p>
 * Вторая половина центрального решения проекта — та, где живёт состояние. {@link WdlScript}
 * неизменяем и общий, экземпляр изменяем и ничей: сколько их создано, столько независимых
 * друг от друга скриптов работает в процессе, и общего у них нет ничего — ни переменных,
 * ни классов, ни статических полей библиотек.
 * <pre>{@code
 * try (WdlInstance script = engine.compile(path).instance()) {
 *     script.define("player", "Аня");
 *     Value result = script.execute();
 *
 *     WdlCallable onTick = script.function("onTick");
 *     game.everySecond(onTick.asRunnable());
 * }
 * }</pre>
 *
 * <h2>Почему {@link AutoCloseable}</h2>
 * Библиотеки заводят живое: {@code sys.net.http} — клиента, {@code sys.io} — открытые
 * файлы. Закрывает их хозяин запуска, и здесь это вызывающий. Забыть {@code close()} —
 * значит утечь; {@code try}-с-ресурсами делает эту ошибку невозможной.
 *
 * <h2>Потоки</h2>
 * Экземпляр держит состояние, и <b>звать его можно из любого числа потоков
 * одновременно</b>. Вызовы больше не выстраиваются в очередь: замка запуска нет,
 * а области видимости, объекты, массивы и реестр модулей внутри конкурентны.
 * Раньше здесь было обещание «один поток за раз», и оно перестало быть правдой —
 * вместе с ним перестала быть правдой и молчаливая атомарность
 * {@code count = count + 1} в скрипте.
 * <p>
 * Что запуск обещает автору скрипта, коротко: <b>одно обращение атомарно</b> —
 * чтение и запись переменной, поля, элемента не увидят «половину» значения
 * и не потеряются. Выражение целиком — нет. Склеить несколько обращений в одно
 * можно {@code synchronized} на функции, {@code th.lock()} и {@code th.counter()};
 * подробно — в {@code docs/threads.md}.
 * <p>
 * {@link #close()} останавливает потоки, заведённые скриптом, и только потом закрывает
 * модули: вызов функции после закрытия даёт внятную остановку, а не работу
 * по закрытым ресурсам.
 */
public final class WdlInstance implements AutoCloseable {

    /**
     * Сколько ждать потоки скрипта при закрытии.
     * <p>
     * Ждать вечно нельзя — тогда один зациклившийся поток вешает закрытие приложения;
     * не ждать вовсе тоже нельзя — поток, честно выходящий по {@code defer}, обязан
     * успеть закрыть своё. Пять секунд — с запасом на первое и без заметной задержки
     * во втором.
     */
    private static final long THREAD_STOP_TIMEOUT_MILLIS = 5000;

    private final Unit unit;
    private final ExecutionContext context;
    /** Замеры этого запуска — вместе с втянутым сюда разбором скрипта. */
    private final MetricsCollector metrics;
    /** Библиотеки, положенные в корень: {@code Modules} про них не знает, закрывать нам. */
    private final List<Library> rootLibraries = new ArrayList<>();
    private final Interpreter interpreter = new Interpreter();

    private Execution done;
    private boolean closed;

    WdlInstance(WdlEngine engine, Unit unit, ModuleSource sources, MetricsReport compiled) {
        this.unit = Objects.requireNonNull(unit, "unit");
        // Замеры разбора втягиваются сразу: приложению нужен один отчёт на
        // «скомпилировали и выполнили», хотя стадии разнесены по двум объектам.
        this.metrics = engine.newCollector();
        this.metrics.adopt(compiled);
        Metrics sink = engine.sinkOf(this.metrics);
        // Своя корневая область — то, что делает изоляцию запусков настоящей.
        // Здесь же выполняется прелюдия и появляются println, len и классы ошибок.
        ExecutionContext fresh = ExecutionContext.fresh(engine.output());
        Environment root = fresh.scope();
        // Порядок важен: имена приложения ложатся в корень до того, как этот корень
        // запомнится модулями, — иначе модуль их не увидел бы.
        engine.rootLibraries().forEach((name, factory) -> {
            Library library = factory.get();
            rootLibraries.add(library);
            library.installTo(root);
        });
        engine.globals().forEach(root::define);
        this.context = fresh
                .withMetrics(sink)
                // Пределы — свойство запуска, и ставятся они здесь, до первой инструкции
                // скрипта: отсчёт времени начнётся сам, на первом входе в скрипт.
                .withLimits(engine.limits())
                // Приёмник достаётся и реестру модулей: их разбор случается внутри
                // движка, снаружи туда не дотянуться.
                .withModules(new ModuleUnits(sources, sink))
                .withNativeModules(NativeModules.of(engine.modules()));
    }

    /**
     * Выполняет скрипт.
     * <p>
     * Второй раз выполнять нечего: скрипт уже отработал, его имена объявлены, а повтор
     * означал бы, что {@code const} объявляется дважды. Нужен ещё один прогон — нужен
     * ещё один экземпляр, он для того и дешёвый.
     *
     * @return значение последней инструкции-выражения файла; {@code null}-значение,
     *         если выражения в конце нет
     * @throws WdlException если скрипт упал — с местом в его тексте и путём по вызовам
     */
    public Value execute() {
        checkOpen();
        if (done != null) {
            throw new IllegalStateException("этот запуск уже выполнен: "
                    + "для повторного нужен новый instance()");
        }
        try {
            done = interpreter.run(unit, context);
            return done.value();
        } catch (WdlError error) {
            throw WdlException.runtime(error, unit.source());
        }
    }

    /**
     * Имя, объявленное скриптом, или {@code null}, если такого нет.
     * <p>
     * Ищется в области <b>файла</b>, а не в корневой: скрипт выполняется в своей области
     * поверх корня, и его переменные живут именно там. До выполнения искать нечего —
     * ответ будет {@code null} для всего.
     */
    public Value lookup(String name) {
        Objects.requireNonNull(name, "name");
        checkOpen();
        return done == null ? null : done.scope().scope().lookup(name);
    }

    /** То же самое обычным Java-объектом: строкой, числом, списком, картой. */
    public Object get(String name) {
        Value value = lookup(name);
        return value == null ? null : Values.toJava(value);
    }

    /**
     * Функция, объявленная скриптом, — готовая к вызову откуда угодно.
     * <p>
     * Это точка, ради которой всё остальное: дальше приложение работает с обычным
     * Java-объектом и о том, что за ним скрипт, знать не обязано. См. {@link WdlCallable}.
     *
     * @throws IllegalStateException если имени нет или под ним лежит не функция —
     *                               это ошибка в договорённости между приложением
     *                               и скриптом, и молчать о ней нельзя
     */
    public WdlCallable function(String name) {
        Value value = lookup(name);
        if (value == null) {
            throw new IllegalStateException(done == null
                    ? "скрипт ещё не выполнен: сначала execute()"
                    : "скрипт не объявил '" + name + "'");
        }
        if (!(value instanceof FunctionValue function)) {
            throw new IllegalStateException("'" + name + "' — не функция, а "
                    + value.type().title() + " (" + value.display() + ")");
        }
        return new WdlCallable(name, function, context, unit.source());
    }

    /** Есть ли у скрипта такая функция — когда обработчик необязателен. */
    public boolean hasFunction(String name) {
        return lookup(name) instanceof FunctionValue;
    }

    /**
     * Кладёт имя в корневую область до выполнения: скрипт увидит его готовым.
     * <p>
     * В корень, а не в область файла, по той же причине, по которой туда ложатся
     * библиотеки: модули запуска тоже должны это имя видеть. После {@code execute()}
     * класть поздно — скрипт уже прочитан.
     */
    public WdlInstance define(String name, Object value) {
        return defineValue(name, Values.of(value));
    }

    /** То же самое готовым значением языка — для классов и функций от приложения. */
    public WdlInstance defineValue(String name, Value value) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(value, "value");
        checkOpen();
        if (done != null) {
            throw new IllegalStateException("скрипт уже выполнен: имя '" + name
                    + "' он всё равно не увидит");
        }
        context.scope().define(name, value);
        return this;
    }

    /**
     * Вычисляет выражение в области выполненного скрипта.
     * <p>
     * То, чем живёт REPL и отладочная консоль: {@code instance.eval("player.health")}
     * видит всё, что видит сам скрипт.
     *
     * @throws WdlException если выражение не разобралось или упало
     */
    public Value eval(String expression) {
        checkOpen();
        Source source = Source.ofString(expression);
        Diagnostics diagnostics = new Diagnostics(source);
        Expr parsed = Parser.parseExpression(Lexer.tokenize(source, diagnostics), diagnostics);
        if (diagnostics.hasErrors()) {
            throw WdlException.syntax(diagnostics);
        }
        // Область — та, в которой скрипт закончил: до выполнения её ещё нет, и тогда
        // остаётся корневая, где видны только встроенные имена и то, что положило
        // приложение.
        try {
            return interpreter.eval(parsed, done == null ? context : done.scope());
        } catch (WdlError error) {
            // Место у ошибки указывает в текст выражения, а не скрипта: считали-то его.
            throw WdlException.runtime(error, source);
        }
    }

    /** Область запуска — для того, кому нужен доступ к ядру напрямую. */
    public ExecutionContext context() {
        return context;
    }

    /**
     * Закрывает всё живое, что завёл запуск: потоки скрипта, модули, библиотеки корня —
     * в порядке, обратном созданию.
     *
     * <h2>Порядок здесь и есть содержание метода</h2>
     * <ol>
     *   <li><b>Потоки скрипта</b> — пока модули ещё открыты. Поток, выходящий
     *       по {@code defer} или {@code use}, обязан успеть закрыть своё по живому
     *       соединению, а не по закрытому.</li>
     *   <li><b>Модули</b> — и <b>вход в скрипт при этом ещё открыт</b>. Модуль вправе
     *       звать скрипт во время собственного закрытия, и это не теоретическая
     *       возможность: {@code sys.gui} ждёт, пока пользователь закроет окна,
     *       а обработчики кнопок всё это время работают. Закрой мы вход раньше —
     *       первое же нажатие давало бы «запуск закрыт» вместо работы.</li>
     *   <li><b>Потоки скрипта ещё раз</b> — те, что завелись в пункте 2. У чат-клиента
     *       с интерфейсом это как раз слушатель сокета: он появляется по нажатию
     *       кнопки, то есть уже после первой остановки.</li>
     *   <li><b>Вход</b> — последним. С этого момента вызов функции даёт внятную
     *       остановку выполнения, а не работу по закрытым ресурсам.</li>
     * </ol>
     * Повторный вызов ничего не делает: {@code close()} обязан быть безобидным, иначе
     * {@code try}-с-ресурсами вокруг явного закрытия стал бы ошибкой.
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        // Закрытие — тоже время запуска, и иногда основное: sys.gui в close() ждёт,
        // пока пользователь закроет окна.
        Measure closing = context.metrics().begin(Stage.SHUTDOWN, subject());
        try {
            stopThreads();
            context.shutdownModules();
            closeRootLibraries();
            // Второй раз — ради того, что завелось, пока закрывались модули: см. javadoc.
            stopThreads();
            // И только теперь вход: всё, что имело право позвать скрипт, уже отработало.
            context.closeRun();
        } finally {
            closing.close();
            metrics.finish();
        }
    }

    /**
     * Отчёт о времени стадий этого запуска: разбор скрипта, разбор и выполнение его
     * модулей, само выполнение и закрытие.
     * <p>
     * Один отчёт на всё, что с этим скриптом произошло: замеры разбора втянуты сюда
     * при создании экземпляра. Пуст, если движок собран без {@code metrics(true)}.
     * <p>
     * Спрашивать можно и до {@link #close()} — тогда закрытия в нём ещё нет,
     * а «время по часам» отвечает «сколько прошло к этой минуте».
     */
    public MetricsReport metrics() {
        return metrics;
    }

    /** Над чем работали стадии этого запуска — имя файла скрипта. */
    private String subject() {
        return unit.source() != null ? unit.source().name() : "<script>";
    }

    /** Библиотеки корня — в порядке, обратном созданию. */
    private void closeRootLibraries() {
        for (int i = rootLibraries.size() - 1; i >= 0; i--) {
            Library library = rootLibraries.get(i);
            try {
                library.close();
            } catch (RuntimeException | LinkageError failure) {
                // Запуск уже отработал, и ронять его закрытием нечестно: то, что скрипт
                // напечатал, он напечатал. Но и молчать нельзя — потерянный ресурс хуже
                // громкой строки в логе.
                System.getLogger(WdlInstance.class.getName()).log(System.Logger.Level.WARNING,
                        "библиотека '" + library.name() + "' не закрылась", failure);
            }
        }
        rootLibraries.clear();
    }

    /** Прерывает потоки скрипта и ждёт их; не завершившихся называет по именам. */
    private void stopThreads() {
        List<String> stubborn = context.stopScriptThreads(THREAD_STOP_TIMEOUT_MILLIS);
        if (!stubborn.isEmpty()) {
            // Названы поимённо: «приложение не завершается» без имени потока —
            // загадка на полдня, а с именем — строка в скрипте, где забыли выход
            // из цикла или проверку прерывания.
            System.getLogger(WdlInstance.class.getName()).log(System.Logger.Level.WARNING,
                    "потоки скрипта не завершились за " + THREAD_STOP_TIMEOUT_MILLIS + " мс: "
                            + String.join(", ", stubborn));
        }
    }

    private void checkOpen() {
        if (closed) {
            throw new IllegalStateException("запуск закрыт");
        }
    }
}
