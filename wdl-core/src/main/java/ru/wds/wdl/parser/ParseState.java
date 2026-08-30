package ru.wds.wdl.parser;

import ru.wds.wdl.diagnostic.Diagnostics;
import ru.wds.wdl.lexer.Token;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Где мы находимся в разбираемом тексте: внутри цикла, функции, {@code finally},
 * тела класса.
 * <p>
 * Всё это нужно ровно для одного — поймать при разборе то, что иначе выстрелило бы
 * при выполнении той единственной ветки, куда до релиза никто не заглянул:
 * {@code break} вне цикла, {@code return} внутри {@code finally}, {@code this}
 * в фабрике. Проверки живут рядом со счётчиками, а не там, где разбирается
 * конструкция: правило «откуда нельзя выйти наружу» одно на {@code return},
 * {@code break} и {@code continue}, и записано оно должно быть один раз.
 * <p>
 * Границы областей задаются методами {@code in...}: они сами сохраняют и
 * восстанавливают глубины. Раньше эта обвязка была выписана вручную в каждом теле,
 * и {@code функция} с {@code методом} расходились в ней по недосмотру.
 */
final class ParseState {

    private final Diagnostics diagnostics;

    /**
     * Сколько циклов вокруг разбираемой сейчас инструкции. Тело функции начинается
     * с нуля — из цикла нельзя выйти через границу функции.
     */
    private int loopDepth;
    /** Сколько функций вокруг: то же самое для {@code return} вне функции. */
    private int functionDepth;
    /**
     * Сколько веток {@code case} у {@code match} в позиции выражения вокруг
     * разбираемой сейчас инструкции. {@code yield} допустим, только пока их больше нуля.
     * <p>
     * Тело функции и тело ветки у {@code match}-инструкции начинаются с нуля: из ветки
     * нельзя отдать значение через границу функции, а у ветки, которая ничего не даёт,
     * отдавать нечего. Второе важнее, чем кажется: без обнуления {@code yield} внутри
     * вложенного {@code match}-инструкции молча уходил бы во внешнюю ветку-значение,
     * хотя читается как «значение этого case».
     */
    private int valueBranchDepth;
    /**
     * Глубины {@code finally} и {@code defer} на входе в ближайшую ветку-значение.
     * <p>
     * Нужны, чтобы запрет «не выходить наружу из {@code finally}» говорил про
     * {@code yield} правду. {@code return} из ветки действительно уходит за пределы
     * объемлющего {@code finally} и гасит летящую ошибку, а {@code yield} дальше своей
     * ветки не идёт никогда — и запрещать его в {@code match}, целиком написанном
     * внутри {@code finally}, значило бы соврать в тексте ошибки. Запрещён он только
     * тогда, когда {@code finally} или {@code defer} открыты <b>внутри</b> самой ветки.
     */
    private int yieldFinallyBase;
    private int yieldDeferBase;
    /**
     * Написан ли в ветке хоть какой-то выход — разбор ветки только что закончился.
     * <p>
     * Считается не только {@code yield}: ветка, которая делает {@code return},
     * {@code throw}, {@code break} или {@code continue}, значения не отдаёт законно —
     * управление уходит мимо {@code match} целиком. Требовать от неё {@code yield}
     * значило бы требовать недостижимую строку.
     */
    private boolean lastBranchExited;
    /** То же для ветки, которая разбирается сейчас. */
    private boolean branchExits;
    /**
     * Сколько блоков {@code finally} вокруг разбираемой сейчас инструкции.
     * <p>
     * {@code return}, {@code break} и {@code continue} внутри {@code finally}
     * запрещены — они молча погасили бы ошибку, которая летит наружу. Тело функции,
     * объявленной внутри {@code finally}, начинается с нуля: её {@code return}
     * возвращает из неё самой и ничего не гасит.
     */
    private int finallyDepth;
    /**
     * Сколько блоков {@code defer} вокруг: тот же запрет на выход наружу и по той же
     * причине — отложенное действие выполняется на пути наружу, и уйти из него
     * значило бы погасить то, ради чего мы идём.
     */
    private int deferDepth;
    /**
     * Есть ли отложенное действие в блоке, который разбирается прямо сейчас.
     * <p>
     * Флаг ставится при разборе {@code defer} и снимается блоком: так
     * {@link ru.wds.wdl.ast.stmt.BlockStmt} узнаёт о своих отложенных действиях даром,
     * а выполнению не приходится заводить список на каждый блок — их в скрипте тысячи,
     * а блоков с {@code defer} единицы.
     */
    private boolean blockHasDefer;
    /**
     * Имя класса, тело которого разбирается сейчас, или {@code null}.
     * <p>
     * Нужно, чтобы отличить конструктор {@code def Point()} от метода и проверить,
     * что фабрика {@code def User.of(...)} объявлена на своём классе.
     */
    private String className;
    /**
     * Допустимо ли здесь {@code this}. Внутри метода и конструктора — да, включая
     * вложенные анонимные функции: {@code this} — обычное имя в области, а области
     * замыкаются по общему правилу. Внутри фабрики — нет: экземпляра ещё не
     * существует, она его и создаёт.
     */
    private boolean thisAllowed;
    /** Допустимо ли здесь {@code super}: только в методе класса, у которого есть родитель. */
    private boolean superAllowed;
    /**
     * Разбирается тело трейта, а не класса.
     * <p>
     * Нужно ровно для {@code super}: у трейта родителя нет и быть не может, и отвечать
     * на вопрос «что такое {@code super} в методе трейта, подмешанного в класс
     * с предком» язык не берётся.
     */
    private boolean inTrait;

