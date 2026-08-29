package ru.wds.wdl.interop;

import ru.wds.wdl.runtime.ErrorKind;
import ru.wds.wdl.runtime.WdlRuntimeError;
import ru.wds.wdl.source.Span;
import ru.wds.wdl.value.Value;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Выбор перегрузки: у Java под одним именем живёт несколько методов, у языка — один.
 * <p>
 * Правило выбора — <b>наименьшая суммарная стоимость приведения аргументов</b>
 * ({@link Marshal#cost}). Точное совпадение бесплатно, приведение стоит тем дороже,
 * чем дальше уводит от написанного, а {@code Object} дороже всего. Отсюда без единого
 * исключения следует то, чего человек и ждёт: {@code f(5)} при выборе между
 * {@code f(long)} и {@code f(Object)} уходит в первый.
 *
 * <h2>Ничья — это отказ, а не «первый попавшийся»</h2>
 * Две подходящие одинаково перегрузки означают, что <b>вопрос задан неоднозначно</b>,
 * и выбрать за автора скрипта нечем: любой выбор будет угадыванием, а ошибётся оно
 * молча и в рантайме. Поэтому мост отказывает и перечисляет кандидатов — тогда видно,
 * что делать: передать число вместо строки, привести явно, взять другой метод.
 */
public final class Overloads {

    private Overloads() {
    }

    /**
     * Перегрузка, подходящая этим аргументам.
     *
     * @param subject    имя для сообщений: {@code "Date.plusDays()"}, {@code "new Date"}
     * @param candidates перегрузки под одним именем; пустым не бывает
     * @throws WdlRuntimeError если не подошла ни одна или подошли несколько одинаково
     */
    public static JavaExecutable select(String subject, List<JavaExecutable> candidates,
                                        List<Value> arguments, Marshal marshal, Span span) {
        JavaExecutable best = null;
        int bestCost = Integer.MAX_VALUE;
        List<JavaExecutable> tied = new ArrayList<>(2);

        for (JavaExecutable candidate : candidates) {
            int cost = candidate.cost(arguments, marshal);
            if (cost == Marshal.IMPOSSIBLE) {
                continue;
            }
            if (cost < bestCost) {
                bestCost = cost;
                best = candidate;
                tied.clear();
            } else if (cost == bestCost) {
                tied.add(candidate);
            }
        }

        if (best == null) {
            throw nothingFits(subject, candidates, arguments, marshal, span);
        }
        if (!tied.isEmpty()) {
            tied.add(0, best);
            throw new WdlRuntimeError(ErrorKind.CALL, span, subject
                    + ": подходит несколько методов Java — " + describe(tied)
                    + "; уточните типы аргументов");
        }
        return best;
    }

    /**
     * Почему не подошло ничего.
     * <p>
     * Два разных ответа, потому что и ошибки разные: «столько аргументов не принимает
     * никто» человек чинит, дописав аргумент, а «аргумент не того типа» — исправив
     * значение. Одно сообщение на оба случая заставляло бы гадать, что именно не так.
     */
    private static RuntimeException nothingFits(String subject, List<JavaExecutable> candidates,
                                                List<Value> arguments, Marshal marshal, Span span) {
        boolean countFits = candidates.stream().anyMatch(candidate ->
                arguments.size() >= candidate.minimum() && arguments.size() <= candidate.maximum());
        if (!countFits) {
            return new WdlRuntimeError(ErrorKind.CALL, span, subject + ": передано "
                    + arguments.size() + " " + argumentWord(arguments.size())
                    + ", а Java принимает " + describe(candidates));
        }
        // Перегрузка одна — значит, известно, какой именно аргумент не подошёл,
        // и общий отказ здесь был бы ленью: пусть скажет тот, кто знает подробности.
        for (JavaExecutable candidate : candidates) {
            RuntimeException detailed = candidate.explain(arguments, marshal, subject, span);
            if (detailed != null && candidates.size() == 1) {
                return detailed;
            }
        }
        return new WdlRuntimeError(ErrorKind.TYPE, span, subject
                + ": аргументы не подошли ни одному методу Java — " + describe(candidates)
                + "; здесь " + given(arguments));
    }

    private static String describe(List<JavaExecutable> candidates) {
        return candidates.stream()
                .map(JavaExecutable::describe)
                .distinct()
                .collect(Collectors.joining(", "));
    }

    private static String given(List<Value> arguments) {
        return arguments.isEmpty() ? "аргументов нет" : arguments.stream()
                .map(value -> value.type().title() + " (" + value.display() + ")")
                .collect(Collectors.joining(", "));
    }

    /** Форма слова при числе — та же, что у {@code Arity} в ядре. */
    private static String argumentWord(int count) {
        int last = count % 10;
        int two = count % 100;
        if (two >= 11 && two <= 14) {
            return "аргументов";
        }
        if (last == 1) {
            return "аргумент";
        }
        return last >= 2 && last <= 4 ? "аргумента" : "аргументов";
    }
}
