package ru.wds.wdl.runtime;

import ru.wds.wdl.resolve.Resolution;
import ru.wds.wdl.value.CallContext;

import java.util.Objects;

/**
 * Состояние одного выполнения: всё, что интерпретатор несёт с собой по дереву.
 * <p>
 * Сейчас это область видимости и вывод. Дальше сюда придут стек вызовов для трассировки
 * ошибок, счётчик шагов и таймаут для лимитов. Если бы интерпретатор принимал
 * {@link Environment} напрямую, каждое такое добавление означало бы правку подписи
 * всех методов посетителя и всех его реализаций.
 * <p>
 * Контекст неизменяем: вложенная область даёт новый контекст ({@link #withScope}),
 * а не подменяет поле. Так вычисление не может случайно оставить после себя чужое
 * окружение — типичная ошибка интерпретаторов, где области видимости кладутся
 * и снимаются вручную.
 * <p>
 * Реализует {@link CallContext}: это тот самый интерфейс, через который встроенные
 * функции добираются до вывода, не зная ничего об устройстве интерпретатора.
 */
public final class ExecutionContext implements CallContext {

    /**
     * Предел вложенности вызовов. Бесконечная рекурсия — обычная ошибка в скрипте,
     * и отвечать на неё движок обязан ошибкой с местом в исходнике, а не
     * {@code StackOverflowError}, который в чужом приложении может свалить поток
     * в любом месте.
     * <p>
     * Число выбрано с запасом вниз, и запас нужен вот почему: один вызов wdl стоит
     * около десятка кадров Java, но сколько именно — зависит от формы тела, а сколько
     * кадров влезет — от размера стека потока, который движку не подчиняется (в потоке
     * сборщика тестов, например, вдвое меньше, чем в главном потоке процесса). Поэтому
     * счётчик — предсказуемая основная защита, а на случай слишком короткого стека есть
     * вторая линия в {@code Interpreter}. Настраиваемым предел станет вместе
     * с остальными лимитами — шагами и таймаутом.
     */
    public static final int MAX_CALL_DEPTH = 256;

    private final Environment scope;
    private final Output output;
    private final int callDepth;
    private final Resolution resolution;

    private ExecutionContext(Environment scope, Output output, int callDepth, Resolution resolution) {
        this.scope = Objects.requireNonNull(scope, "scope");
        this.output = Objects.requireNonNull(output, "output");
        this.callDepth = callDepth;
        this.resolution = Objects.requireNonNull(resolution, "resolution");
    }

    /**
     * Контекст с чистым корневым окружением, встроенными функциями и выводом в никуда.
     * <p>
     * Вывод по умолчанию именно пустой: движок, встроенный в чужое приложение, не имеет
     * права печатать в его консоль без спроса. Консольный запуск передаёт
     * {@link Output#standard()} явно.
     */
    public static ExecutionContext fresh() {
        return fresh(Output.discarding());
    }

    /** Контекст с чистым окружением, встроенными функциями и заданным выводом. */
    public static ExecutionContext fresh(Output output) {
        return new ExecutionContext(Builtins.installTo(Scope.root()), output, 0, Resolution.none());
    }

    /** Контекст поверх готового окружения — встроенные функции туда кладёт вызывающий. */
    public static ExecutionContext of(Environment scope) {
        return new ExecutionContext(scope, Output.discarding(), 0, Resolution.none());
    }

    public static ExecutionContext of(Environment scope, Output output) {
        return new ExecutionContext(scope, output, 0, Resolution.none());
    }

    /**
     * Контекст тела вызванной функции: своя область видимости, вывод <b>вызывающего</b>
     * и глубина на единицу больше.
     * <p>
     * Вывод берётся у вызывающего, а не у места объявления функции: куда печатает
     * {@code println}, решает тот, кто запустил скрипт, и функция, переданная в другой
     * движок, обязана печатать туда, где её вызвали.
     */
    static ExecutionContext call(Environment scope, CallContext caller) {
        // Формы классов достаются от вызывающего, если он их знает: функция, вызванная
        // из скрипта с классами, обязана уметь объявить класс в своём теле.
        Resolution known = caller instanceof ExecutionContext context ? context.resolution : Resolution.none();
        return new ExecutionContext(scope, caller::write, caller.callDepth() + 1, known);
    }

    public Environment scope() {
        return scope;
    }

    /**
     * Формы классов и типажей, собранные резолвером до выполнения.
     * <p>
     * Здесь, а не в интерпретаторе, потому что интерпретатор безсостоятельный
     * и разделяется между запусками, а формы принадлежат конкретной программе.
     */
    public Resolution resolution() {
        return resolution;
    }

    /** Тот же контекст, но знающий формы разобранной программы. */
    public ExecutionContext withResolution(Resolution newResolution) {
        return new ExecutionContext(scope, output, callDepth, newResolution);
    }

    public Output output() {
        return output;
    }

    @Override
    public void write(String text) {
        output.write(text);
    }

    @Override
    public int callDepth() {
        return callDepth;
    }

    /** Тот же контекст, но с другим окружением: вход в блок, функцию, итерацию. */
    public ExecutionContext withScope(Environment newScope) {
        return new ExecutionContext(newScope, output, callDepth, resolution);
    }

    /** Контекст вложенной области видимости. */
    public ExecutionContext nested() {
        return withScope(scope.child());
    }

    @Override
    public String toString() {
        return "ExecutionContext[" + scope + "]";
    }
}
