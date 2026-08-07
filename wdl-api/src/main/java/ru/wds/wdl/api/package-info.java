/**
 * Публичный фасад для встраивания wdl в Java-приложение.
 * <p>
 * Целевая форма использования:
 * <pre>{@code
 * WdlEngine engine = WdlEngine.builder()
 *         .stdlib(Stdlib.SAFE)
 *         .module("game", gameApi)
 *         .maxSteps(1_000_000)
 *         .timeout(Duration.ofSeconds(5))
 *         .output(logger::info)
 *         .build();
 *
 * WdlScript script = engine.compile(Source.ofFile(path));   // разбор один раз
 *
 * for (Player p : players) {
 *     WdlInstance instance = script.newInstance();          // своё состояние на каждого
 *     instance.bind("player", p);
 *     instance.run();
 * }
 * }</pre>
 * <p>
 * Разделение {@code Script} (иммутабельный, разделяемый между потоками) и
 * {@code Instance} (изменяемое состояние, один поток) — центральное решение всего
 * проекта. Оно же делает обязательным запрет на изменяемую статику в ядре.
 */
package ru.wds.wdl.api;