    ParseState(Diagnostics diagnostics) {
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
    }

    // --- границы областей ----------------------------------------------------

    /**
     * Тело функции — отдельная территория для управляющих конструкций: {@code return}
     * внутри разрешён, а {@code break} из цикла, объемлющего объявление, — нет.
     * Поэтому глубины циклов, {@code finally}, {@code defer} и веток-значений
     * на время разбора тела обнуляются, а не просто не растут.
     */
    <T> T inFunctionBody(Supplier<T> parse) {
        int outerLoops = loopDepth;
        int outerFinally = finallyDepth;
        int outerDefer = deferDepth;
        int outerBranches = valueBranchDepth;
        loopDepth = 0;
        finallyDepth = 0;
        deferDepth = 0;
        valueBranchDepth = 0;
        functionDepth++;
        try {
            return parse.get();
        } finally {
            functionDepth--;
            loopDepth = outerLoops;
            finallyDepth = outerFinally;
            deferDepth = outerDefer;
            valueBranchDepth = outerBranches;
        }
    }

    /**
     * Тело ветки {@code case} у {@code match} в позиции выражения: отсюда — и только
     * отсюда — можно отдать значение через {@code yield}.
     * <p>
     * Глубины {@code finally} и {@code defer} здесь не обнуляются, а запоминаются:
     * см. {@link #yieldFinallyBase}.
     */
    <T> T inValueBranch(Supplier<T> parse) {
        int outerFinallyBase = yieldFinallyBase;
        int outerDeferBase = yieldDeferBase;
        boolean outerExits = branchExits;
        yieldFinallyBase = finallyDepth;
        yieldDeferBase = deferDepth;
        branchExits = false;
        valueBranchDepth++;
        try {
            return parse.get();
        } finally {
            valueBranchDepth--;
            lastBranchExited = branchExits;
            branchExits = outerExits;
            yieldFinallyBase = outerFinallyBase;
            yieldDeferBase = outerDeferBase;
        }
    }

    /**
     * Тело ветки у {@code match}-инструкции: значения такая ветка не даёт, поэтому
     * {@code yield} внутри неё недопустим — даже если снаружи есть ветка-значение.
     */
    <T> T outOfValueBranch(Supplier<T> parse) {
        int outer = valueBranchDepth;
        valueBranchDepth = 0;
        try {
            return parse.get();
        } finally {
            valueBranchDepth = outer;
        }
    }

    /**
     * Написан ли выход в ветке, которую только что разобрал {@link #inValueBranch}.
     * <p>
     * Проверка синтаксическая и намеренно грубая: она ловит ветку, у которой выхода
     * нет вовсе, и молчит про ту, где {@code yield} стоит под условием. Доказывать,
     * что условие выполнится, — работа, которой в языке нет ни одной стадии; остальное
     * говорит выполнение, когда ветка отработала и значения не дала.
     */
    boolean lastBranchExited() {
        return lastBranchExited;
    }

    /**
     * Отмечает, что ветка-значение уходит наружу и без {@code yield}: {@code return},
     * {@code throw}, {@code break}, {@code continue}. Вне ветки — ничего не делает.
     */
    void markBranchExit() {
        if (valueBranchDepth > 0) {
            branchExits = true;
        }
    }

    /** Тело цикла: то же, что и любое тело, но внутри него разрешены break и continue. */
    <T> T inLoopBody(Supplier<T> parse) {
        loopDepth++;
        try {
            return parse.get();
        } finally {
            loopDepth--;
        }
    }

    <T> T inFinallyBlock(Supplier<T> parse) {
        finallyDepth++;
        try {
            return parse.get();
        } finally {
            finallyDepth--;
        }
    }

    <T> T inDeferBody(Supplier<T> parse) {
        blockHasDefer = true;
        deferDepth++;
        try {
            return parse.get();
        } finally {
            deferDepth--;
        }
    }

