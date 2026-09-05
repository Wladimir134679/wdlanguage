package ru.wds.wdl.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.wds.wdl.value.types.IntValue;
import ru.wds.wdl.value.types.StringValue;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Перечисление области: то, чем наполняют панель переменных отладчика и дополнение
 * в REPL. Самому выполнению это не нужно, поэтому и спрашивается снаружи.
 */
class ScopeNamesTest {

    @Test
    @DisplayName("область перечисляет свои имена и не показывает чужие")
    void namesHere() {
        Scope root = Scope.root();
        root.define("price", IntValue.of(120));
        root.defineConstant("LIMIT", IntValue.of(10));

        Environment nested = root.child();
        nested.define("count", IntValue.of(2));

        assertEquals(Set.of("price", "LIMIT"), root.namesHere());
        assertEquals(Set.of("count"), nested.namesHere());
    }

    @Test
    @DisplayName("видимые имена собираются по цепочке областей, ближняя сильнее")
    void names() {
        Scope root = Scope.root();
        root.define("price", IntValue.of(120));

        Environment nested = root.child();
        nested.define("count", IntValue.of(2));
        nested.define("price", StringValue.of("перекрыто"));

        assertEquals(Set.of("price", "count"), nested.names());
        assertEquals(List.of("count", "price"), nested.names().stream().sorted().toList());
        assertTrue(nested.names().containsAll(root.names()), "внешние имена видны изнутри");
        assertFalse(root.names().contains("count"), "вложенные имена наружу не выходят");
    }

    @Test
    @DisplayName("окружение, написанное приложением, перечислять себя не обязано")
    void foreignEnvironmentStaysSilent() {
        // Реализация по умолчанию отвечает пустым множеством: библиотека, которая
        // просто заводит имена в чужой области, о таком вопросе не знает.
        Environment foreign = new Environment() {
            @Override
            public ru.wds.wdl.value.Value lookup(String name) {
                return null;
            }

            @Override
            public ru.wds.wdl.value.Value lookupHere(String name) {
                return null;
            }

            @Override
            public boolean isDefined(String name) {
                return false;
            }

            @Override
            public void define(String name, ru.wds.wdl.value.Value value) {
            }

            @Override
            public void defineConstant(String name, ru.wds.wdl.value.Value value) {
            }

            @Override
            public boolean isConstantHere(String name) {
                return false;
            }

            @Override
            public Assignment assign(String name, ru.wds.wdl.value.Value value) {
                return Assignment.ABSENT;
            }

            @Override
            public Environment child() {
                return this;
            }
        };

        assertEquals(Set.of(), foreign.names());
        assertEquals(Set.of(), foreign.namesHere());
    }
}
