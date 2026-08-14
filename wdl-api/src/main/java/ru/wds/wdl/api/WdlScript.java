package ru.wds.wdl.api;

import ru.wds.wdl.module.ModuleSource;
import ru.wds.wdl.module.Unit;
import ru.wds.wdl.source.Source;
import ru.wds.wdl.value.Value;

import java.util.Objects;

/**
 * Разобранный скрипт: дерево, формы его классов и его исходник.
 * <p>
 * <b>Неизменяем и разделяем между потоками.</b> Это половина центрального решения
 * проекта; вторая — {@link WdlInstance}, где живёт состояние. Разбор стоит дорого
 * и делается один раз; выполнение стоит дёшево и делается сколько угодно раз,
 * каждый со своими переменными.
 * <pre>{@code
 * WdlScript script = engine.compile(Path.of("handler.wdl"));   // один раз
 * for (Player player : players) {
 *     try (WdlInstance instance = script.instance()) {         // на каждого свой
 *         instance.define("player", player.name());
 *         instance.execute();
 *     }
 * }
 * }</pre>
 * Именно ради этого у узлов дерева нет {@code eval()}: дерево — данные, и привязки
 * к одному выполнению в нём нет.
 *
 * <h2>Одна оговорка про классы</h2>
 * Класс связывается при выполнении своего объявления, поэтому у каждого экземпляра
 * получаются <b>свои</b> значения классов: родитель — это значение, а значения
 * у запусков разные. Внутри одного экземпляра всё как обычно, но объект из первого
 * на {@code is} из второго ответит {@code false}. Приложению, которое различает типы
 * между запусками, надо смотреть на имя класса, а не на тождество.
 */
public final class WdlScript {

    private final WdlEngine engine;
    private final Unit unit;
    private final ModuleSource sources;

    WdlScript(WdlEngine engine, Unit unit, ModuleSource sources) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.unit = Objects.requireNonNull(unit, "unit");
        this.sources = Objects.requireNonNull(sources, "sources");
    }

    /** Исходник — тот же, что разбирали: нужен для показа ошибок. */
    public Source source() {
        return unit.source();
    }

    /**
     * Новый запуск этого скрипта: своя область видимости, свои библиотеки, своё
     * состояние.
     * <p>
     * Экземпляр держит живое (соединения, клиентов), поэтому он {@link AutoCloseable}
     * и просит {@code try}-с-ресурсами. Забыть закрыть — значит утечь тем, что завели
     * его модули.
     */
    public WdlInstance instance() {
        return new WdlInstance(engine, unit, sources);
    }

    /**
     * Выполняет скрипт разом и закрывает запуск за собой.
     * <p>
     * Для случая «запустить и забыть». Всё, что скрипт объявил, при этом пропадает
     * вместе с запуском — нужно оно, значит нужен {@link #instance()}.
     *
     * @return значение последней инструкции-выражения файла
     */
    public Value run() {
        try (WdlInstance instance = instance()) {
            return instance.execute();
        }
    }
}
