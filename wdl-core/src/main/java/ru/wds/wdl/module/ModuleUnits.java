package ru.wds.wdl.module;

import ru.wds.wdl.ast.Program;
import ru.wds.wdl.diagnostic.Diagnostics;
import ru.wds.wdl.lexer.Lexer;
import ru.wds.wdl.lexer.Token;
import ru.wds.wdl.metrics.Measure;
import ru.wds.wdl.metrics.Metrics;
import ru.wds.wdl.metrics.Stage;
import ru.wds.wdl.parser.Parser;
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
 * Здесь только разбор — {@code Lexer} → {@code Parser} — и ни одной
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
 * <p>
 * Из этого же следует и защита от потоков: {@link #load} синхронизирован целиком.
 * Разбор двумя потоками одновременно нарушил бы ровно то правило, ради которого
 * реестр и заведён, — а стоит эта строгость немного: разбирается модуль один раз
 * за запуск, дальше работает карта.
 */
public final class ModuleUnits {

    private final ModuleSource source;
    /**
     * Куда сообщается время разбора модулей.
     * <p>
     * Единственное место, где приёмник приходится передавать <b>внутрь</b> стадии:
     * везде остальном лексер и парсер меряет тот, кто их зовёт, а здесь их зовёт сам
     * движок в ответ на {@code import}, и снаружи туда не дотянуться.
     */
    private final Metrics metrics;
    private final Map<String, Unit> loaded = new HashMap<>();
    /** Модули, которые разбираются прямо сейчас, — по ним и виден круг. */
    private final Deque<String> loading = new ArrayDeque<>();

    public ModuleUnits(ModuleSource source) {
        this(source, Metrics.off());
    }

    /** Тот же реестр, но со включёнными метриками разбора модулей. */
    public ModuleUnits(ModuleSource source, Metrics metrics) {
        this.source = Objects.requireNonNull(source, "source");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    /**
     * Итог загрузки.
     *
     * @param unit    разобранный модуль или {@code null}
     * @param problem готовое сообщение, почему модуля нет; {@code null} вместе
     *                с {@code unit == null} означает круг — это не ошибка
     * @param missing источник такого модуля не знает — в отличие от «модуль есть,
     *                но в нём ошибки». Разница нужна тому, кто спрашивал: только
     *                к «не найден» уместно добавить, где ещё искали
     */
    public record Loaded(Unit unit, String problem, boolean missing) {

        public boolean ok() {
            return unit != null;
        }
    }

    /** Разбирает модуль или отдаёт разобранный раньше. */
    public synchronized Loaded load(String key) {
        Unit ready = loaded.get(key);
        if (ready != null) {
            return new Loaded(ready, null, false);
        }
        if (loading.contains(key)) {
            return new Loaded(null, null, false);
        }

        Source moduleSource = source.find(key);
        if (moduleSource == null) {
            return new Loaded(null, "модуль '" + key + "' не найден", true);
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
        List<Token> tokens;
        Measure lexing = metrics.begin(Stage.LEX, key, true);
        try {
            tokens = Lexer.tokenize(moduleSource, diagnostics);
        } finally {
            lexing.close();
        }
        Program program;
        Measure parsing = metrics.begin(Stage.PARSE, key, true);
        try {
            program = Parser.parseProgram(tokens, diagnostics);
        } finally {
            parsing.close();
        }
        if (diagnostics.hasErrors()) {
            return new Loaded(null, "в модуле '" + key + "' есть ошибки:"
                    + System.lineSeparator() + diagnostics.renderAll(), false);
        }

        Unit unit = new Unit(moduleSource, program, key);
        loaded.put(key, unit);
        return new Loaded(unit, null, false);
    }

}
