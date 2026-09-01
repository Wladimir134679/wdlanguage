package ru.wds.wdl.stdlib;

import ru.wds.wdl.bridge.Module;
import ru.wds.wdl.bridge.reflect.JavaBridge;
import ru.wds.wdl.module.Library;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Period;
import java.time.format.DateTimeFormatter;

/**
 * Модуль {@code sys.time}: дата, время и промежутки — прямо из {@code java.time}.
 * <p>
 * Весь модуль — это <b>список открытых типов</b>. Ни одного описанного метода,
 * ни одной лямбды: методы у {@code java.time} уже написаны, и переписывать их
 * на построителе значило бы делать чужую работу второй раз, а потом делать её
 * заново после каждого обновления JDK.
 * <pre>{@code
 * import sys.time as time
 *
 * d = time.Date.of(2026, 8, 29)
 * println(d.plusDays(3), " ", d.getDayOfWeek())    // 2026-09-01 SATURDAY
 * }</pre>
 *
 * <h2>Почему это можно открывать целиком</h2>
 * {@code java.time} неизменяем и ничего не умеет, кроме арифметики над датами:
 * ни файлов, ни сети, ни доступа к процессу. Открывать его схемой поимённо значило
 * бы переписать сто методов ради нуля выигрыша.
 * <p>
 * <b>Но не {@code wrapUnknown}.</b> Политика остаётся строгой: скрипт получает
 * ровно перечисленные типы, а метод, вернувший что-то другое (скажем,
 * {@code Chronology}), честно откажет вместо того, чтобы молча открыть половину JDK.
 * Список ниже — это и есть граница модуля.
 *
 * <h2>Что делают перечисления и строки</h2>
 * {@code getDayOfWeek()} возвращает строку {@code "SATURDAY"}, а метод, ждущий
 * {@code DayOfWeek}, принимает такую же строку: перечисление на границе — это имя
 * константы ({@code docs/java-interop.md}). Поэтому {@code DayOfWeek} и {@code Month}
 * в списке типов не нужны.
 */
public final class Times {

    private Times() {
    }

    /**
     * Библиотека модуля: собирается на запуск, как и все остальные.
     * <p>
     * Мост сам умеет класть свои имена в область, поэтому весь модуль — это он
     * и его закрытие: описывать типы построителем незачем, они уже описаны в JDK.
     */
    public static Library library() {
        JavaBridge bridge = bridge();
        return Module.named("sys/time")
                .install(bridge::installTo)
                .onClose(bridge::close)
                .build();
    }

    /**
     * Открытые типы и их имена в скрипте.
     * <p>
     * Имена короче исходных, потому что модуль уже назван: {@code time.Date} читается
     * лучше, чем {@code time.LocalDate}, а {@code java.time.Date} путать не с чем —
     * старого {@code java.util.Date} здесь нет и не будет.
     */
    private static JavaBridge bridge() {
        return JavaBridge.open()
                .expose(LocalDate.class, "Date")
                .expose(LocalTime.class, "Time")
                .expose(LocalDateTime.class, "DateTime")
                .expose(Instant.class, "Instant")
                .expose(Duration.class, "Duration")
                .expose(Period.class, "Period")
                // Формат — единственный тип, которому нужна оговорка: создавать его
                // через 'new' нельзя (у DateTimeFormatter нет публичных конструкторов),
                // и мост скажет об этом сам. Фабрика 'ofPattern' открыта заодно
                // со всей статикой.
                .expose(DateTimeFormatter.class, "Format")
                .build();
    }
}
