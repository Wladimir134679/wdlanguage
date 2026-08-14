package ru.wds.wdl.api;

import ru.wds.wdl.ast.expr.Expr;
import ru.wds.wdl.diagnostic.Diagnostics;
import ru.wds.wdl.embed.Library;
import ru.wds.wdl.lexer.Lexer;
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
 * Экземпляр держит состояние, и работает с ним один поток за раз. Это не значит, что
 * звать его можно только из одного: вход в скрипт снаружи берёт замок запуска, поэтому
 * вызовы из разных потоков выстраиваются в очередь, а не портят друг другу переменные.
 * Настоящая параллельность — это несколько экземпляров.
 */
public final class WdlInstance implements AutoCloseable {

    private final Unit unit;
    private final ExecutionContext context;
    /** Библиотеки, положенные в корень: {@code Modules} про них не знает, закрывать нам. */
    private final List<Library> rootLibraries = new ArrayList<>();
    private final Interpreter interpreter = new Interpreter();

    private Execution done;
    private boolean closed;

    WdlInstance(WdlEngine engine, Unit unit, ModuleSource sources) {
        this.unit = Objects.requireNonNull(unit, "unit");
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
                .withModules(new ModuleUnits(sources))
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
     * Закрывает всё живое, что завёл запуск: сначала модули, потом библиотеки корня —
     * в порядке, обратном созданию.
     * <p>
     * Повторный вызов ничего не делает: {@code close()} обязан быть безобидным, иначе
     * {@code try}-с-ресурсами вокруг явного закрытия стал бы ошибкой.
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        context.shutdownModules();
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

    private void checkOpen() {
        if (closed) {
            throw new IllegalStateException("запуск закрыт");
        }
    }
}
