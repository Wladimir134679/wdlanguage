package ru.wds.wdl.runtime;

import ru.wds.wdl.module.ModuleSource;
import ru.wds.wdl.module.ModuleUnits;
import ru.wds.wdl.module.NativeModules;
import ru.wds.wdl.module.Unit;
import ru.wds.wdl.resolve.Linker;
import ru.wds.wdl.resolve.Resolution;
import ru.wds.wdl.source.Span;
import ru.wds.wdl.value.CallContext;
import ru.wds.wdl.value.ScriptThreads;

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
     * Сеанс, которому принадлежит это место выполнения: модули, формы классов, классы
     * ошибок, замок.
     * <p>
     * Изменяемое, что несёт контекст, собрано здесь, и это не оговорка: «модуль
     * выполняется один раз за запуск», «форма класса одна на запуск», «классы ошибок
     * сняты один раз» — всё это свойства <b>запуска</b>, а не области видимости.
     * Поэтому вложенный контекст обязан делить сеанс с внешним, а не заводить свой.
     * <p>
     * И по той же причине сеанс носят с собой функция и класс: вытащенная наружу
     * функция остаётся частью своего запуска, откуда бы её ни позвали. См. {@link Run}.
     */
    private final Run run;
    /**
     * Отложенные действия текущей области или {@code null}, если их в ней нет.
     * <p>
     * Принадлежит области, а не контексту: вложенный контекст той же области
     * (итерация цикла, тело {@code if}) делит список с внешним, а новый блок
     * с {@code defer} заводит свой — см. {@link Deferred}.
     */
    private final Deferred deferred;

    private ExecutionContext(Environment scope, Output output, Frame frame, Unit unit,
                             Run run, Deferred deferred) {
        this.scope = Objects.requireNonNull(scope, "scope");
        this.output = Objects.requireNonNull(output, "output");
        this.frame = frame;
        this.unit = Objects.requireNonNull(unit, "unit");
        this.run = Objects.requireNonNull(run, "run");
        this.deferred = deferred;
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
     * Сразу после этого классы снимаются в {@linkplain PreludeTypes реестр запуска}.
     * Дальше скрипт волен делать с этими именами что угодно — движок берёт классы
     * из реестра, а не из области.
     */
    public static ExecutionContext of(Environment scope, Output output) {
        Run run = new Run(scope);
        // Вывод оборачивается здесь, на границе запуска, и ровно один раз: приложение
        // передаёт обычную лямбду, ничего не зная про потоки, а запуск обещает, что
        // строка одного потока не разорвётся строкой другого. См. Output.serialized.
        ExecutionContext context = new ExecutionContext(scope, Output.serialized(output), null,
                Unit.none(), run, null);
        Prelude.installTo(context);
        run.exceptions().captureFrom(scope);
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
     * <p>
     * <b>Меняется сам сеанс, а не его копия.</b> Контекст по-прежнему неизменяем —
     * возвращается новый, — но {@link Run} у запуска один от начала до конца, и это
     * важнее: классы прелюдии созданы раньше этого вызова, и достанься им сеанс
     * без модулей, {@code report()} у ошибки выполнялся бы не в том запуске, где она
     * случилась. Отсюда правило: собирать запуск до первой инструкции скрипта, как
     * и обещано выше.
     */
    public ExecutionContext withModules(ModuleUnits units) {
        run.useModules(new Modules(units, run.modules().natives(), scope));
        return this;
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
     * что одно и то же имя в двух местах скрипта означает разное. Меняется при этом
     * сам сеанс — почему именно так, разобрано у {@link #withModules}.
     */
    public ExecutionContext withNativeModules(NativeModules natives) {
        run.useModules(new Modules(run.modules().units(), natives, scope));
        return this;
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
        run.modules().shutdown();
    }

    /**
     * Помечает запуск закрытым: дальше вход в скрипт снаружи даёт остановку выполнения
     * вместо работы по закрытым ресурсам.
     * <p>
     * Зовётся <b>до</b> {@link #shutdownModules()} — в этом порядке и есть смысл:
     * сначала перестать пускать внутрь, потом закрывать то, чем внутри пользуются.
     * Обратный порядок оставлял бы окно, в котором проснувшийся поток скрипта работает
     * по уже закрытому соединению.
     */
    public void closeRun() {
        run.close();
    }

    /** Закрыт ли запуск. */
    public boolean isRunClosed() {
        return run.isClosed();
    }

    /**
     * Прерывает потоки, заведённые скриптом, и ждёт их.
     * <p>
     * Зовётся между {@link #closeRun()} и {@link #shutdownModules()} — в этом порядке
     * и есть смысл: вход уже закрыт, значит проснувшийся поток не влезет внутрь,
     * а модули ещё открыты, значит тот, кто выходит по {@code defer}, успеет закрыть
     * своё.
     *
     * @return имена потоков, не завершившихся за отведённое время; пусто, если все вышли
     */
    public java.util.List<String> stopScriptThreads(long timeoutMillis) {
        return run.threads().stopAndJoin(timeoutMillis);
    }

    @Override
    public ScriptThreads threads() {
        return run.threads();
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
     * <p>
     * Сеанс же берётся у <b>функции</b>, и это то, ради чего {@link Run} и заведён.
     * Раньше он доставался у вызывающего, и функция, позванная приложением из своего
     * потока, начинала выполнение без модулей, без форм классов и без классов ошибок —
     * снаружи запуска, к которому принадлежит. Теперь вызывающий не решает ничего,
     * кроме одного: <b>куда печатать</b>.
     *
     * @param run      сеанс вызываемой функции — её модули, формы и классы ошибок
     * @param function имя вызванной функции — оно и попадёт в трассировку
     * @param callSite место вызова в тексте вызывающего
     */
    static ExecutionContext call(Environment scope, CallContext caller, Run run, Unit unit,
                                 String function, Span callSite) {
        // Кадр и файл для него — вызывающего: 'callSite' указывает в его текст. Если
        // вызывающий не из этого запуска (или вовсе не скрипт), путь начинается здесь,
        // и это честно: до границы движка трассировка скрипта не достаёт.
        ExecutionContext running = caller instanceof ExecutionContext context
                && context.run == run ? context : null;
        Unit callerUnit = running != null ? running.unit : Unit.none();
        Frame parent = running != null ? running.frame : null;
        // Отложенное вызванной функции не наследуется: 'defer' принадлежит своей области,
        // а тело вызова — это другая область в другом файле.
        return new ExecutionContext(scope, caller::write,
                Frame.of(function, callSite, callerUnit, parent), unit, run, null);
    }

    public Environment scope() {
        return scope;
    }

    /** Сеанс, которому принадлежит это место выполнения. */
    Run run() {
        return run;
    }

    /** Модули этого запуска: где их искать и какие уже выполнены. */
    Modules modules() {
        return run.modules();
    }

    /** Формы классов этого запуска: кто с кем связан и что уже собрано. */
    Linker linker() {
        return run.linker();
    }

    /** Классы ошибок этого запуска: по ним движок отвечает на {@code catch (e is ...)}. */
    PreludeTypes exceptions() {
        return run.exceptions();
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
        return new ExecutionContext(scope, output, frame, newUnit, run, deferred);
    }

    /** Тот же контекст, но знающий план объявлений разобранной программы. */
    public ExecutionContext withResolution(Resolution newResolution) {
        return new ExecutionContext(scope, output, frame, unit.withResolution(newResolution),
                run, deferred);
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
        return new ExecutionContext(newScope, output, frame, unit, run, deferred);
    }

    /**
     * Тот же контекст, но с собственным списком отложенных действий: вход в блок,
     * у которого есть {@code defer}.
     */
    ExecutionContext withDeferred(Deferred own) {
        return new ExecutionContext(scope, output, frame, unit, run, own);
    }

    /** Отложенные действия текущей области или {@code null}. */
    Deferred deferred() {
        return deferred;
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
