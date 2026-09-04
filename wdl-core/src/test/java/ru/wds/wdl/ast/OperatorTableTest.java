package ru.wds.wdl.ast;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.wds.wdl.ast.op.BinaryOp;
import ru.wds.wdl.ast.op.Overloads;
import ru.wds.wdl.ast.op.UnaryOp;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Таблица перегружаемых операторов: она и есть сверка кода с
 * {@code plans/operator_overloads.md}.
 * <p>
 * Тест написан списком имён целиком, а не выборочными проверками, нарочно: документ
 * обещает <b>ровно этот</b> набор, и лишняя строка в таблице так же плоха, как
 * недостающая — она обещает пользователю то, чего язык не делает.
 */
class OperatorTableTest {

    @Test
    @DisplayName("бинарных членов ровно столько, сколько обещает документ")
    void binaryMembers() {
        assertEquals(Set.of("+", "-", "*", "/", "%", "&", "|", "^", "<<", ">>", ">>>",
                        "..", "==", "<=>", "in"),
                Overloads.binaryNames());
    }

    @Test
    @DisplayName("унарные члены — только смена знака, плюс и дополнение")
    void unaryMembers() {
        assertEquals(Set.of("-", "+", "~"), Overloads.unaryNames());
    }

    @Test
    @DisplayName("четыре сравнения закрывает один член, а '!=' — тот же, что '=='")
    void groups() {
        assertEquals(Overloads.ORDER, BinaryOp.LESS.member());
        assertEquals(Overloads.ORDER, BinaryOp.LESS_EQUAL.member());
        assertEquals(Overloads.ORDER, BinaryOp.GREATER.member());
        assertEquals(Overloads.ORDER, BinaryOp.GREATER_EQUAL.member());
        assertEquals("==", BinaryOp.EQUAL.member());
        assertEquals("==", BinaryOp.NOT_EQUAL.member());
    }

    @Test
    @DisplayName("все четыре записи принадлежности идут к одному члену 'in'")
    void membership() {
        assertEquals("in", BinaryOp.IN.member());
        assertEquals("in", BinaryOp.NOT_IN.member());
        assertEquals("in", BinaryOp.HAS.member());
        assertEquals("in", BinaryOp.NOT_HAS.member());
        // Зеркала у него нет: обе стороны отношения уже есть в грамматике.
        assertFalse(Overloads.allowsMirror("in"));
        assertTrue(Overloads.allowsMirror("+"));
    }

    @Test
    @DisplayName("'&&', '||', 'is' и '!' членов не имеют вовсе")
    void notOverloadable() {
        assertNull(BinaryOp.AND.member());
        assertNull(BinaryOp.OR.member());
        assertNull(BinaryOp.IS.member());
        assertNull(BinaryOp.NOT_IS.member());
        assertNull(UnaryOp.NOT.member());
        assertFalse(BinaryOp.AND.overloadable());
        assertFalse(UnaryOp.NOT.overloadable());
    }

    @Test
    @DisplayName("у сравнений и принадлежности ответ члена — ещё не ответ выражения")
    void memberIsResult() {
        assertTrue(BinaryOp.ADD.memberIsResult());
        assertTrue(BinaryOp.SHIFT_LEFT.memberIsResult());
        assertTrue(BinaryOp.RANGE.memberIsResult());
        assertFalse(BinaryOp.LESS.memberIsResult());
        assertFalse(BinaryOp.EQUAL.memberIsResult());
        assertFalse(BinaryOp.IN.memberIsResult());
        assertFalse(BinaryOp.AND.memberIsResult());
    }

    @Test
    @DisplayName("у производных записей и запретов есть причина текстом")
    void reasons() {
        assertNotNull(Overloads.derived("!="));
        assertNotNull(Overloads.derived("!in"));
        assertNotNull(Overloads.derived("has"));
        assertNotNull(Overloads.derived("<"));
        assertNull(Overloads.derived("+"));

        assertNotNull(Overloads.forbidden("&&"));
        assertNotNull(Overloads.forbidden("is"));
        assertNotNull(Overloads.forbidden("+="));
        assertNotNull(Overloads.forbidden("="));
        assertNull(Overloads.forbidden("+"));
    }

    @Test
    @DisplayName("ключ члена разводит прямой оператор, зеркальный и унарный")
    void keys() {
        assertEquals("+", Overloads.key("+", false, false));
        assertEquals("+@", Overloads.key("+", true, false));
        assertEquals("+()", Overloads.key("+", false, true));
        // Обычного имени манглинг не касается: метод без параметров остаётся собой.
        assertEquals("size", Overloads.key("size", false, true));
    }
}
