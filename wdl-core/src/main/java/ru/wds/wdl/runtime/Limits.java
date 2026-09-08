package ru.wds.wdl.runtime;

import java.time.Duration;
import java.util.Objects;

/**
 * Пределы одного запуска: сколько скрипту можно шагов, времени, потоков и вложенности.
 * <p>
 * Это то, чего движку не хватало, чтобы выполнять <b>чужой</b> скрипт: без лимитов
 * {@code while (true) {}} висит навсегда, а {@code for (;;) th.spawn(...)} кладёт
 * не скрипт, а приложение, в которое движок встроен. Поэтому же {@code sys.thread}
 * до появления этого класса не входил в набор {@link ru.wds.wdl.module.Library
 * библиотек} безопасного профиля.
 *
 * <h2>Почему это свойство запуска</h2>
 * Лимиты живут в {@link Run}, рядом с модулями, формами классов и метриками, и по той
 * же причине: «сколько шагов осталось» — вопрос про <b>сеанс целиком</b>, а не про
 * место в дереве. Контекст выполнения ({@link ExecutionContext}) остаётся неизменяемым
 * и копируется на каждую область видимости; счётчик, лежащий в нём, копировался бы
 * вместе с ним и обнулялся бы на входе в любой блок.
 *
 * <h2>Ноль значит «без предела»</h2>
 * У всех полей один и тот же способ сказать «не ограничивать» — ноль (у времени
 * {@link Duration#ZERO}). Второй способ сказать то же самое ({@code -1},
 * {@code OptionalLong}, {@code null}) пришлось бы помнить в каждой проверке, а пользы
 * он не даёт: отрицательный предел бессмыслен, и конструктор его не примет.
 * <p>
 * Умолчание — {@link #none()}: движок не платит за то, о чём его не просили, ровно
 * как не печатает никуда без {@link Output} и не считает время без
 * {@link ru.wds.wdl.metrics.Metrics}. Пределы вложенности при этом стоят
 * не нулём, а своими прежними значениями: они не «лимит на чужой скрипт», а защита
 * от {@code StackOverflowError} в чужом приложении, и нужна она всегда.
 *
 * <h2>Что лимитами не закрывается</h2>
 * Шаги и время проверяются в точках выполнения скрипта, а одна долгая операция
 * на Java — сортировка миллиона элементов, регулярное выражение с возвратом — ни одной
 * такой точки не проходит. Блокирующее ожидание снимает сторож
 * ({@code Run}, прерывание), а непрерываемый счёт не останавливается ничем:
 * {@code Thread.stop} из JDK убран, и заменять его нечем. См. {@code docs/limits.md}.
 *
 * @param maxSteps     сколько шагов скрипта можно сделать за запуск; {@code 0} — без
 *                     предела. Шаг — итерация цикла и вход в тело функции или
 *                     конструктора, а не узел дерева: зациклиться, не пройдя ни разу
 *                     ни через то, ни через другое, нельзя, а платить за счётчик
 *                     на каждом узле пришлось бы в самом горячем месте интерпретатора
 * @param timeout      сколько всего может работать запуск; {@link Duration#ZERO} —
 *                     без предела. Отсчёт идёт от <b>первого входа в скрипт</b>,
 *                     а не от сборки движка: подготовка запуска не должна съедать
 *                     бюджет скрипта
 * @param maxThreads   сколько потоков скрипту разрешено держать одновременно;
 *                     {@code 0} — без предела. Считаются и потоки {@code th.spawn},
 *                     и потоки пулов {@code th.pool}
 * @param maxCallDepth предел вложенности вызовов: бесконечная рекурсия обязана давать
 *                     ошибку с местом в исходнике, а не {@code StackOverflowError}
 * @param maxEntries   предел цепочки «скрипт → приложение → скрипт» на одном потоке:
 *                     кадры скрипта на каждом внешнем входе начинаются заново,
 *                     и {@code maxCallDepth} такую цепочку не видит
 */
