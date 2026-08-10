package ru.wds.wdl.runtime;

import ru.wds.wdl.module.ModuleSource;
import ru.wds.wdl.module.ModuleUnits;
import ru.wds.wdl.module.Unit;
import ru.wds.wdl.resolve.Linker;
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
    private final Unit unit;
    /**
     * Реестр модулей — изменяемое, что несёт контекст, и это не оговорка:
     * «модуль выполняется один раз за запуск» — свойство запуска, а не области видимости,
     * поэтому вложенный контекст обязан делить реестр с внешним, а не заводить свой.
     */
    private final Modules modules;
    /**
     * Собранные формы классов — по той же причине общие на весь запуск. Объявление
     * класса внутри функции выполняется на каждый вызов, и если бы вложенный контекст
     * заводил свой {@link Linker}, каждый вызов давал бы новую форму, а {@code is}
     * переставал бы узнавать свои же экземпляры.
     */
    private final Linker linker;

    private ExecutionContext(Environment scope, Output output, int callDepth, Unit unit,
                             Modules modules, Linker linker) {
        this.scope = Objects.requireNonNull(scope, "scope");
        this.output = Objects.requireNonNull(output, "output");
        this.callDepth = callDepth;
        this.unit = Objects.requireNonNull(unit, "unit");
        this.modules = Objects.requireNonNull(modules, "modules");
        this.linker = Objects.requireNonNull(linker, "linker");
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
        return of(Builtins.installTo(Scope.root()), output);
    }

    /** Контекст поверх готового окружения — встроенные функции туда кладёт вызывающий. */
    public static ExecutionContext of(Environment scope) {
        return of(scope, Output.discarding());
    }

    public static ExecutionContext of(Environment scope, Output output) {
        return new ExecutionContext(scope, output, 0, Unit.none(),
                new Modules(new ModuleUnits(ModuleSource.none()), scope), new Linker());
    }

    /**
     * Тот же контекст, но с доступом к модулям: {@code import} начинает работать,
     * а разобранные модули будут браться отсюда.
     * <p>
     * Реестр один на запуск — иначе один и тот же файл разобрался бы дважды, и класс,
     * полученный из первого разбора, не был бы тем же классом, что из второго.
     * <p>
     * Текущая область видимости запоминается как корневая для всех модулей — они
     * увидят встроенные функции и библиотеки, положенные до этого вызова, и не увидят
     * локальных имён того, кто их импортирует.
     */
    public ExecutionContext withModules(ModuleUnits units) {
        return new ExecutionContext(scope, output, callDepth, unit, new Modules(units, scope), linker);
    }

    /**
     * То же самое, когда приложение задаёт только источник исходников.
     * <p>
     * По умолчанию источника нет вовсе и {@code import} отвечает ошибкой: встроенный
     * в приложение движок не должен читать чужие файлы, пока его об этом не попросили.
     */
    public ExecutionContext withModules(ModuleSource source) {
        return withModules(new ModuleUnits(source));
    }

    /**
     * Контекст тела вызванной функции: своя область видимости, вывод <b>вызывающего</b>
     * и глубина на единицу больше.
     * <p>
     * Вывод берётся у вызывающего, а не у места объявления функции: куда печатает
     * {@code println}, решает тот, кто запустил скрипт, и функция, переданная в другой
     * движок, обязана печатать туда, где её вызвали.
     * <p>
     * Юнит, наоборот, берётся у самой функции, а не у вызывающего: тело выполняется
     * в том файле, где оно написано, — со своими формами классов и своим исходником
     * для сообщений об ошибках.
     */
    static ExecutionContext call(Environment scope, CallContext caller, Unit unit) {
        // Реестр модулей и собранные формы принадлежат запуску, а не файлу: функция,
        // вызванная из чужого движка, к его модулям отношения не имеет — там начинается
        // свой запуск.
        boolean inRun = caller instanceof ExecutionContext;
        Modules known = inRun
                ? ((ExecutionContext) caller).modules
                : new Modules(new ModuleUnits(ModuleSource.none()), scope);
        Linker shapes = inRun ? ((ExecutionContext) caller).linker : new Linker();
        return new ExecutionContext(scope, caller::write, caller.callDepth() + 1, unit, known, shapes);
    }

    public Environment scope() {
        return scope;
    }

    /** Модули этого запуска: где их искать и какие уже выполнены. */
    Modules modules() {
        return modules;
    }

    /** Формы классов этого запуска: кто с кем связан и что уже собрано. */
    Linker linker() {
        return linker;
    }

    /** Файл, который сейчас выполняется: исходник, формы его классов и его каталог. */
    public Unit unit() {
        return unit;
    }

    /**
     * План объявлений выполняемого файла: что резолвер разрешил объявить до первой
     * инструкции.
     * <p>
     * Здесь, а не в интерпретаторе, потому что интерпретатор безсостоятельный
     * и разделяется между запусками, а план принадлежит конкретному файлу.
     */
    public Resolution resolution() {
        return unit.resolution();
    }

    /** Тот же контекст, но выполняющий другой файл. */
    public ExecutionContext withUnit(Unit newUnit) {
        return new ExecutionContext(scope, output, callDepth, newUnit, modules, linker);
    }

    /** Тот же контекст, но знающий план объявлений разобранной программы. */
    public ExecutionContext withResolution(Resolution newResolution) {
        return new ExecutionContext(scope, output, callDepth, unit.withResolution(newResolution),
                modules, linker);
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
        return new ExecutionContext(newScope, output, callDepth, unit, modules, linker);
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
