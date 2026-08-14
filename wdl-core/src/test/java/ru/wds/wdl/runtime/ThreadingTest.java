package ru.wds.wdl.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.wds.wdl.ast.Program;
import ru.wds.wdl.diagnostic.Diagnostics;
import ru.wds.wdl.lexer.Lexer;
import ru.wds.wdl.module.ModuleSource;
import ru.wds.wdl.module.ModuleUnits;
import ru.wds.wdl.module.Unit;
import ru.wds.wdl.parser.Parser;
import ru.wds.wdl.resolve.Resolver;
import ru.wds.wdl.source.Source;
import ru.wds.wdl.source.Span;
import ru.wds.wdl.value.Arity;
import ru.wds.wdl.value.CallContext;
import ru.wds.wdl.value.FunctionValue;
import ru.wds.wdl.value.Value;
import ru.wds.wdl.value.types.InstanceObjectValue;
import ru.wds.wdl.value.types.IntValue;
import ru.wds.wdl.value.types.NullValue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Многопоточность: два вопроса, на которые обязан отвечать запуск, когда в него
 * входят из нескольких потоков.
 *
 * <h2>Первый: работают ли потоки одновременно</h2>
 * Проверяется <b>барьером</b>, а не временем. «Скрипт не упал» и «скрипт посчитал
 * правильно» ничего не говорят о параллельности: под глобальным замком оба
 * утверждения тоже верны, просто вызовы стоят в очереди. Барьер спрашивает ровно
 * то, что нужно: два потока обязаны оказаться внутри скрипта <b>в один и тот же
 * момент</b>. Пока внутри запуска работает один — сойтись им негде, и тест краснеет
 * по определению.
 *
 * <h2>Второй: цела ли общая память</h2>
 * Эти проверки обязаны проходить и до снятия замка, и после: замок их держал,
 * теперь держат конкурентные структуры. Точных чисел там, где скрипт не
 * синхронизируется сам, они не требуют — потерянный инкремент в
 * {@code counter = counter + 1} это обещанное поведение (см. {@code docs/threads.md}),
 * а вот потерянный <b>ключ</b> или испорченная таблица — нет.
 */
class ThreadingTest {

    /** Сколько ждать схождения на барьере: заведомо больше любой честной задержки. */
    private static final long MEETING_TIMEOUT_MS = 2000;

    /**
     * Вызывающий, у которого нет ничего, кроме вывода: так выглядит чужое приложение.
     * <p>
     * Вывод берётся {@link Output#serialized} — той же обёрткой, что кладёт на границу
     * запуска {@code ExecutionContext}. Приложение, зовущее скрипт из своих потоков,
     * обязано либо дать потокобезопасный вывод, либо взять эту обёртку; проверяется
     * здесь именно она.
     */
    private record Host(Output out) implements CallContext {

        Host(StringBuilder printed) {
            this(Output.serialized(printed::append));
        }

        @Override
        public void write(String text) {
            out.write(text);
        }
    }

    /**
     * Выполненный скрипт плюс то, что он напечатал.
     * <p>
     * Хозяин один на скрипт, а не один на вызов: у обёртки вывода свой монитор,
     * и заводить её на каждый вызов значило бы не синхронизировать ничего.
     */
    private record Script(Execution done, StringBuilder printed, Host host) {

        Script(Execution done, StringBuilder printed) {
            this(done, printed, new Host(printed));
        }

        Value name(String name) {
            Value value = done.scope().scope().lookup(name);
            assertFalse(value == null, () -> "имя '" + name + "' не объявлено скриптом");
            return value;
        }

        /** Вызов «снаружи»: контекст свой, о запуске ничего не знающий. */
        Value call(String function, Value... arguments) {
            return assertInstanceOf(FunctionValue.class, name(function))
                    .call(host, List.of(arguments), Span.point(0));
        }
    }

    private static Script run(String code) {
        return run(code, Map.of(), scope -> { });
    }

    private static Script run(String code, Consumer<Environment> setup) {
        return run(code, Map.of(), setup);
    }

