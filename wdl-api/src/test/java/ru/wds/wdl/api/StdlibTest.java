package ru.wds.wdl.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.wds.wdl.stdlib.Sys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Пресеты набора модулей.
 * <p>
 * Проверяется одно, но важное: {@link Stdlib#STANDARD} и {@code Sys.registry()} —
 * это один и тот же набор, записанный дважды. Дважды он записан вынужденно —
 * {@code wdl-stdlib} подключён к фасаду как {@code implementation}, и назвать
 * {@code Sys} в его сигнатурах нельзя, — а расходиться этим спискам нельзя тем
 * более: разойдясь, они дают встроенному движку набор беднее, чем у консольного
 * {@code wdl}, и скрипт, работавший в CLI, молча перестаёт работать в приложении.
 */
class StdlibTest {

    @Test
    @DisplayName("STANDARD — это весь реестр sys, слово в слово")
    void standardMatchesRegistry() {
        assertEquals(Sys.registry().keySet(), Stdlib.STANDARD.modules().keySet());
    }

    @Test
    @DisplayName("SAFE — подмножество STANDARD без файлов, сети и потоков")
    void safeIsSubsetWithoutWorld() {
        var safe = Stdlib.SAFE.modules().keySet();
        assertTrue(Stdlib.STANDARD.modules().keySet().containsAll(safe), safe.toString());
        assertTrue(safe.stream().noneMatch(name -> name.startsWith("sys/io")
                || name.startsWith("sys/net") || name.startsWith("sys/gui")
                || name.startsWith("sys/thread")), safe.toString());
    }

    @Test
    @DisplayName("NONE не даёт ни одного модуля — ни по import, ни в корне")
    void noneGivesNothing() {
        assertTrue(Stdlib.NONE.modules().isEmpty());
        assertTrue(Stdlib.NONE.rootLibraries().isEmpty());
    }
}
