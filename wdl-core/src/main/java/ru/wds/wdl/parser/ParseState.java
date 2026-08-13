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
     * Поэтому глубины циклов, {@code finally} и {@code defer} на время разбора тела
     * обнуляются, а не просто не растут.
     */
    <T> T inFunctionBody(Supplier<T> parse) {
        int outerLoops = loopDepth;
        int outerFinally = finallyDepth;
        int outerDefer = deferDepth;
        loopDepth = 0;
        finallyDepth = 0;
        deferDepth = 0;
        functionDepth++;
        try {
            return parse.get();
        } finally {
            functionDepth--;
            loopDepth = outerLoops;
            finallyDepth = outerFinally;
            deferDepth = outerDefer;
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
