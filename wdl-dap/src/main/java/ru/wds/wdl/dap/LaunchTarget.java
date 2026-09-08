package ru.wds.wdl.dap;

import ru.wds.wdl.api.Stdlib;
import ru.wds.wdl.api.WdlEngine;
import ru.wds.wdl.api.WdlException;
import ru.wds.wdl.api.WdlInstance;
import ru.wds.wdl.debug.DebugListener;
import ru.wds.wdl.debug.DebugSession;
import ru.wds.wdl.runtime.Output;

import java.util.function.IntConsumer;

/**
 * Запуск, заведённый по просьбе {@code launch}: движок, скрипт и поток, который его
 * выполняет.
 *
 * <h2>Почему отдельный поток</h2>
 * Выполнять скрипт в потоке транспорта нельзя ни минуты: первая же точка останова
 * припарковала бы поток, читающий сообщения, и клиент, нажавший «Продолжить», не был
 * бы услышан вовсе. Поэтому скрипт идёт своим потоком, а транспорт остаётся свободным —
 * это и есть то устройство, ради которого отладчик умеет останавливать <i>не себя</i>.
 *
 * <h2>Сессия существует до первой инструкции</h2>
 * Движок собирается с {@code debug(listener)} — это режим launch из
 * {@code docs/debugging.md}: сессия заводится вместе с запуском, ещё до первого шага,
 * поэтому точку можно поставить на самой первой строке файла. Спросить
 * {@code debugger()} потом было бы поздно: файл к этому времени уже начал выполняться.
 */
final class LaunchTarget implements DebugTarget {

    /** Сколько ждать поток скрипта после просьбы прекратить выполнение. */
    private static final long TERMINATE_TIMEOUT_MILLIS = 5000;

    private final LaunchOptions options;
    private final WdlInstance instance;
    private final DebugSession session;
    private final Output errors;
    private final IntConsumer onExit;

    private volatile Thread runner;
    private volatile boolean terminating;

    /**
     * Собирает движок и разбирает скрипт — но не выполняет его.
     *
     * @param listener кому сессия расскажет об остановках
     * @param output   куда идёт печать скрипта
     * @param errors   куда идёт сообщение о падении
     * @param onExit   что позвать, когда скрипт закончится: код возврата
     * @throws WdlException если скрипт не разобрался — со всеми ошибками сразу
     */
    LaunchTarget(LaunchOptions options, DebugListener listener, Output output, Output errors,
                 IntConsumer onExit) {
        this.options = options;
        this.errors = errors;
        this.onExit = onExit;
        WdlEngine.Builder builder = WdlEngine.builder()
                .debug(listener)
                .output(output)
                .stdlib(options.stdlib() == null ? Stdlib.STANDARD : options.stdlib())
                // Аргументы скрипта — то же имя 'args', что и у консольного запуска.
                .define("args", options.arguments());
        if (options.projectRoot() != null) {
            builder.projectRoot(options.projectRoot());
        }
        // Пределы ставятся поверх набора: SAFE приносит свои, и явная просьба клиента
        // обязана быть сильнее умолчания набора, а не наоборот.
        if (options.maxSteps() > 0) {
            builder.maxSteps(options.maxSteps());
        }
        if (options.timeout() != null) {
            builder.timeout(options.timeout());
        }
        if (options.maxThreads() > 0) {
            builder.maxThreads(options.maxThreads());
        }
        this.instance = builder.build().compile(options.program()).instance();
        this.session = instance.debugger();
        session.policy(options.policy());
        session.stopOnError(options.stopOnError());
        if (options.evalTimeout() > 0) {
            session.evalTimeout(options.evalTimeout());
        }
    }

    @Override
    public DebugSession session() {
        return session;
    }

    /** Имя файла так, как его видит сессия: по нему сверяются точки останова. */
    String file() {
        return options.program().toString();
    }

    @Override
    public void start() {
        if (options.stopOnEntry()) {
            // Останов до первой инструкции — это обычная пауза, поставленная заранее:
            // первый же шаг скрипта её увидит. Отдельного «останова на входе»
            // в сессии для этого не нужно.
            session.pause();
        }
        Thread thread = new Thread(this::run, "wdl-script");
        runner = thread;
        thread.start();
    }

    private void run() {
        int code = 0;
        try {
            instance.execute();
        } catch (WdlException failed) {
            // Скрипт упал — это его законный конец, а не поломка адаптера. Текст уже
            // готов к печати: строка исходника с подчёркиванием и путь по вызовам.
            code = 1;
            report(failed.getMessage());
        } catch (RuntimeException | Error broken) {
            code = 1;
            report("отладка прервана: " + broken);
        } finally {
            try {
                instance.close();
            } catch (RuntimeException ignored) {
                // Закрытие уже ничего не решает: код возврата назван выше.
            }
            onExit.accept(code);
        }
    }

    /** Сообщение о падении — рядом с выводом скрипта, но помеченное как ошибка. */
    private void report(String text) {
        errors.write(text + System.lineSeparator());
    }

    /**
     * Прекращает выполнение: отпускает стоящие потоки и прерывает свой.
     * <p>
     * Порядок именно такой. Поток, стоящий на точке останова, шагов больше не делает
     * и прерывания не заметит — сначала его надо отпустить. Дальше прерывание доходит
     * до скрипта той же дорогой, что и вышедшее время: через {@code Run.checkpoint},
     * на ближайшем шаге. Потока, ушедшего в блокирующий вызов Java, это тоже касается —
     * {@code interrupt} его оттуда выведет.
     */
    @Override
    public void terminate() {
        if (terminating) {
            return;
        }
        terminating = true;
        session.detach();
        Thread thread = runner;
        if (thread == null) {
            // Скрипт ещё не пускали: закрывать запуск больше некому.
            instance.close();
            return;
        }
        thread.interrupt();
        try {
            thread.join(TERMINATE_TIMEOUT_MILLIS);
        } catch (InterruptedException stop) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        terminate();
    }
}
