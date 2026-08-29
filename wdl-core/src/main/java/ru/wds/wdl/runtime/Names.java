package ru.wds.wdl.runtime;

import java.util.List;

/**
 * Подсказка по имени: «похоже на …» вместо голого «нет такого».
 * <p>
 * <b>Считается только на пути ошибки.</b> Обращение к члену — горячий путь, и платить
 * за подсказку на каждом удачном чтении нельзя; поэтому расстояние меряется тогда,
 * когда ошибка уже случилась и вот-вот полетит наверх. Тот же приём, что
 * в {@link Declarations}: диагностика — не стадия.
 * <p>
 * <b>Порог зависит от длины имени.</b> На длинном имени две правки — почти наверняка
 * описка, на коротком та же пара правок меняет имя целиком ({@code get} и {@code set}
 * различаются на две буквы, а значат разное). Поэтому порог растёт вместе с именем,
 * а совсем короткому не подсказывается ничего: ничего не предложить честнее,
 * чем предложить наугад.
 */
public final class Names {

    private Names() {
    }

    /**
     * Ближайшее имя из списка или {@code null}, если ни одно не похоже.
     * <p>
     * При равном расстоянии выигрывает первое: порядок имён — это порядок объявления
     * в наборе, а он осмысленнее алфавитного.
     */
    public static String closestTo(String name, List<String> known) {
        int limit = threshold(name.length());
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (String candidate : known) {
            // Разница длин уже не меньше расстояния — считать нечего.
            if (Math.abs(candidate.length() - name.length()) > limit) {
                continue;
            }
            int distance = distance(name, candidate, limit);
            if (distance <= limit && distance < bestDistance) {
                best = candidate;
                bestDistance = distance;
            }
        }
        return best;
    }

    /** До двух букв не подсказываем вовсе, дальше — одна правка, с шести букв — две. */
    private static int threshold(int length) {
        if (length <= 2) {
            return 0;
        }
        return length <= 5 ? 1 : 2;
    }

    /**
     * Редакционное расстояние с отсечкой: как только вся строка дороже предела,
     * считать дальше незачем.
     */
    private static int distance(String from, String to, int limit) {
        int[] previous = new int[to.length() + 1];
        int[] current = new int[to.length() + 1];
        for (int j = 0; j <= to.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= from.length(); i++) {
            current[0] = i;
            int rowBest = current[0];
            for (int j = 1; j <= to.length(); j++) {
                int substitution = previous[j - 1] + (from.charAt(i - 1) == to.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(substitution, Math.min(previous[j] + 1, current[j - 1] + 1));
                rowBest = Math.min(rowBest, current[j]);
            }
            if (rowBest > limit) {
                return Integer.MAX_VALUE;
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[to.length()];
    }
}
