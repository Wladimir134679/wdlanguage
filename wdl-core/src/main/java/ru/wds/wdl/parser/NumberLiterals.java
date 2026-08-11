package ru.wds.wdl.parser;

import ru.wds.wdl.diagnostic.Diagnostics;
import ru.wds.wdl.lexer.Token;
import ru.wds.wdl.lexer.TokenType;
import ru.wds.wdl.value.Value;
import ru.wds.wdl.value.types.FloatValue;
import ru.wds.wdl.value.types.IntValue;

/**
 * Текст числового токена → значение. Считается один раз, при разборе: во время
 * выполнения литерал уже готов.
 * <p>
 * Лексер к этому моменту убрал префиксы и разделители разрядов, так что здесь
 * остаётся выбрать представление и поймать выход за границы.
 */
final class NumberLiterals {

    private NumberLiterals() {
    }

    static Value of(Token token, Diagnostics diagnostics) {
        String text = token.text();
        try {
            return switch (token.type()) {
                case INT -> IntValue.of(Long.parseLong(text));
                // Шестнадцатеричный и двоичный литерал — это про биты, а не про величину:
                // 0xFFFFFFFFFFFFFFFF законно читается как маска из 64 единиц, то есть -1.
                case HEX -> IntValue.of(Long.parseUnsignedLong(text, 16));
                case BIN -> IntValue.of(Long.parseUnsignedLong(text, 2));
                default -> floatLiteral(token, text, diagnostics);
            };
        } catch (NumberFormatException e) {
            if (token.type() == TokenType.INT) {
                // Целое не влезло в 64 бита: считаем приблизительно, но говорим об этом.
                diagnostics.warning(token.span(),
                        "целое число не помещается в 64 бита, оно станет вещественным и потеряет точность");
                return FloatValue.of(Double.parseDouble(text));
            }
            diagnostics.error(token.span(), "не удалось разобрать число '" + text + "'");
            return IntValue.ZERO;
        }
    }

    private static Value floatLiteral(Token token, String text, Diagnostics diagnostics) {
        double value = Double.parseDouble(text);
        if (Double.isInfinite(value)) {
            diagnostics.warning(token.span(), "число слишком велико для вещественного типа, получилось inf");
        }
        return FloatValue.of(value);
    }
}
