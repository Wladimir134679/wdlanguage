package ru.wds.wdl.runtime;

import ru.wds.wdl.module.ModuleUnits;
import ru.wds.wdl.module.Unit;
import ru.wds.wdl.source.Span;
import ru.wds.wdl.value.types.ModuleValue;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Выполненные модули одного запуска: ключ → значение.
 * <p>
 * Разбором здесь не занимаются — он на стадию раньше, в {@link ModuleUnits}, и его
 * результатами пользуется ещё и резолвер. Отсюда берётся вторая половина: файл
 * выполняется в своей области, и получившееся значение достаётся всем, кто его
 * импортировал.
 * <p>
 * <b>Модуль выполняется один раз за запуск.</b> Это не оптимизация, а решение о смысле:
 * иначе {@code import} внутри функции выполнял бы файл на каждом вызове, а два импорта
 * одного модуля давали бы два разных набора значений — и класс, полученный из первого,
 * не был бы классом из второго.
 * <p>
 * Отсюда же и круг: если модуль через цепочку импортов просит сам себя, отдавать
 * нечего — он ещё не выполнен. При разборе круг ошибкой не считается (там достаточно
 * не дать типов), а здесь считается: значения без выполнения не бывает.
 */
final class Modules {

    private final ModuleUnits units;
    /** Корневая область запуска: родитель областей всех модулей. */
    private final Environment root;
    private final Map<String, ModuleValue> values = new HashMap<>();
    /** Модули, которые выполняются прямо сейчас, — по ним и виден круг. */
    private final Deque<String> running = new ArrayDeque<>();

    Modules(ModuleUnits units, Environment root) {
        this.units = Objects.requireNonNull(units, "units");
        this.root = Objects.requireNonNull(root, "root");
    }

    /**
     * Значение модуля: готовое из реестра или полученное выполнением его файла.
     *
     * @param span место в скрипте, которому принадлежит вопрос: инструкция
     *             {@code import} или объявление класса, которому нужен родитель
     */
    ModuleValue load(String key, Span span, ExecutionContext context, Interpreter interpreter) {
        if (key.isEmpty()) {
            throw new WdlRuntimeError(span, "пустой путь модуля");
        }
        ModuleValue ready = values.get(key);
        if (ready != null) {
            return ready;
        }
        if (running.contains(key)) {
            throw new WdlRuntimeError(span, "циклический импорт: "
                    + String.join(" → ", running) + " → " + key
                    + ". Модуль не может пользоваться тем, что ещё не выполнено");
        }

        ModuleUnits.Loaded loaded = units.load(key);
        if (!loaded.ok()) {
            throw new WdlRuntimeError(span, loaded.problem() != null
                    ? loaded.problem()
                    : "модуль '" + key + "' разбирается прямо сейчас: круг в импортах");
        }

        running.addLast(key);
        try {
            ModuleValue module = execute(loaded.unit(), context, interpreter);
            values.put(key, module);
            return module;
        } finally {
            running.removeLast();
        }
    }

    private ModuleValue execute(Unit unit, ExecutionContext context, Interpreter interpreter) {
        ModuleScope scope = new ModuleScope(unit.key(), root);
        try {
            // Область модуля уже создана и вложенной быть не должна: имена файла
            // обязаны лечь именно в неё — она же и есть значение модуля.
            interpreter.run(unit.program(), context.withScope(scope).withUnit(unit));
        } catch (WdlRuntimeError error) {
            throw error.inSource(unit.source());
        }
        return scope.module();
    }
}
