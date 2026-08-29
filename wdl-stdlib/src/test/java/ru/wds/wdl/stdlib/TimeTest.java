package ru.wds.wdl.stdlib;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.wds.wdl.runtime.ErrorKind;
import ru.wds.wdl.runtime.WdlRuntimeError;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Модуль {@code sys.time} — весь на мосте, ни одного описанного метода.
 * <p>
 * Проверяется не {@code java.time} (за него отвечает JDK), а <b>граница</b>:
 * что типы стоят под своими именами, что перечисление приходит строкой, что
 * возвращённый объект снова оказывается классом языка и что закрытое остаётся
 * закрытым.
 */
class TimeTest {

    @Test
    @DisplayName("Дата создаётся фабрикой, считает сама себя и печатается через toString")
    void dateArithmetic() {
        assertEquals("2026-08-29 2026-09-01 2026", Scripts.printed("""
                import sys.time as time
                d = time.Date.of(2026, 8, 29)
                println(d, " ", d.plusDays(3), " ", d.getYear())
                """));
    }

    @Test
    @DisplayName("Перечисление Java приходит в скрипт именем константы")
    void enumComesAsString() {
        assertEquals("SATURDAY true", Scripts.printed("""
                import sys.time as time
                day = time.Date.of(2026, 8, 29).getDayOfWeek()
                println(day, " ", day == "SATURDAY")
                """));
    }

    @Test
    @DisplayName("Объект, вернувшийся из метода, — снова класс языка")
    void returnedObjectStaysAClass() {
        assertEquals("true 9", Scripts.printed("""
                import sys.time as time
                d = time.Date.of(2026, 8, 29)
                later = d.plusDays(40)
                println(later is time.Date, " ", time.Period.between(d, later).getDays())
                """));
    }

    @Test
    @DisplayName("Форматирование: объект одного типа уходит аргументом в метод другого")
    void formatting() {
        assertEquals("29.08.2026", Scripts.printed("""
                import sys.time as time
                println(time.Date.of(2026, 8, 29).format(time.Format.ofPattern("dd.MM.yyyy")))
                """));
    }

    @Test
    @DisplayName("Промежуток считается и переводится в числа языка")
    void durations() {
        assertEquals("90 1", Scripts.printed("""
                import sys.time as time
                d = time.Duration.ofMinutes(90)
                println(d.toMinutes(), " ", d.toHours())
                """));
    }

    @Test
    @DisplayName("Тип без публичных конструкторов отказывает внятно, а не падает")
    void formatterCannotBeCreated() {
        WdlRuntimeError error = Scripts.errorOf("""
                import sys.time as time
                new time.Format()
                """);
        assertEquals(ErrorKind.CALL, error.kind());
        assertTrue(error.getMessage().contains("нет публичных конструкторов"), error.getMessage());
    }

    @Test
    @DisplayName("Модуль открывает только перечисленные типы: политика осталась строгой")
    void unlistedTypeIsRefused() {
        WdlRuntimeError error = Scripts.errorOf("""
                import sys.time as time
                time.Date.of(2026, 8, 29).getChronology()
                """);
        assertEquals(ErrorKind.TYPE, error.kind());
        assertTrue(error.getMessage().contains("откройте этот тип мосту"), error.getMessage());
    }

    @Test
    @DisplayName("Ошибка самого java.time становится ошибкой скрипта, а не крахом движка")
    void javaFailureIsCatchable() {
        assertEquals("поймали", Scripts.printed("""
                import sys.time as time
                try {
                    time.Date.of(2026, 13, 40)
                } catch (e) {
                    println("поймали")
                }
                """));
    }
}
