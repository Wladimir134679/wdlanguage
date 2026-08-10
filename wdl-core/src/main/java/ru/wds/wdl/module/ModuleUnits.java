package ru.wds.wdl.module;

import ru.wds.wdl.ast.Program;
import ru.wds.wdl.diagnostic.Diagnostics;
import ru.wds.wdl.lexer.Lexer;
import ru.wds.wdl.lexer.Token;
import ru.wds.wdl.parser.Parser;
import ru.wds.wdl.resolve.Resolution;
import ru.wds.wdl.resolve.Resolver;
import ru.wds.wdl.source.Source;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Разобранные модули одного запуска: ключ → {@link Unit}.
 * <p>
 * Здесь только разбор — {@code Lexer} → {@code Parser} → {@code Resolver} — и ни одной
 * выполненной инструкции. Значения модулей появляются позже и живут в своём реестре.
 * <p>
 * <b>Файл читается тогда, когда выполняется его {@code import}, и не раньше.</b>
 * Спрашивает отсюда только {@code runtime.Modules}; подготовка главного скрипта
 * в реестр не заглядывает вовсе. Отсюда главное свойство: модуля может не быть
 * на диске в момент запуска — скрипт, который до этого {@code import} не дошёл,
 * о нём и не спросит.
 * <p>
 * Разбор модуля его импорты за собой не тянет: {@code import} внутри модуля — такая же
 * инструкция, и выполнится она в свой черёд.
 * <p>
 * Реестр один на запуск: разбери модуль дважды — получатся два дерева и два набора
 * форм, и класс, полученный из первого, не будет тем же классом, что из второго.
 */
public final class ModuleUnits {

    private final ModuleSource source;
    private final Map<String, Unit> loaded = new HashMap<>();
    /** Модули, которые разбираются прямо сейчас, — по ним и виден круг. */
    private final Deque<String> loading = new ArrayDeque<>();

    public ModuleUnits(ModuleSource source) {
        this.source = Objects.requireNonNull(source, "source");
    }

    /**
     * Итог загрузки.
     *
     * @param unit    разобранный модуль или {@code null}
     * @param problem готовое сообщение, почему модуля нет; {@code null} вместе
     *                с {@code unit == null} означает круг — это не ошибка
     */
    public record Loaded(Unit unit, String problem) {

        public boolean ok() {
            return unit != null;
        }
    }

    /** Разбирает модуль или отдаёт разобранный раньше. */
    public Loaded load(String key) {
        Unit ready = loaded.get(key);
        if (ready != null) {
            return new Loaded(ready, null);
        }
        if (loading.contains(key)) {
            return new Loaded(null, null);
        }

        Source moduleSource = source.find(key);
        if (moduleSource == null) {
            return new Loaded(null, "модуль '" + key + "' не найден");
        }

        loading.addLast(key);
        try {
            return parse(key, moduleSource);
        } finally {
            loading.removeLast();
        }
    }

    private Loaded parse(String key, Source moduleSource) {
        // Диагностика своя: сообщения об ошибках модуля должны показывать его строки,
        // а не строки того, кто его импортировал.
        Diagnostics diagnostics = new Diagnostics(moduleSource);
        List<Token> tokens = Lexer.tokenize(moduleSource, diagnostics);
        Program program = Parser.parseProgram(tokens, diagnostics);
        Resolution resolution = Resolution.none();
        if (!diagnostics.hasErrors()) {
            resolution = Resolver.resolve(program, diagnostics);
        }
        if (diagnostics.hasErrors()) {
            return new Loaded(null, "в модуле '" + key + "' есть ошибки:"
                    + System.lineSeparator() + diagnostics.renderAll());
        }

        Unit unit = new Unit(moduleSource, program, resolution, key);
        loaded.put(key, unit);
        return new Loaded(unit, null);
    }

}
