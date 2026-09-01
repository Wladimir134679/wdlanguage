package ru.wds.wdl.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.wds.wdl.runtime.ErrorKind;
import ru.wds.wdl.runtime.WdlRuntimeError;
import ru.wds.wdl.source.Span;
import ru.wds.wdl.value.Arity;
import ru.wds.wdl.value.CallContext;
import ru.wds.wdl.value.Value;
import ru.wds.wdl.value.types.ArrayValue;
import ru.wds.wdl.value.types.BoolValue;
import ru.wds.wdl.value.types.FloatValue;
import ru.wds.wdl.value.types.IntValue;
import ru.wds.wdl.value.types.MapValue;
import ru.wds.wdl.value.types.NullValue;
import ru.wds.wdl.value.types.StringValue;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Аргументы вызова: то, что библиотека спрашивает у {@link Args} вместо своей проверки.
 * <p>
 * Проверяется не столько преобразование — оно очевидно, — сколько <b>сообщение</b>:
 * оно одно на все библиотеки, и в нём должно быть видно, кого позвали, какой аргумент
 * не подошёл и что вместо него пришло. И класс ошибки: «не тот тип» и «тип тот,
 * а значение не годится» скрипт ловит по-разному.
 */
class ArgsTest {

    private static final Span AT = new Span(0, 5);

    /** Вызывающий, которому нечего сказать: тесту про типы аргументов вывод не нужен. */
    private static final CallContext NOWHERE = text -> {
    };

    private static Args args(Value... values) {
        return Args.of("open", List.of(values), NOWHERE, AT);
    }

    @Test
    @DisplayName("это обычный список значений — тело, которому проверка не нужна, ничего не заметит")
    void isPlainList() {
        Args args = args(IntValue.of(1), StringValue.of("два"));
        assertEquals(2, args.size());
        assertEquals(IntValue.of(1), args.get(0));
        assertEquals(List.of(IntValue.of(1), StringValue.of("два")), args);
    }

    @Test
    @DisplayName("значения приходят своим типом")
    void typedAccess() {
        Args args = args(StringValue.of("a.txt"), IntValue.of(7), FloatValue.of(1.5),
                BoolValue.TRUE, ArrayValue.of(List.of(IntValue.of(1))), new MapValue());
        assertEquals("a.txt", args.string(0, "путь"));
        assertEquals(7, args.integer(1, "размер"));
        assertEquals(1.5, args.real(2, "доля"));
        assertTrue(args.flag(3, "признак"));
        assertEquals(1, args.array(4, "элементы").size());
        assertEquals(0, args.object(5, "опции").size());
    }

    @Test
    @DisplayName("за концом списка — null языка, а не исключение: необязательный аргумент законен")
    void missingIsNull() {
        Args args = args(StringValue.of("a.txt"));
        assertSame(NullValue.NULL, args.at(1));
        assertFalse(args.has(1));
        assertEquals("GET", args.string(1, "метод", "GET"));
        assertEquals(4, args.integer(1, "отступ", 4));
        assertThrows(IndexOutOfBoundsException.class, () -> args.get(1));
    }

    @Test
    @DisplayName("переданный null считается непереданным: значение по умолчанию побеждает")
    void explicitNullIsAbsent() {
        Args args = args(StringValue.of("a.txt"), NullValue.NULL);
        assertFalse(args.has(1));
        assertEquals("GET", args.string(1, "метод", "GET"));
    }

    @Test
    @DisplayName("сообщение называет вызванного, роль аргумента и то, что пришло")
    void message() {
        WdlRuntimeError error = assertThrows(WdlRuntimeError.class,
                () -> args(IntValue.of(5)).string(0, "путь к файлу"));
        assertEquals("open(): путь к файлу: ожидалась строка, а здесь число (5)", error.getMessage());
        assertEquals(AT, error.span());
    }

    @Test
    @DisplayName("без роли аргумент называется номером, каким его видит автор скрипта")
    void messageWithoutRole() {
        WdlRuntimeError error = assertThrows(WdlRuntimeError.class,
                () -> args(StringValue.of("a"), StringValue.of("b")).number(1));
        assertEquals("open(): аргумент 2: ожидалось число, а здесь строка (\"b\")", error.getMessage());
    }

    @Test
    @DisplayName("не тот тип — TypeError, не то значение — ValueError: скрипт ловит их по-разному")
    void errorKinds() {
        WdlRuntimeError wrongType = assertThrows(WdlRuntimeError.class,
                () -> args(IntValue.of(5)).string(0, "путь"));
        assertEquals(ErrorKind.TYPE, wrongType.kind());
        assertEquals(ErrorKind.VALUE, args(IntValue.of(-1)).bad(0, "размер", "ожидалось число").kind());
    }

    @Test
    @DisplayName("вещественное целым не считается: индекс и размер дробными не бывают")
    void integerIsStrict() {
        assertEquals("open(): размер: ожидалось целое число, а здесь число (2.5)",
                assertThrows(WdlRuntimeError.class,
                        () -> args(FloatValue.of(2.5)).integer(0, "размер")).getMessage());
        assertEquals("open(): размер: ожидалось целое число, а здесь число (2.0)",
                assertThrows(WdlRuntimeError.class,
                        () -> args(FloatValue.of(2.0)).integer(0, "размер")).getMessage());
    }

    @Test
    @DisplayName("функция приходит значением — библиотека может позвать её сама")
    void callback() {
        Value twice = ru.wds.wdl.runtime.BuiltinFunction.of("twice", Arity.exactly(1),
                (context, arguments, span) -> IntValue.of(arguments.integer(0) * 2));
        assertEquals(IntValue.of(8),
                args(twice).function(0, "обработчик").call(text -> { }, List.of(IntValue.of(4)), AT));
    }

    @Test
    @DisplayName("общий вид сообщения открыт наружу — для значений внутри аргумента")
    void sharedShape() {
        assertEquals("http: 'headers': ожидался объект, а здесь строка (\"a\")",
                Args.because("http: 'headers'", "ожидался объект", StringValue.of("a")));
    }
}
