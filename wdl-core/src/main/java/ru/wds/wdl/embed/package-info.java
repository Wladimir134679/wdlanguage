/**
 * Встраивание: как приложение добавляет в язык свои классы и функции.
 * <p>
 * Ядро уже умеет считать классы значениями, поэтому ничего особенного здесь нет —
 * {@link ru.wds.wdl.embed.NativeClass} это вторая реализация того же
 * {@link ru.wds.wdl.value.ClassValue}, что и класс, написанный на wdl. Интерпретатор
 * их не различает: {@code new File("a.txt")} и {@code new Point(1, 2)} проходят
 * одним и тем же кодом.
 * <p>
 * Что нужно знать, чтобы написать библиотеку:
 * <ul>
 *   <li>функция — {@link ru.wds.wdl.runtime.BuiltinFunction}, лямбда с именем и арностью;</li>
 *   <li>класс — {@link ru.wds.wdl.embed.NativeClass} с построителем: поля заголовка,
 *       методы, фабрики, константы;</li>
 *   <li>состояние, которое не выражается значениями языка, — в
 *       {@link ru.wds.wdl.embed.NativeInstance#state()};</li>
 *   <li>всё вместе — {@link ru.wds.wdl.embed.Library}, кладущая имена в область видимости.</li>
 * </ul>
 * Пример живой библиотеки — {@code ru.wds.wdl.stdlib.Std} в модуле {@code wdl-stdlib}.
 */
package ru.wds.wdl.embed;