    private static Script run(String code, Map<String, String> modules, Consumer<Environment> setup) {
        StringBuilder printed = new StringBuilder();
        ExecutionContext context = ExecutionContext.fresh(printed::append)
                .withModules(new ModuleUnits(ModuleSource.ofMap(modules)));
        // Имена приложения ложатся в корень до первой инструкции — как это делает
        // WdlInstance с библиотеками движка.
        setup.accept(context.scope());

        Source source = Source.ofString(code);
        Diagnostics diagnostics = new Diagnostics(source);
        Program program = Parser.parseProgram(Lexer.tokenize(source, diagnostics), diagnostics);
        assertFalse(diagnostics.hasErrors(), () -> "ошибки разбора:\n" + diagnostics.renderAll());
        Unit unit = Unit.of(source, program, Resolver.resolve(program, diagnostics));
        assertFalse(diagnostics.hasErrors(), () -> "ошибки разбора:\n" + diagnostics.renderAll());
        return new Script(new Interpreter().run(unit, context), printed);
    }

    /**
     * Место встречи для двух потоков внутри скрипта.
     * <p>
     * Ждёт с таймаутом, а не бесконечно: не сошлись — тест обязан рассказать об этом
     * сам, а не повиснуть до конца сборки.
     */
    private static BuiltinFunction meeting(CyclicBarrier barrier) {
        return BuiltinFunction.of("meet", Arity.exactly(0), (context, arguments, span) -> {
            try {
                barrier.await(MEETING_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (TimeoutException | BrokenBarrierException notMet) {
                throw new IllegalStateException("потоки не сошлись внутри скрипта за "
                        + MEETING_TIMEOUT_MS + " мс: внутри запуска работает один поток", notMet);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("ожидание прервано", interrupted);
            }
            return NullValue.NULL;
        });
    }

    /** Выполняет задачи на своих потоках и возвращает результаты в порядке задач. */
    private static <T> List<T> inParallel(int threads, List<Callable<T>> work) {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<T> results = new ArrayList<>(work.size());
            for (Future<T> done : pool.invokeAll(work)) {
                try {
                    results.add(done.get());
                } catch (java.util.concurrent.ExecutionException failed) {
                    // Причина интереснее обёртки: тест должен показать ошибку скрипта,
                    // а не ExecutionException вокруг неё.
                    throw failed.getCause() instanceof RuntimeException cause
                            ? cause
                            : new IllegalStateException(failed.getCause());
                }
            }
            return results;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        } finally {
            pool.shutdownNow();
        }
    }

    // --- доказательство параллелизма -----------------------------------------

    @Test
    @DisplayName("две разные функции работают внутри запуска одновременно")
    void twoFunctionsRunAtTheSameTime() {
        CyclicBarrier barrier = new CyclicBarrier(2);
        Script script = run("""
                def first() {
                    meet()
                    return "первая";
                }

                def second() {
                    meet()
                    return "вторая";
                }
                """, scope -> scope.define("meet", meeting(barrier)));

        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            List<String> results = inParallel(2, List.of(
                    () -> script.call("first").display(),
                    () -> script.call("second").display()));
            assertEquals(List.of("первая", "вторая"), results);
        });
    }

    @Test
    @DisplayName("одна и та же функция работает в двух потоках одновременно")
    void oneFunctionRunsTwiceAtTheSameTime() {
        CyclicBarrier barrier = new CyclicBarrier(2);
        Script script = run("""
                def work(mark) {
                    meet()
                    return mark;
                }
                """, scope -> scope.define("meet", meeting(barrier)));

        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            List<String> results = inParallel(2, List.of(
                    () -> script.call("work", IntValue.of(1)).display(),
                    () -> script.call("work", IntValue.of(2)).display()));
            assertEquals(List.of("1", "2"), results);
        });
    }

    @Test
    @DisplayName("вызов после закрытия запуска даёт остановку выполнения, а не работу по закрытому")
    void callAfterCloseStopsExecution() {
        Script script = run("""
                def ping() => "жив"
                """);
        assertEquals("жив", script.call("ping").display());

        script.done().scope().closeRun();

        FatalError stopped = assertInstanceOf(FatalError.class,
                assertThrows(RuntimeException.class,
                        () -> script.call("ping")));
        assertTrue(stopped.getMessage().contains("запуск закрыт"), stopped.getMessage());
    }

    // --- корректность общего состояния ---------------------------------------

    @Test
    @DisplayName("общий объект не теряет ключей при записи из восьми потоков")
    void sharedObjectKeepsEveryKey() {
        int threads = 8;
        int perThread = 500;
        Script script = run("""
                shared = {}

                def put(key) {
                    shared[key] = key
                }

                def size() => len(shared)
                """);

        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
            List<Callable<Void>> work = new ArrayList<>();
            for (int thread = 0; thread < threads; thread++) {
                int base = thread * perThread;
                work.add(() -> {
                    for (int i = 0; i < perThread; i++) {
                        script.call("put", IntValue.of(base + i));
                    }
                    return null;
                });
            }
            inParallel(threads, work);

            // Ключи не пересекаются, поэтому потерять нечего: каждая запись касается
            // своей ячейки. Меньшее число означает не гонку в скрипте, а порчу таблицы
            // внутри движка — то есть ошибку, за которую отвечает движок, а не автор.
            assertEquals(String.valueOf(threads * perThread), script.call("size").display(),
                    "потерянный ключ означает порчу таблицы объекта");
        });
    }

    @Test
    @DisplayName("вызовы из восьми потоков не портят область видимости")
    void sharedScopeSurvivesConcurrentCalls() {
        int threads = 8;
        int perThread = 500;
        Script script = run("""
                calls = 0

                def bump() {
                    calls = calls + 1
                    return calls;
                }

                def total() => calls
                """);

        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
            List<Callable<Void>> work = new ArrayList<>();
            for (int thread = 0; thread < threads; thread++) {
                work.add(() -> {
                    for (int i = 0; i < perThread; i++) {
                        script.call("bump");
                    }
                    return null;
                });
            }
            inParallel(threads, work);

            // Точного числа здесь не спрашивают: 'calls = calls + 1' — не атомарная
            // операция, и потерянные обновления это обещанное поведение языка.
            // Проверяется другое: счётчик остался числом в разумных границах,
            // а таблица имён цела.
            long total = assertInstanceOf(IntValue.class, script.call("total")).value();
            assertTrue(total > 0 && total <= (long) threads * perThread,
                    () -> "счётчик вне границ: " + total);
        });
    }

    @Test
    @DisplayName("модуль выполняется один раз, из скольких бы потоков его ни просили")
    void moduleRunsOnceForAllThreads() {
        int threads = 8;
        AtomicInteger executions = new AtomicInteger();
        Script script = run("""
                def load() {
                    import "./lib" as m
                    return m.token;
                }
                """,
                Map.of("lib", """
                        mark()
                        token = {name: "общий"}
                        """),
                scope -> scope.define("mark", BuiltinFunction.of("mark", Arity.exactly(0),
                        (context, arguments, span) -> {
                            executions.incrementAndGet();
                            return NullValue.NULL;
                        })));

        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
            List<Callable<Value>> work = new ArrayList<>();
            for (int thread = 0; thread < threads; thread++) {
                work.add(() -> script.call("load"));
            }
            List<Value> tokens = inParallel(threads, work);

            assertEquals(1, executions.get(), "тело модуля выполнено больше одного раза");
            // Значение модуля общее для всех импортёров — иначе класс, полученный
            // из одного потока, не был бы тем же классом, что из другого.
            tokens.forEach(token -> assertSame(tokens.get(0), token));
        });
    }

    @Test
    @DisplayName("класс, объявленный в функции, остаётся одним классом для всех потоков")
    void classDeclaredInsideFunctionHasOneShape() {
        int threads = 8;
        Script script = run("""
                def make(x) {
                    class Point(value)
                    return new Point(x);
                }
                """);

        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
            List<Callable<Value>> work = new ArrayList<>();
            for (int thread = 0; thread < threads; thread++) {
                int mark = thread;
                work.add(() -> script.call("make", IntValue.of(mark)));
            }
            List<Value> points = inParallel(threads, work);

            InstanceObjectValue first = assertInstanceOf(InstanceObjectValue.class, points.get(0));
            for (Value point : points) {
                InstanceObjectValue instance = assertInstanceOf(InstanceObjectValue.class, point);
                // Ровно то, что отвечает 'is': формы сравниваются по ссылке, и две формы
                // на один текст означали бы, что 'p is Point' врёт при верном скрипте.
                assertTrue(instance.owner().conformsTo(first.owner()),
                        "объявление класса из разных потоков дало разные формы");
            }
        });
    }

    @Test
    @DisplayName("общий массив не портится: длина растёт, чтение не бросает")
    void sharedArraySurvivesConcurrentGrowth() {
        int threads = 8;
        int perThread = 300;
        Script script = run("""
                shared = []

                def append(value) {
                    shared = shared + [value]
                }

                def size() => len(shared)

                def sum() {
                    total = 0
                    for (item in shared) total += 1
                    return total;
                }
                """);

        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
            List<Callable<Void>> work = new ArrayList<>();
            for (int thread = 0; thread < threads; thread++) {
                work.add(() -> {
                    for (int i = 0; i < perThread; i++) {
                        script.call("append", IntValue.of(i));
                        script.call("sum");
                    }
                    return null;
                });
            }
            inParallel(threads, work);

            // 'shared = shared + [value]' атомарным никто не обещал, поэтому точной
            // длины здесь не спрашивают. Спрашивают, что массив вообще цел и что
            // одновременный перебор не бросил ConcurrentModificationException.
            long size = assertInstanceOf(IntValue.class, script.call("size")).value();
            assertTrue(size > 0 && size <= (long) threads * perThread,
                    () -> "длина массива вне границ: " + size);
            assertEquals(String.valueOf(size), script.call("sum").display());
        });
    }

    // --- synchronized ---------------------------------------------------------

    @Test
    @DisplayName("synchronized def не теряет ни одного инкремента: 8 × 10 000 = 80 000")
    void synchronizedFunctionKeepsEveryIncrement() {
        int threads = 8;
        int perThread = 10_000;
        Script script = run("""
                calls = 0

                synchronized def bump() {
                    calls = calls + 1
                }

                def total() => calls
                """);

        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
            List<Callable<Void>> work = new ArrayList<>();
            for (int thread = 0; thread < threads; thread++) {
                work.add(() -> {
                    for (int i = 0; i < perThread; i++) {
                        script.call("bump");
                    }
                    return null;
                });
            }
            inParallel(threads, work);

            // Ровно столько, сколько вызовов: 'calls = calls + 1' — два обращения,
            // и склеивает их именно модификатор. Без него это число было бы меньше
            // (см. sharedScopeSurvivesConcurrentCalls), и в этом вся разница.
            assertEquals(String.valueOf((long) threads * perThread), script.call("total").display());
        });
    }

    @Test
    @DisplayName("synchronized-методы разных экземпляров друг друга не блокируют")
    void synchronizedMethodsLockTheInstance() {
        CyclicBarrier barrier = new CyclicBarrier(2);
        Script script = run("""
                class Box(mark) {
                    synchronized def work() {
                        meet()
                        return mark;
                    }
                }

                first = new Box("первый")
                second = new Box("второй")

                def runFirst() => first.work()
                def runSecond() => second.work()
                """, scope -> scope.define("meet", meeting(barrier)));

        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            // Замок метода (а не экземпляра) развёл бы эти два вызова по очереди,
            // и на барьере они не встретились бы никогда — хотя объекты разные
            // и мешать друг другу им незачем.
            List<String> results = inParallel(2, List.of(
                    () -> script.call("runFirst").display(),
                    () -> script.call("runSecond").display()));
            assertEquals(List.of("первый", "второй"), results);
        });
    }

    @Test
    @DisplayName("synchronized-методы одного экземпляра взаимно исключаются")
    void synchronizedMethodsOfOneInstanceQueue() {
        CyclicBarrier barrier = new CyclicBarrier(2);
        Script script = run("""
                class Box(mark) {
                    synchronized def one() {
                        meet()
                        return "one";
                    }

                    synchronized def two() {
                        meet()
                        return "two";
                    }
                }

                box = new Box("общий")

                def runOne() => box.one()
                def runTwo() => box.two()
                """, scope -> scope.define("meet", meeting(barrier)));

        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            // Обратная сторона того же правила: замок принадлежит объекту, поэтому
            // два разных его метода одновременно не идут — и сойтись им негде.
            RuntimeException failed = assertThrows(RuntimeException.class,
                    () -> inParallel(2, List.of(
                            () -> script.call("runOne").display(),
                            () -> script.call("runTwo").display())));
            assertTrue(failed.getMessage().contains("не сошлись"),
                    () -> "ожидалась несостоявшаяся встреча, а получено: " + failed);
        });
    }

    @Test
    @DisplayName("synchronized реентрантен: функция зовёт себя и соседа по тому же объекту")
    void synchronizedIsReentrant() {
        Script script = run("""
                class Counter(value = 0) {
                    synchronized def bump(n) {
                        if (n <= 0) return value;
                        value = value + 1
                        return bump(n - 1);
                    }

                    synchronized def twice(n) {
                        bump(n)
                        return bump(n);
                    }
                }

                counter = new Counter()

                def run() => counter.twice(3)
                """);

        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            // Замок не реентрантный превратил бы этот вызов в мгновенную взаимную
            // блокировку — тест повис бы, а не ошибся.
            assertEquals("6", script.call("run").display());
        });
    }

    @Test
    @DisplayName("два замыкания одного 'def' — два разных замка")
    void everyClosureHasItsOwnLock() {
        CyclicBarrier barrier = new CyclicBarrier(2);
        Script script = run("""
                def makeHandler() {
                    return synchronized def () {
                        meet()
                        return "готово";
                    };
                }

                first = makeHandler()
                second = makeHandler()

                def runFirst() => first()
                def runSecond() => second()
                """, scope -> scope.define("meet", meeting(barrier)));

        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            // Замок принадлежит значению-функции: два вычисления одного литерала дают
            // два замыкания с разным захваченным состоянием, и защищать их одним замком
            // было бы неправдой. Проверка — что они и правда расходятся.
            List<String> results = inParallel(2, List.of(
                    () -> script.call("runFirst").display(),
                    () -> script.call("runSecond").display()));
            assertEquals(List.of("готово", "готово"), results);
        });
    }

    @Test
    @DisplayName("строки вывода из разных потоков не рвутся посередине")
    void outputLinesStayWhole() {
        int threads = 8;
        int perThread = 200;
        Script script = run("""
                def say(mark) {
                    println("строка-", mark, "-конец")
                }
                """);

        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
            List<Callable<Void>> work = new ArrayList<>();
            for (int thread = 0; thread < threads; thread++) {
                int mark = thread;
                work.add(() -> {
                    for (int i = 0; i < perThread; i++) {
                        // Печатает вызывающий: вывод берётся у того, кто позвал.
                        script.call("say", IntValue.of(mark));
                    }
                    return null;
                });
            }
            inParallel(threads, work);
        });

        String text = script.printed().toString();
        int lines = 0;
        for (String line : text.split("\\R")) {
            if (line.isEmpty()) {
                continue;
            }
            lines++;
            assertTrue(line.startsWith("строка-") && line.endsWith("-конец"),
                    () -> "строка вывода разорвана: '" + line + "'");
        }
        assertEquals(threads * perThread, lines, "потерянная или лишняя строка вывода");
    }

    @Test
    @DisplayName("ошибка скрипта в чужом потоке остаётся ошибкой скрипта")
    void errorKeepsItsPlaceInOtherThreads() {
        Script script = run("""
                def boom() {
                    return [1][5];
                }
                """);

        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            List<Callable<String>> work = new ArrayList<>();
            for (int thread = 0; thread < 4; thread++) {
                work.add(() -> {
                    try {
                        script.call("boom");
                        return "без ошибки";
                    } catch (WdlRuntimeError error) {
                        return error.getMessage();
                    }
                });
            }
            inParallel(4, work).forEach(message ->
                    assertTrue(message.contains("вне границ"), () -> "не та ошибка: " + message));
        });
    }
}
