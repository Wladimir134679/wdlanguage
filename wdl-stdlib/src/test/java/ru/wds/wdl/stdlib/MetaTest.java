package ru.wds.wdl.stdlib;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/** Модуль {@code sys.meta}: метаданные декоратора, собранные из скрипта. */
class MetaTest {

    private static final Duration LIMIT = Duration.ofSeconds(15);

    @Test
    @DisplayName("m.of собирает те же метаданные, что и '@'")
    void sameMetaAsDecorator() {
        assertEquals("true", Scripts.printed("""
                import sys.meta as m

                seen = []
                def reg(meta) {
                    seen = seen + [meta.name + "/" + typeof(meta.target) + "/" + meta.isAnonymous]
                }

                @[reg]
                def app() {}

                reg(m.of(app))
                println(seen[0] == seen[1])
                """));
    }

    @Test
    @DisplayName("декоратор, позванный руками, делает ровно то же самое")
    void decoratorCalledByHand() {
        assertEquals("обёрнуто:3 обёрнуто:3", Scripts.printed("""
                import sys.meta as m

                def wrap(meta) {
                    return def (*args, **named) => "обёрнуто:" + meta.target(*args, **named);
                }

                @[wrap]
                def sum(a, b) => a + b

                def plain(a, b) => a + b
                byHand = wrap(m.of(plain))

                println(sum(1, 2), " ", byHand(1, 2))
                """));
    }

    @Test
    @DisplayName("у анонимной функции isAnonymous истинно")
    void anonymousIsReported() {
        assertEquals("true false", Scripts.printed("""
                import sys.meta as m

                f = def (a) => a
                def named(a) => a

                println(m.of(f).isAnonymous, " ", m.of(named).isAnonymous)
                """));
    }

    @Test
    @DisplayName("у значения без имени name пустое")
    void valueWithoutName() {
        assertEquals("null 42", Scripts.printed("""
                import sys.meta as m

                meta = m.of(42)
                println(meta.name, " ", meta.target)
                """));
    }

    @Test
    @DisplayName("анонимная функция берёт имя переменной, в которую её положили")
    void anonymousTakesVariableName() {
        assertEquals("f true CONST true", Scripts.printed("""
                import sys.meta as m

                f = def (x) => x
                const CONST = def (x) => x

                println(m.of(f).name, " ", m.of(f).isAnonymous, " ",
                        m.of(CONST).name, " ", m.of(CONST).isAnonymous)
                """));
    }

    @Test
    @DisplayName("имени нет вовсе — name пустое, а не выдуманное 'def'")
    void namelessFunctionHasNoName() {
        // 'def' — подстановка для сообщений об ошибках. Попади она в данные,
        // commands[meta.name] = ... регистрировал бы все такие функции под одним ключом.
        assertEquals("null true", Scripts.printed("""
                import sys.meta as m

                meta = m.of(def (x) => x)
                println(meta.name, " ", meta.isAnonymous)
                """));
    }

    @Test
    @DisplayName("замок synchronized переживает обёртку: считает внутреннее значение")
    void synchronizedSurvivesWrapping() {
        assertTimeoutPreemptively(LIMIT, () -> assertEquals("2000", Scripts.printed("""
                import sys.thread as th

                count = 0
                def pass(meta) {
                    return def (*args, **named) => meta.target(*args, **named);
                }

                @[pass]
                synchronized def bump() {
                    count = count + 1
                }

                workers = []
                for (i = 0; i < 2; i += 1) {
                    workers = workers + [th.spawn(def () {
                        for (n = 0; n < 1000; n += 1) bump()
                    })]
                }
                for (w in workers) w.join()
                println(count)
                """)));
    }
}
