package ru.wds.wdl.debug;

import ru.wds.wdl.ast.stmt.Stmt;
import ru.wds.wdl.runtime.ExecutionContext;

import java.util.Arrays;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.FutureTask;

/**
 * Что сессия помнит про один поток скрипта: где он, чего ждёт и стоит ли.
 *
 * <h2>Стек кадров — массив по глубине, а не список с {@code push} и {@code pop}</h2>
 * Глубина вызова уже посчитана и лежит готовой: {@code Frame.depth()} считается
 * один раз при создании кадра. Значит, стек можно вести <b>без хуков на вход
 * и выход из функции</b> — достаточно записывать текущее место в ячейку своей глубины
 * на каждом шаге. Возврат из функции ничего не стирает: следующий шаг вызывающего
 * перезапишет свою ячейку и сдвинет вершину, а всё, что глубже вершины, никого
 * не интересует.
 * <p>
 * Это стоит одной записи в массив на шаг — и избавляет от четырёх точек съёма
 * (тело функции, создание экземпляра, чужой код, верхний уровень файла), которые
 * пришлось бы завести и держать в согласии с профилем.
 *
 * <h2>Потоки</h2>
 * Пишет сюда только сам поток скрипта, читает — поток отладчика, и только пока
 * поток стоит. Согласованность держит {@link #top}: он {@code volatile} и пишется
 * <b>после</b> ячеек, поэтому увидевший новую вершину увидит и то, что в ней лежит.
 */
final class ThreadState {

    private static final int INITIAL_DEPTH = 16;

    private final Thread thread;
    private final long id;

    private volatile Stmt[] statements = new Stmt[INITIAL_DEPTH];
    private volatile ExecutionContext[] contexts = new ExecutionContext[INITIAL_DEPTH];

    /** Глубина текущего кадра; {@code -1}, пока поток не сделал ни одного шага. */
    private volatile int top = -1;

    /** Чего ждёт поток после возобновления. Снимается той остановкой, которую вызвал. */
    volatile StepMode mode = StepMode.RUN;

    /** Глубина, на которой был запрошен шаг. */
    volatile int stepDepth;

    /** Просили встать именно этот поток. */
    volatile boolean pauseRequested;

    /** Стоит ли поток сейчас. */
    volatile boolean suspended;

    /** Почему стоит; {@code null}, пока работает. */
    volatile StopReason reason;

    /**
     * Выполняется ли сейчас вычисление по просьбе отладчика.
     * <p>
     * Пока да — точки останова и шаги не срабатывают: выражение из панели «Watches»
     * не должно останавливать поток, который и так стоит.
     */
    volatile boolean evaluating;

    /** Прерывали ли этот поток ради отмены затянувшегося вычисления. */
    volatile boolean evalInterrupted;

    /** Что поток должен выполнить, прежде чем идти дальше: вычисления от отладчика. */
    final Queue<FutureTask<?>> tasks = new ConcurrentLinkedQueue<>();

    ThreadState(Thread thread) {
        this.thread = thread;
        this.id = thread.threadId();
    }

    Thread thread() {
        return thread;
    }

    long id() {
        return id;
    }

    /** Запоминает место: инструкция и контекст на своей глубине. Зовёт свой поток. */
    void record(Stmt stmt, ExecutionContext context) {
        int depth = context.callDepth();
        Stmt[] known = statements;
        if (depth >= known.length) {
            grow(depth);
            known = statements;
        }
        known[depth] = stmt;
        contexts[depth] = context;
        // Вершина — последней и volatile: она и публикует всё записанное выше.
        top = depth;
    }

    private void grow(int depth) {
        int size = Math.max(depth + 1, statements.length * 2);
        statements = Arrays.copyOf(statements, size);
        contexts = Arrays.copyOf(contexts, size);
    }

    /** Глубина текущего кадра; {@code -1}, если поток ещё не делал шагов. */
    int top() {
        return top;
    }

    Stmt statementAt(int depth) {
        Stmt[] known = statements;
        return depth >= 0 && depth < known.length ? known[depth] : null;
    }

    ExecutionContext contextAt(int depth) {
        ExecutionContext[] known = contexts;
        return depth >= 0 && depth < known.length ? known[depth] : null;
    }

    ThreadInfo info() {
        return new ThreadInfo(id, thread.getName(), suspended, reason);
    }
}
