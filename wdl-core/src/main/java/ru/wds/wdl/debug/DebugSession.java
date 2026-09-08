package ru.wds.wdl.debug;

import ru.wds.wdl.ast.expr.Expr;
import ru.wds.wdl.ast.stmt.Stmt;
import ru.wds.wdl.diagnostic.Diagnostic;
import ru.wds.wdl.diagnostic.Diagnostics;
import ru.wds.wdl.lexer.Lexer;
import ru.wds.wdl.parser.Parser;
import ru.wds.wdl.runtime.ExecutionContext;
import ru.wds.wdl.runtime.Interpreter;
import ru.wds.wdl.runtime.Run;
import ru.wds.wdl.source.Source;
import ru.wds.wdl.source.Span;
import ru.wds.wdl.value.Value;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.LockSupport;

/**
 * Сессия отладки: точки останова, остановленные потоки, шаги и вычисления в кадре.
 * <p>
 * Это единственная настоящая реализация {@link Debugger}. Подключается к живому
 * запуску ({@link #attach(ExecutionContext)}) и отпускает его ({@link #detach()});
 * пока не подключена — движок о ней не знает и ничего не платит.
 *
 * <h2>Остановка — парковка на safepoint, а не {@code Thread.suspend}</h2>
 * {@code Thread.suspend} из JDK убран, и правильно: поток, остановленный в произвольной
 * точке, оставляет за собой захваченные замки и полуизменённые структуры. JVM
 * останавливает потоки иначе — ставит флаг и ждёт, пока каждый сам дойдёт до ближайшей
 * безопасной точки. Здесь так же, и безопасные точки уже есть: каждая инструкция
 * ({@link Debugger#at}) и каждый шаг лимитов ({@link Debugger#poll} из
 * {@code Run.checkpoint} — итерация цикла, вызов, создание экземпляра).
 * <p>
 * Поток встаёт {@link LockSupport#park}, а не {@code interrupt}: прерывание в этом
 * движке означает «выполнение остановлено» и приводит к {@code FatalError}. Пауза —
 * это не отмена.
 *
 * <h2>Чего остановка не может</h2>
 * <ul>
 *   <li>поток внутри блокирующего вызова Java ({@code th.sleep}, чтение сокета,
 *       ожидание замка) не встанет, пока не вернётся в скрипт. В JVM ровно то же:
 *       поток в native считается уже находящимся в safepoint;</li>
 *   <li>поток, вставший внутри {@code synchronized}-функции или под {@code th.lock()},
 *       <b>продолжает держать замок</b>. Другой поток, ждущий этот замок, до своей
 *       безопасной точки не дойдёт никогда. Это тоже поведение JVM, и выход из него
 *       тот же — {@link SuspendPolicy#THREAD}.</li>
 * </ul>
 * Оба ограничения записаны в {@code docs/debugging.md} — как записана в
 * {@code docs/limits.md} невозможность остановить непрерываемый счёт.
 *
 * <h2>Часы запуска на паузе стоят</h2>
 * Пока стоит хоть один поток, сессия копит время и на выходе отдаёт его запуску
 * ({@code Run.extendDeadline}). Иначе сеанс отладки скрипта под {@code Stdlib.SAFE}
 * жил бы ровно тридцать секунд, после чего человек, читающий панель переменных,
 * получал бы «вышло время выполнения».
 */
public final class DebugSession implements Debugger {

    /** Сколько ждать вычисления в кадре, прежде чем считать его зависшим. */
    private static final long DEFAULT_EVAL_TIMEOUT_MILLIS = 5_000;

    private final Breakpoints breakpoints = new Breakpoints();

    /** Интерпретатор для вычислений в кадре: он без состояния, одного хватает. */
    private final Interpreter interpreter = new Interpreter();

    /** Состояния потоков по идентификатору — для отладчика, который спрашивает снаружи. */
    private final Map<Long, ThreadState> threads = new ConcurrentHashMap<>();

    /** Состояние своего потока — для шага, который спрашивает изнутри. */
    private final ThreadLocal<ThreadState> local = ThreadLocal.withInitial(() -> {
        ThreadState state = new ThreadState(Thread.currentThread());
        threads.put(state.id(), state);
        return state;
    });

    /** Замок координации: остановки, возобновления, счёт паузы. Короткие критические секции. */
    private final Object lock = new Object();

    private volatile DebugListener listener = DebugListener.none();
    private volatile SuspendPolicy policy = SuspendPolicy.ALL;
    private volatile boolean detached;

    /** Просьба встать всем: ставится паузой и остановкой под политикой «все». */
    private volatile boolean pauseAll;

    /** Куда подключились; {@code null}, пока не подключены. */
    private volatile ExecutionContext attached;
    private volatile Run run;

