package ru.wds.wdl.runtime;

import ru.wds.wdl.module.ModuleSource;
import ru.wds.wdl.module.ModuleUnits;
import ru.wds.wdl.module.NativeModules;
import ru.wds.wdl.module.Unit;
import ru.wds.wdl.resolve.Linker;
import ru.wds.wdl.resolve.Resolution;
import ru.wds.wdl.source.Span;
import ru.wds.wdl.value.CallContext;

import java.util.Objects;

/**
 * Состояние одного выполнения: всё, что интерпретатор несёт с собой по дереву.
 * <p>
 * Сейчас это область видимости, вывод и цепочка кадров вызова для трассировки ошибок.
 * Дальше сюда придут счётчик шагов и таймаут для лимитов. Если бы интерпретатор принимал
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
    /**
     * Кадр текущего вызова или {@code null}, если выполняется верхний уровень файла.
     * <p>
     * Цепочка кадров заменила собой счётчик глубины: она нужна трассировке, а глубина
     * из неё выводится даром — {@link Frame#depth()} считается один раз при создании.
     */
    private final Frame frame;
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
    /**
     * Классы ошибок этого запуска. Общий на весь запуск по той же причине, что
     * и {@link #linker}: снят он один раз, с корневой области, до первой строки скрипта.
     */
    private final Exceptions exceptions;

    private ExecutionContext(Environment scope, Output output, Frame frame, Unit unit,
                             Modules modules, Linker linker, Exceptions exceptions) {
        this.scope = Objects.requireNonNull(scope, "scope");
        this.output = Objects.requireNonNull(output, "output");
        this.frame = frame;
        this.unit = Objects.requireNonNull(unit, "unit");
        this.modules = Objects.requireNonNull(modules, "modules");
        this.linker = Objects.requireNonNull(linker, "linker");
        this.exceptions = Objects.requireNonNull(exceptions, "exceptions");
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

    /**
     * Здесь же выполняется {@linkplain Prelude прелюдия}: иерархия классов ошибок
     * появляется в области видимости до первой строки скрипта, как и встроенные функции.
     * <p>
     * Сразу после этого классы снимаются в {@linkplain Exceptions реестр запуска}.
     * Дальше скрипт волен делать с этими именами что угодно — движок берёт классы
     * из реестра, а не из области.
     */
    public static ExecutionContext of(Environment scope, Output output) {
        Exceptions exceptions = new Exceptions();
        ExecutionContext context = new ExecutionContext(scope, output, null, Unit.none(),
                new Modules(new ModuleUnits(ModuleSource.none()), NativeModules.none(), scope),
                new Linker(), exceptions);
        Prelude.installTo(context);
        exceptions.captureFrom(scope);
        return context;
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
        return new ExecutionContext(scope, output, frame, unit,
                new Modules(units, modules.natives(), scope), linker, exceptions);
    }

    /**
     * Тот же контекст, но со встроенными модулями: {@code import sys.json} начинает
     * находить библиотеку на Java.
     * <p>
     * По умолчанию их нет вовсе — как нет и источника файлов. Что дать скрипту:
     * файлы, сеть, ничего, — решает приложение, и решает набором, а не флагами:
     * не положил {@code sys/net/http} в реестр — модуля не существует.
     * <p>
     * Ставится до запуска, вместе с {@link #withModules}: реестр выполненного
     * принадлежит запуску, и менять его состав посреди работы значило бы,
     * что одно и то же имя в двух местах скрипта означает разное.
     */
    public ExecutionContext withNativeModules(NativeModules natives) {
        return new ExecutionContext(scope, output, frame, unit,
                new Modules(modules.units(), natives, scope), linker, exceptions);
    }

    /**
     * Закрывает библиотеки встроенных модулей: соединения, клиенты, всё живое,
     * что они завели.
     * <p>
     * Зовёт хозяин запуска, когда запуск кончился, — консольный интерпретатор
     * в {@code finally}, встраивающее приложение из своего {@code close()}. Само
     * ядро момента «конец запуска» не знает: контекст неизменяем и копируется,
     * а вот реестр модулей у копий общий — потому закрывать и можно отсюда.
     */
    public void shutdownModules() {
        modules.shutdown();
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
     * <p>
     * Кадр, наоборот, помнит файл <b>вызывающего</b>: {@code callSite} — место в его
     * тексте, и осмысленно оно только в его исходнике.
     *
     * @param function имя вызванной функции — оно и попадёт в трассировку
     * @param callSite место вызова в тексте вызывающего
     */
    static ExecutionContext call(Environment scope, CallContext caller, Unit unit,
                                 String function, Span callSite) {
        // Реестр модулей, собранные формы и классы ошибок принадлежат запуску, а не файлу:
        // функция, вызванная из чужого движка, к его модулям отношения не имеет — там
        // начинается свой запуск.
        ExecutionContext running = caller instanceof ExecutionContext context ? context : null;
        Modules known = running != null
                ? running.modules
                : new Modules(new ModuleUnits(ModuleSource.none()), NativeModules.none(), scope);
        Linker shapes = running != null ? running.linker : new Linker();
        Exceptions errors = running != null ? running.exceptions : new Exceptions();
        Unit callerUnit = running != null ? running.unit : Unit.none();
        Frame parent = running != null ? running.frame : null;
        return new ExecutionContext(scope, caller::write,
                Frame.of(function, callSite, callerUnit, parent), unit, known, shapes, errors);
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

    /** Классы ошибок этого запуска: по ним движок отвечает на {@code catch (e is ...)}. */
    Exceptions exceptions() {
        return exceptions;
    }

    /** Кадр текущего вызова или {@code null} на верхнем уровне файла. */
    Frame frame() {
        return frame;
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
        return new ExecutionContext(scope, output, frame, newUnit, modules, linker, exceptions);
    }

    /** Тот же контекст, но знающий план объявлений разобранной программы. */
    public ExecutionContext withResolution(Resolution newResolution) {
        return new ExecutionContext(scope, output, frame, unit.withResolution(newResolution),
                modules, linker, exceptions);
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
        return frame == null ? 0 : frame.depth();
    }

    /** Тот же контекст, но с другим окружением: вход в блок, функцию, итерацию. */
    public ExecutionContext withScope(Environment newScope) {
        return new ExecutionContext(newScope, output, frame, unit, modules, linker, exceptions);
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
