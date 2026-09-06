package ru.wds.wdl.tools.analysis;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Правило видимости языка, записанное один раз.
 * <p>
 * Оно состоит из двух половин, и обе обязательны:
 * <ol>
 *   <li><b>Имя существует с той строки, где его завели.</b> На верхнем уровне файла
 *       и внутри любой области видно только объявленное выше — стадии между разбором
 *       и выполнением нет, инструкции идут подряд.</li>
 *   <li><b>Тело функции видит внешнее целиком</b>, включая объявленное ниже по файлу:
 *       имя ищется в момент вызова, а к тому времени файл отработан. На этом стоят
 *       рекурсия и взаимная рекурсия, и без этой половины редактор не предложил бы
 *       функции её собственное имя.</li>
 * </ol>
 * Отсюда единственный признак, который здесь считается: пересечена ли по дороге
 * наружу граница вызова ({@link ScopeKind#isCallBoundary()}). До неё имена
 * фильтруются позицией, после — нет.
 */
final class Visibility {

    private Visibility() {
    }

    /**
     * Объявление, к которому ведёт имя, употреблённое в этой точке, или {@code null}.
     * <p>
     * Промах — обычное дело: имя может быть встроенным, приходить из библиотеки
     * или заводиться приложением. Диагностики отсюда не следует.
     */
    static Symbol resolve(String name, LexicalScope scope, int offset) {
        boolean whole = false;
        for (LexicalScope current = scope; current != null; current = current.parent()) {
            Symbol found = lookup(current, name, offset, whole);
            if (found != null) {
                return found;
            }
            whole |= current.kind().isCallBoundary();
        }
        return null;
    }

    /**
     * Все имена, видимые в этой точке, — ближняя область раньше и сильнее.
     * <p>
     * Одно имя встречается в ответе один раз: перекрытое внешнее не показывается,
     * потому что обратиться к нему отсюда всё равно нельзя.
     */
    static List<Symbol> visibleAt(LexicalScope scope, int offset) {
        Map<String, Symbol> byName = new LinkedHashMap<>();
        boolean whole = false;
        for (LexicalScope current = scope; current != null; current = current.parent()) {
            for (Symbol symbol : current.symbols()) {
                if (whole || symbol.nameSpan().start() < offset) {
                    byName.putIfAbsent(symbol.name(), symbol);
                }
            }
            whole |= current.kind().isCallBoundary();
        }
        return List.copyOf(new ArrayList<>(byName.values()));
    }

    /**
     * Имя в самой этой области. Символы отсортированы по тексту, поэтому подходящих
     * перебираем до конца: сильнее последнее объявление — {@code def f()} после
     * {@code f = 1} перекрывает переменную.
     */
    private static Symbol lookup(LexicalScope scope, String name, int offset, boolean whole) {
        Symbol found = null;
        for (Symbol symbol : scope.symbols()) {
            if (!symbol.name().equals(name)) {
                continue;
            }
            if (whole || symbol.nameSpan().start() < offset || itself(symbol, offset)) {
                found = symbol;
            }
        }
        return found;
    }

    /**
     * Стоит ли смещение в самом имени объявления.
     * <p>
     * Нужно потому, что первое присваивание — одновременно и объявление, и запись:
     * в {@code price = 120} имя {@code price} и заводится, и употребляется. Без этой
     * проверки цель такого присваивания не разрешалась бы никуда — то есть переименование
     * пропустило бы ровно ту строку, где имя появилось.
     */
    private static boolean itself(Symbol symbol, int offset) {
        return offset >= symbol.nameSpan().start() && offset <= symbol.nameSpan().end();
    }
}
