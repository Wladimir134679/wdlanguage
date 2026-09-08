/**
 * Публичный фасад для встраивания wdl в Java-приложение.
 *
 * <h2>Три уровня, от короткого к полному</h2>
 * <pre>{@code
 * // 1. на один раз
 * Object answer = Wdl.eval("40 + 2");
 *
 * // 2. движок и скрипт
 * WdlEngine engine = WdlEngine.builder()
 *         .stdlib(Stdlib.STANDARD)
 *         .output(logger::info)
 *         .define("appName", "демо")
 *         .module("game", GameApi::library)
 *         .build();
 * Value result = engine.run(Path.of("script.wdl"));
 *
 * // 3. разбор один раз, запусков много
 * WdlScript script = engine.compile(Path.of("handler.wdl"));
 * for (Player player : players) {
 *     try (WdlInstance instance = script.instance()) {
 *         instance.define("player", player.name());
 *         instance.execute();
 *         game.everySecond(instance.function("onTick").asRunnable());
 *     }
 * }
 * }</pre>
 *
 * <h2>Script и Instance</h2>
 * Разделение {@link ru.wds.wdl.api.WdlScript} (неизменяемый, разделяемый между потоками)
 * и {@link ru.wds.wdl.api.WdlInstance} (изменяемое состояние, {@code AutoCloseable}) —
 * центральное решение всего проекта. Оно же делает обязательным запрет на изменяемую
 * статику в ядре: сколько создано экземпляров, столько независимых скриптов работает
 * в процессе, и общего у них нет ничего.
 * <p>
 * {@link ru.wds.wdl.api.WdlEngine} при этом — <b>рецепт, а не запуск</b>: он держит
 * состав (библиотеки, набор модулей, вывод), а не переменные, и потому неизменяем
 * и годится для любого числа потоков.
 *
 * <h2>Функция скрипта — обычный Java-объект</h2>
 * {@link ru.wds.wdl.api.WdlCallable} — то, ради чего остальное. Функцию можно достать
 * из скрипта, положить в поле, отдать в чужой фреймворк, позвать из другого потока:
 * она несёт с собой свою область видимости, свой файл и свой запуск, поэтому внутри
 * будет ровно то же, что при вызове из скрипта. Про механизм — {@code runtime.Run}.
 *
 * <h2>Что где</h2>
 * <table border="1">
 *   <caption>Состав пакета</caption>
 *   <tr><th>Класс</th><th>Зачем</th></tr>
 *   <tr><td>{@link ru.wds.wdl.api.Wdl}</td><td>одна строка на весь запуск</td></tr>
 *   <tr><td>{@link ru.wds.wdl.api.WdlEngine}</td><td>состав: что скрипту доступно</td></tr>
 *   <tr><td>{@link ru.wds.wdl.api.Stdlib}</td><td>пресет стандартной библиотеки</td></tr>
 *   <tr><td>{@link ru.wds.wdl.api.WdlScript}</td><td>разобранное дерево, общее</td></tr>
 *   <tr><td>{@link ru.wds.wdl.api.WdlInstance}</td><td>один запуск со своим состоянием</td></tr>
 *   <tr><td>{@link ru.wds.wdl.api.WdlCallable}</td><td>функция скрипта в руках приложения</td></tr>
 *   <tr><td>{@link ru.wds.wdl.api.Values}</td><td>перевод значений языка в Java и обратно</td></tr>
 *   <tr><td>{@link ru.wds.wdl.api.WdlException}</td><td>ошибка с местом в тексте скрипта</td></tr>
 *   <tr><td>{@link ru.wds.wdl.runtime.Limits}</td><td>пределы запуска: шаги, время, потоки</td></tr>
 * </table>
 *
 * <h2>Чего здесь пока нет</h2>
 * <ul>
 *   <li><b>Лимита памяти</b>: шаги, время и потоки движок считает ({@code Limits}),
 *       а байты — нет. Это учёт в каждом контейнере, отдельная работа.</li>
 *   <li><b>Моста к произвольному Java-объекту</b>: {@code player.getName()} без описания
 *       класса руками. Пока это {@code bridge.NativeClass}.</li>
 * </ul>
 */
package ru.wds.wdl.api;