public record Limits(long maxSteps, Duration timeout, int maxThreads,
                     int maxCallDepth, int maxEntries) {

    /**
     * Предел вложенности вызовов по умолчанию.
     * <p>
     * Число выбрано с запасом вниз: один вызов wdl стоит около десятка кадров Java,
     * но сколько именно — зависит от формы тела, а сколько кадров влезет — от размера
     * стека потока, который движку не подчиняется. Поэтому счётчик — предсказуемая
     * основная защита, а на случай слишком короткого стека есть вторая линия
     * в {@link Interpreter} ({@code StackOverflowError} ловится на границе выполнения).
     */
    public static final int DEFAULT_CALL_DEPTH = 256;

    /**
     * Предел внешних входов по умолчанию.
     * <p>
     * Число небольшое намеренно: рекурсия через приложение — это почти всегда
     * не замысел, а зациклившийся обработчик, и упереться в предел лучше на десятом
     * обороте с внятным сообщением, чем на трёхсотом с {@code StackOverflowError}.
     */
    public static final int DEFAULT_ENTRIES = 32;

    /** Шаги безопасного профиля: десятки секунд работы честного скрипта. */
    public static final long SAFE_STEPS = 100_000_000L;

    /** Время безопасного профиля. */
    public static final Duration SAFE_TIMEOUT = Duration.ofSeconds(30);

    /** Потоки безопасного профиля. */
    public static final int SAFE_THREADS = 8;

    /**
     * Через сколько шагов поток сверяется с общим счётчиком и с часами.
     * <p>
     * Счётчик один на запуск (иначе «восемь потоков по лимиту каждый» — это не лимит),
     * но трогать общий {@code LongAdder} и спрашивать {@code System.nanoTime()}
     * на каждой итерации цикла значило бы платить в самой горячей точке. Поэтому
     * у потока свой локальный счётчик, а вливается он раз в батч: погрешность
     * в двести пятьдесят шагов тому, кто ставит предел в миллионы, безразлична.
     * <p>
     * Маленькому пределу батч не мешает: {@link #stepBatch()} подрезает его под
     * {@link #maxSteps()}, иначе предел в сто шагов не сработал бы никогда.
     */
    static final int STEP_BATCH = 256;

    private static final Limits NONE =
            new Limits(0, Duration.ZERO, 0, DEFAULT_CALL_DEPTH, DEFAULT_ENTRIES);

    private static final Limits SAFE =
            new Limits(SAFE_STEPS, SAFE_TIMEOUT, SAFE_THREADS, DEFAULT_CALL_DEPTH, DEFAULT_ENTRIES);

    public Limits {
        Objects.requireNonNull(timeout, "timeout");
        if (maxSteps < 0) {
            throw new IllegalArgumentException("предел шагов не может быть отрицательным: " + maxSteps);
        }
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("таймаут не может быть отрицательным: " + timeout);
        }
        if (maxThreads < 0) {
            throw new IllegalArgumentException("предел потоков не может быть отрицательным: " + maxThreads);
        }
        if (maxCallDepth <= 0) {
            throw new IllegalArgumentException("предел вложенности вызовов должен быть "
                    + "положительным: " + maxCallDepth);
        }
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("предел внешних входов должен быть "
                    + "положительным: " + maxEntries);
        }
    }

    /**
     * Пределов нет: ни шагов, ни времени, ни квоты потоков.
     * <p>
     * Состояние движка по умолчанию. Вложенность вызовов и цепочка внешних входов
     * при этом ограничены как всегда — они защищают не хозяина от скрипта,
     * а поток от исчерпания стека.
     */
    public static Limits none() {
        return NONE;
    }

    /**
     * Пределы для скрипта, пришедшего от пользователя: сто миллионов шагов,
     * тридцать секунд, восемь потоков.
     * <p>
     * Числа — не истина, а ориентир «честный скрипт успевает, зациклившийся
     * останавливается»; приложение вправе задать свои. Важно другое: набор
     * {@code Stdlib.SAFE} держит своё обещание только вместе с какими-нибудь
     * лимитами, и эти — те, что ставятся сами.
     */
    public static Limits safeDefaults() {
        return SAFE;
    }

    /** Построитель: задаётся только то, что нужно, остальное — как в {@link #none()}. */
    public static Builder builder() {
        return new Builder(NONE);
    }

    /** Построитель поверх этих пределов: меняется одно поле, остальные остаются. */
    public Builder toBuilder() {
        return new Builder(this);
    }

    /**
     * Нужны ли счётчик шагов и часы.
     * <p>
     * Спрашивается на каждом шаге, и от ответа зависит, платит ли выключенный движок
     * хоть что-нибудь: при {@code false} проверка кончается здесь, до всякого
     * {@code nanoTime} и до общего счётчика.
     */
    public boolean counting() {
        return maxSteps > 0 || !timeout.isZero();
    }

    /** Ограничено ли число потоков. */
    public boolean limitsThreads() {
        return maxThreads > 0;
    }

    /** Таймаут в наносекундах; {@code 0}, если его нет. */
    long timeoutNanos() {
        return timeout.isZero() ? 0 : timeout.toNanos();
    }

    /**
     * Через сколько шагов сверяться с общим счётчиком: батч, подрезанный под предел.
     * <p>
     * Без подрезки предел меньше батча не сработал бы вовсе — а именно такие пределы
     * ставят тесты и приложения, дающие скрипту «чуть-чуть посчитать».
     */
    int stepBatch() {
        return maxSteps > 0 ? (int) Math.min(STEP_BATCH, maxSteps) : STEP_BATCH;
    }

    /** Построитель пределов. */
    public static final class Builder {

        private long maxSteps;
        private Duration timeout;
        private int maxThreads;
        private int maxCallDepth;
        private int maxEntries;

        private Builder(Limits from) {
            this.maxSteps = from.maxSteps;
            this.timeout = from.timeout;
            this.maxThreads = from.maxThreads;
            this.maxCallDepth = from.maxCallDepth;
            this.maxEntries = from.maxEntries;
        }

        /** Сколько шагов скрипта можно сделать за запуск; {@code 0} — без предела. */
        public Builder maxSteps(long steps) {
            this.maxSteps = steps;
            return this;
        }

        /** Сколько может работать запуск; {@link Duration#ZERO} — без предела. */
        public Builder timeout(Duration limit) {
            this.timeout = Objects.requireNonNull(limit, "timeout");
            return this;
        }

        /** Сколько потоков скрипту разрешено держать; {@code 0} — без предела. */
        public Builder maxThreads(int threads) {
            this.maxThreads = threads;
            return this;
        }

        /** Предел вложенности вызовов. */
        public Builder maxCallDepth(int depth) {
            this.maxCallDepth = depth;
            return this;
        }

        /** Предел цепочки «скрипт → приложение → скрипт» на одном потоке. */
        public Builder maxEntries(int entries) {
            this.maxEntries = entries;
            return this;
        }

        public Limits build() {
            return new Limits(maxSteps, timeout, maxThreads, maxCallDepth, maxEntries);
        }
    }
}