    /**
     * Тело класса или трейта. {@code this} и {@code super} на входе не разрешаются:
     * это решает каждый член сам — у метода они есть, у фабрики нет, — а восстановить
     * прежнее состояние на выходе всё равно нужно здесь.
     */
    <T> T inTypeBody(String name, boolean isClass, Supplier<T> parse) {
        String outerClass = className;
        boolean outerThis = thisAllowed;
        boolean outerSuper = superAllowed;
        boolean outerTrait = inTrait;
        className = name;
        inTrait = !isClass;
        try {
            return parse.get();
        } finally {
            className = outerClass;
            thisAllowed = outerThis;
            superAllowed = outerSuper;
            inTrait = outerTrait;
        }
    }

    /**
     * Открывает блок и отдаёт прежнее состояние флага отложенных действий: {@code defer}
     * во вложенном блоке принадлежит ему, а не внешнему, — на то и правило «выход
     * из своей области».
     */
    boolean enterBlock() {
        boolean outer = blockHasDefer;
        blockHasDefer = false;
        return outer;
    }

    /** Закрывает блок: возвращает, было ли в нём отложенное действие, и восстанавливает флаг. */
    boolean leaveBlock(boolean outer) {
        boolean deferred = blockHasDefer;
        blockHasDefer = outer;
        return deferred;
    }

    /** Разрешает {@code this} и {@code super} внутри разбираемого сейчас члена типа. */
    void allowSelf(boolean forThis, boolean forSuper) {
        thisAllowed = forThis;
        superAllowed = forSuper;
    }

    /** Имя разбираемого класса или трейта; {@code null} вне тела типа. */
    String className() {
        return className;
    }

    // --- проверки ------------------------------------------------------------

    void requireLoop(Token keyword) {
        if (loopDepth == 0) {
            diagnostics.error(keyword.span(),
                    "'" + keyword.text() + "' допустим только внутри цикла");
        }
    }

    void requireFunction(Token keyword) {
        if (functionDepth == 0) {
            diagnostics.error(keyword.span(), "'return' допустим только внутри функции");
        }
    }

    /**
     * {@code yield} допустим только в ветке {@code case} у {@code match} в позиции
     * выражения. Заодно отмечает, что ветка своё значение отдаёт: по этой отметке
     * {@code Parser.caseBody} ловит ветку, у которой {@code yield} забыт совсем.
     */
    void requireValueBranch(Token keyword) {
        if (valueBranchDepth == 0) {
            diagnostics.error(keyword.span(), "'yield' допустим только в ветке 'case'"
                    + " у 'match' в позиции выражения: он отдаёт значение ветки."
                    + " Выйти из функции — это 'return'");
            return;
        }
        branchExits = true;
    }

    /**
     * Тот же запрет на выход наружу, что и у {@code return}, но считанный от ветки:
     * {@code yield} дальше своей ветки не идёт, поэтому мешает он только тому
     * {@code finally}, который открыт внутри самой ветки. Про {@code match},
     * целиком написанный внутри {@code finally}, здесь молчим — и это не поблажка,
     * а правда: гасить такому {@code yield} нечего.
     */
    void forbidYieldInFinally(Token keyword) {
        if (finallyDepth <= yieldFinallyBase && deferDepth <= yieldDeferBase) {
            return;
        }
        String where = finallyDepth > yieldFinallyBase ? "блоке 'finally'" : "теле 'defer'";
        diagnostics.error(keyword.span(), "'yield' в " + where + " запрещён: "
                + "он молча погасил бы ошибку, которая сейчас летит наружу."
                + " Отдавайте значение из тела 'try' или из 'catch'");
    }

    /**
     * Выход из {@code finally} наружу запрещён — ошибка разбора, а не предупреждение.
     * <p>
     * Это та же линия, что и {@code if (x = 5)}: конструкция, у которой единственное
     * применение — незаметно проглотить ошибку, летящую наружу, не разбирается в принципе.
     * В Java она разрешена и служит источником багов, которых не видно при чтении.
     */
    void forbidInFinally(Token keyword) {
        if (finallyDepth == 0 && deferDepth == 0) {
            return;
        }
        String where = finallyDepth > 0 ? "блоке 'finally'" : "теле 'defer'";
        diagnostics.error(keyword.span(), "'" + keyword.text() + "' в " + where + " запрещён: "
                + "он молча погасил бы ошибку, которая сейчас летит наружу."
                + " Выходите из тела 'try' или из 'catch'");
    }

    void checkThis(Token token) {
        if (thisAllowed) {
            return;
        }
        diagnostics.error(token.span(), className == null
                ? "'this' допустим только внутри класса"
                : "'this' недопустим внутри фабрики: экземпляра ещё не существует, "
                        + "фабрика его и создаёт");
    }

    void checkSuper(Token token) {
        if (superAllowed) {
            return;
        }
        diagnostics.error(token.span(), superProblem());
    }

    private String superProblem() {
        if (className == null) {
            return "'super' допустим только внутри класса";
        }
        if (inTrait) {
            return "у трейта нет родителя, 'super' здесь неприменим: трейт не знает,"
                    + " в какой класс его подмешают";
        }
        return "у класса '" + className + "' нет родителя, обращаться через 'super' не к чему";
    }
}
