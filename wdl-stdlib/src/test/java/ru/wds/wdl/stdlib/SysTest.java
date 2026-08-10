package ru.wds.wdl.stdlib;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static ru.wds.wdl.stdlib.Scripts.errorOf;
import static ru.wds.wdl.stdlib.Scripts.printed;

/**
 * Набор {@link Sys}: какие модули приходят вместе с библиотекой и как они выглядят
 * из скрипта.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class SysTest {

    @Test
    @DisplayName("std — обычная библиотека, и её можно импортировать модулем")
    void standardLibraryAsModule() {
        assertEquals("1024 module std", printed("""
                import std as s
                println(s.pow(2, 10), " ", s)
                """));
    }

    @Test
    @DisplayName("модули набора видны каждый под своим именем")
    void everyModuleIsThere() {
        assertEquals("module sys/io module sys/json module sys/net/http", printed("""
                import sys.io as io
                import sys.json as json
                import sys.net.http as http
                println(io, " ", json, " ", http)
                """));
    }

    @Test
    @DisplayName("модуля вне набора не существует — и ошибка говорит, где искали")
    void unknownModule() {
        String message = errorOf("import sys.sql").getMessage();
        assertTrue(message.contains("модуль 'sys/sql' не найден"), message);
        assertTrue(message.contains("встроенного модуля 'sys/sql' тоже нет"), message);
    }

    @Test
    @DisplayName("модули работают вместе: json поверх файла")
    void modulesWorkTogether() {
        assertEquals("7", printed("""
                import sys.json as json

                text = json.stringify({id: 7})
                println(json.parse(text).id)
                """));
    }
}