    private volatile long evalTimeoutMillis = DEFAULT_EVAL_TIMEOUT_MILLIS;

    /** Сколько потоков стоит прямо сейчас — под {@link #lock}. */
    private int standing;

    /** Когда встал первый из них, в шкале {@code nanoTime} — под {@link #lock}. */
    private long standingSince;

    public DebugSession() {
    }

    public DebugSession(DebugListener listener) {
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    // --- подключение ---------------------------------------------------------

    /**
     * Подключается к запуску: с этой минуты его шаги проходят через сессию.
     * <p>
     * Можно и до первой инструкции, и посреди работы — второе и есть режим
     * {@code attach}, ради которого отладчик заводится в приложении со встроенным
     * движком.
     *
     * @return тот же контекст — чтобы вызов вставал в цепочку сборки запуска
     */
    public ExecutionContext attach(ExecutionContext context) {
        Objects.requireNonNull(context, "context");
        this.attached = context;
        this.run = context.run();
        this.detached = false;
        context.withDebugger(this);
        return context;
    }

    /**
     * Отпускает запуск: точки перестают срабатывать, все стоящие потоки идут дальше.
     * <p>
     * Порядок важен: сначала флаг, потом снятие приёмника, потом побудка. Поток,
     * проснувшийся раньше снятия приёмника, увидит {@link #detached} и не встанет
     * снова.
     */
    public void detach() {
        detached = true;
        ExecutionContext context = attached;
        if (context != null) {
            context.withDebugger(Debugger.off());
        }
        releaseAll(null);
        attached = null;
    }

    /** Точки останова этой сессии. */
    public Breakpoints breakpoints() {
        return breakpoints;
    }

    /** Кому рассказывать о событиях. */
    public void listener(DebugListener replacement) {
        this.listener = Objects.requireNonNull(replacement, "listener");
    }

    /** Кого останавливать при срабатывании точки; по умолчанию — всех. */
    public SuspendPolicy policy() {
        return policy;
    }

    public void policy(SuspendPolicy replacement) {
        this.policy = Objects.requireNonNull(replacement, "policy");
    }

    /** Сколько ждать вычисления в кадре, прежде чем прервать его. */
    public void evalTimeout(long millis) {
        if (millis <= 0) {
            throw new IllegalArgumentException("срок вычисления должен быть положительным: " + millis);
        }
        this.evalTimeoutMillis = millis;
    }

    // --- точки съёма ---------------------------------------------------------

    @Override
    public void at(Stmt stmt, ExecutionContext context) {
        ThreadState state = local.get();
        // Запоминается место всегда, даже когда останавливаться не собираемся:
        // на нём стоит панель кадров того потока, который встанет следующим.
        state.record(stmt, context);
        if (detached || state.evaluating) {
            return;
        }
        StopReason reason = reasonFor(state, stmt, context);
        if (reason != null) {
            suspend(state, reason);
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * Здесь срабатывает только пауза, но не точка останова и не шаг: у этой точки
     * съёма нет контекста, а значит, нет ни файла для сравнения с точкой, ни глубины
     * для шага. Место для панели кадров берётся от последнего {@link #at} —
     * это ближайшая инструкция, и она же честный ответ на «где мы стоим».
     */
    @Override
    public void poll(Span span) {
        if (detached) {
            return;
        }
        ThreadState state = local.get();
        if (state.evaluating || (!pauseAll && !state.pauseRequested)) {
            return;
        }
        if (state.top() < 0) {
            // Поток ещё не выполнил ни одной инструкции: показывать нечего,
            // а встать без места значит показать пустой отладчик.
            return;
        }
        suspend(state, StopReason.PAUSE);
    }

    /** Надо ли этому потоку встать здесь — и если да, то почему. */
    private StopReason reasonFor(ThreadState state, Stmt stmt, ExecutionContext context) {
        if (pauseAll || state.pauseRequested) {
            return StopReason.PAUSE;
        }
        if (!breakpoints.isEmpty()) {
            Source source = context.unit().source();
            if (source != null && breakpoints.has(source.name(), stmt.span().start())) {
                return StopReason.BREAKPOINT;
            }
        }
        StepMode mode = state.mode;
        if (mode == StepMode.RUN) {
            return null;
        }
        int depth = context.callDepth();
        boolean done = switch (mode) {
            case INTO -> true;
            case OVER -> depth <= state.stepDepth;
            case OUT -> depth < state.stepDepth;
            case RUN -> false;
        };
        return done ? StopReason.STEP : null;
    }

    // --- остановка -----------------------------------------------------------

    /**
     * Ставит поток и держит его, пока не возобновят.
     * <p>
     * Выполняется <b>потоком скрипта</b>: он же выполнит и вычисления, которые
     * отладчик пришлёт, пока он стоит, — контекст, области и замки принадлежат ему,
     * и делать это за него из чужого потока нельзя.
     */
    private void suspend(ThreadState state, StopReason reason) {
        synchronized (lock) {
            if (policy == SuspendPolicy.ALL) {
                pauseAll = true;
            }
            state.pauseRequested = false;
            state.mode = StepMode.RUN;
            state.reason = reason;
            state.suspended = true;
            if (standing++ == 0) {
                standingSince = System.nanoTime();
            }
        }
        // Слушателю сообщается вне замка: обработчик вправе тут же возобновить поток,
        // а делать это, держа наш замок, значило бы звать чужой код под ним.
        listener.suspended(new SuspendedEvent(state.info(), reason, framesOf(state)));
        try {
            park(state);
        } finally {
            synchronized (lock) {
                state.suspended = false;
                state.reason = null;
                if (--standing == 0) {
                    stopClock(System.nanoTime() - standingSince);
                }
            }
            listener.resumed(state.id());
        }
    }

    /**
     * Цикл парковки: спать, пока стоим, и выполнять присланное.
     * <p>
     * Прерывание из цикла выходит: закрытие запуска и сторож таймаута прерывают
     * потоки, находящиеся внутри, и поток, застрявший в паузе отладчика, обязан
     * на это ответить — иначе {@code close()} приложения ждал бы отладчика.
     */
    private void park(ThreadState state) {
        while (true) {
            FutureTask<?> task = state.tasks.poll();
            if (task != null) {
                task.run();
                continue;
            }
            if (!state.suspended || detached || Thread.currentThread().isInterrupted()) {
                return;
            }
            LockSupport.park(this);
        }
    }

    /** Отдаёт запуску время, которое он простоял. */
    private void stopClock(long nanos) {
        Run known = run;
        if (known != null) {
            known.extendDeadline(nanos);
        }
    }

    // --- команды отладчика ---------------------------------------------------

    /**
     * Просит все потоки встать на ближайшей безопасной точке.
     * <p>
     * Сразу они не встанут, и это не недостаток: остановка в произвольном месте —
     * это и есть то, чего движок себе не позволяет. Поток, ушедший в блокирующий
     * вызов Java, встанет по возвращении в скрипт.
     */
    public void pause() {
        pauseAll = true;
    }

    /** Просит встать один поток. */
    public void pause(long threadId) {
        require(threadId).pauseRequested = true;
    }

    /**
     * Возобновляет остановленные потоки.
     * <p>
     * Под политикой «все» возобновляются все: оставить одного стоять, сняв просьбу
     * стоять со всех, значило бы, что он тут же встанет снова на следующей же
     * инструкции.
     */
    public void resume(long threadId) {
        ThreadState state = require(threadId);
        state.mode = StepMode.RUN;
        releaseAll(state);
    }

    /** Возобновляет всех. */
    public void resumeAll() {
        releaseAll(null);
    }

    /**
     * Шаг в указанном потоке: {@code into}, {@code over} или {@code out}.
     * <p>
     * Глубина, от которой считается шаг, берётся у текущего кадра потока —
     * той самой {@code Frame.depth()}, что уже посчитана при вызове.
     */
    public void step(long threadId, StepMode mode) {
        Objects.requireNonNull(mode, "mode");
        ThreadState state = require(threadId);
        if (!state.suspended) {
            throw new DebugException("поток не остановлен: шагать в нём нечем");
        }
        state.stepDepth = Math.max(state.top(), 0);
        state.mode = mode;
        releaseAll(state);
    }

    /**
     * Отпускает стоящие потоки; у всех, кроме {@code stepping}, снимается и режим шага.
     *
     * @param stepping поток, которому шаг только что назначен, или {@code null}
     */
    private void releaseAll(ThreadState stepping) {
        List<ThreadState> waking = new ArrayList<>();
        synchronized (lock) {
            pauseAll = false;
            for (ThreadState state : threads.values()) {
                state.pauseRequested = false;
                if (state != stepping) {
                    state.mode = StepMode.RUN;
                }
                if (state.suspended) {
                    state.suspended = false;
                    waking.add(state);
                }
            }
        }
        // Побудка вне замка: разбуженный поток первым делом пойдёт за состоянием,
        // и держать замок в этот момент значило бы затормозить его на ровном месте.
        for (ThreadState state : waking) {
            LockSupport.unpark(state.thread());
        }
    }

    // --- что видно снаружи ---------------------------------------------------

    /**
     * Потоки, которые делали шаги в этом запуске.
     * <p>
     * Заодно чистит записи умерших: реестр наполняется сам, при первом шаге потока,
     * и без уборки пул из тысячи коротких потоков оставил бы тысячу записей.
     */
    public List<ThreadInfo> threads() {
        List<ThreadInfo> found = new ArrayList<>();
        for (Iterator<ThreadState> it = threads.values().iterator(); it.hasNext(); ) {
            ThreadState state = it.next();
            if (!state.thread().isAlive() && !state.suspended) {
                it.remove();
                continue;
            }
            found.add(state.info());
        }
        return List.copyOf(found);
    }

    /** Стоит ли этот поток. */
    public boolean isSuspended(long threadId) {
        ThreadState state = threads.get(threadId);
        return state != null && state.suspended;
    }

    /**
     * Кадры потока: от текущего к верхнему уровню файла.
     * <p>
     * Осмысленны, только пока поток стоит: у работающего они устареют раньше, чем
     * их успеют прочитать.
     */
    public List<DebugFrame> frames(long threadId) {
        ThreadState state = threads.get(threadId);
        return state == null ? List.of() : framesOf(state);
    }

    private List<DebugFrame> framesOf(ThreadState state) {
        int top = state.top();
        List<DebugFrame> frames = new ArrayList<>(Math.max(top + 1, 1));
        for (int depth = top; depth >= 0; depth--) {
            ExecutionContext context = state.contextAt(depth);
            Stmt stmt = state.statementAt(depth);
            if (context == null || stmt == null) {
                continue;
            }
            frames.add(new DebugFrame(depth, context.functionName(), context.unit().source(),
                    stmt.span(), context));
        }
        return List.copyOf(frames);
    }

    // --- вычисление в кадре --------------------------------------------------

    /**
     * Вычисляет выражение в кадре остановленного потока.
     * <p>
     * <b>Считает сам остановленный поток</b>, а не тот, кто попросил. Иначе выражение
     * выполнялось бы с чужими {@code ThreadLocal} (счётчики шагов и входов), мимо
     * замков {@code synchronized}, которые держит остановленный, и в чужой цепочке
     * кадров. Ровно так же устроен JDWP.
     * <p>
     * На время вычисления точки останова в этом потоке выключены: точка внутри
     * вызванной функции остановила бы поток, который и так стоит.
     * <p>
     * Затянувшееся вычисление прерывается по {@link #evalTimeout(long)}: выражение
     * из панели «Watches» не должно вешать сессию. Прерывание доходит до скрипта
     * той же дорогой, что и остановка выполнения, — через {@code Run.checkpoint}.
     *
     * @param threadId остановленный поток
     * @param depth    глубина кадра из {@link #frames(long)}
     */
    public Value evaluate(long threadId, int depth, String expression) {
        Objects.requireNonNull(expression, "expression");
        ThreadState state = require(threadId);
        if (!state.suspended) {
            throw new DebugException("поток не остановлен: вычислять в нём нечего");
        }
        ExecutionContext frame = state.contextAt(depth);
        if (frame == null) {
            throw new DebugException("у потока нет кадра глубины " + depth);
        }
        FutureTask<Value> task = new FutureTask<>(() -> evaluateIn(state, frame, expression));
        state.tasks.add(task);
        LockSupport.unpark(state.thread());
        try {
            return task.get(evalTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException slow) {
            state.evalInterrupted = true;
            state.thread().interrupt();
            throw new DebugException("вычисление не уложилось в " + evalTimeoutMillis
                    + " мс и остановлено");
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new DebugException("вычисление не удалось: " + cause, cause);
        } catch (InterruptedException stop) {
            Thread.currentThread().interrupt();
            throw new DebugException("ожидание вычисления прервано");
        }
    }

    /** Тело вычисления — выполняется остановленным потоком в цикле парковки. */
    private Value evaluateIn(ThreadState state, ExecutionContext frame, String expression) {
        state.evaluating = true;
        try {
            Source source = Source.ofString(expression);
            Diagnostics diagnostics = new Diagnostics(source);
            Expr parsed = Parser.parseExpression(Lexer.tokenize(source, diagnostics), diagnostics);
            if (diagnostics.hasErrors()) {
                throw new DebugException("выражение не разобралось: " + firstError(diagnostics));
            }
            return interpreter.eval(parsed, frame);
        } finally {
            state.evaluating = false;
            if (state.evalInterrupted) {
                // Флаг прерывания поставили мы сами, отменяя затянувшееся вычисление;
                // унести его в продолжение скрипта значило бы остановить выполнение
                // вместо отмены одного выражения в панели.
                Thread.interrupted();
                state.evalInterrupted = false;
            }
        }
    }

    private static String firstError(Diagnostics diagnostics) {
        for (Diagnostic diagnostic : diagnostics.all()) {
            return diagnostic.message();
        }
        return "неизвестная ошибка разбора";
    }

    private ThreadState require(long threadId) {
        ThreadState state = threads.get(threadId);
        if (state == null) {
            throw new DebugException("поток " + threadId + " этой сессии неизвестен");
        }
        return state;
    }
}
