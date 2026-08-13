package ru.wds.wdl.runtime;

import ru.wds.wdl.value.Binding;
import ru.wds.wdl.value.Value;
import ru.wds.wdl.value.types.ModuleValue;

import java.util.Objects;

/**
 * Связка имени с именем в модуле — то, что заводит развёрнутый импорт.
 * <p>
 * Держит модуль и имя, а не значение: значение спрашивается каждый раз заново, иначе
 * связка была бы той же копией, только отложенной. Отсюда и всё поведение — обе формы
 * импорта, сам модуль и второй импортёр смотрят в одну ячейку.
 * <p>
 * Связка на связку — обычное дело: модуль {@code lib} мог сам принести имя развёрнутым
 * импортом из {@code base}. Цепочка конечна, потому что круг в импортах останавливает
 * запуск ({@link Modules}).
 */
record ModuleBinding(ModuleValue module, String member) implements Binding {

    ModuleBinding {
        Objects.requireNonNull(module, "module");
        Objects.requireNonNull(member, "member");
    }

    @Override
    public Value value() {
        return module.get(member);
    }

    @Override
    public boolean constant() {
        return module.isConstant(member);
    }

    @Override
    public boolean set(Value value) {
        if (module.isConstant(member)) {
            return false;
        }
        module.set(member, value);
        return true;
    }
}
