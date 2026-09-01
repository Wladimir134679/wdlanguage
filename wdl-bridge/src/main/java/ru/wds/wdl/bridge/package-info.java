/**
 * Встраивание: как приложение добавляет в язык свои классы, трейты и модули.
 * <p>
 * Ядро уже умеет считать классы значениями, поэтому ничего особенного здесь нет —
 * {@link ru.wds.wdl.bridge.NativeClass} это вторая реализация того же
 * {@link ru.wds.wdl.value.ClassValue}, что и класс, написанный на wdl. Интерпретатор
 * их не различает: {@code new File("a.txt")} и {@code new Point(1, 2)} проходят
 * одним и тем же кодом.
 * <p>
 * Что нужно знать, чтобы написать библиотеку:
 * <ul>
 *   <li>всё вместе — {@link ru.wds.wdl.bridge.Module}: типы, функции, константы,
 *       члены значений и то, что надо отпустить в конце запуска;</li>
 *   <li>функция — {@link ru.wds.wdl.runtime.BuiltinFunction}, лямбда с именем и арностью;</li>
 *   <li>класс — {@link ru.wds.wdl.bridge.NativeClass} с построителем: поля заголовка,
 *       методы, фабрики, константы;</li>
 *   <li>состояние, которое не выражается значениями языка, — в
 *       {@link ru.wds.wdl.bridge.NativeInstance#state()};</li>
 *   <li>методы, которые уже написаны в чужом типе, — оттуда и берутся:
 *       {@link ru.wds.wdl.bridge.MemberSource} и его реализация
 *       {@code reflect.FromJava}.</li>
 * </ul>
 * Рефлексия живёт в соседнем пакете {@code ru.wds.wdl.bridge.reflect} и здесь
 * не нужна: построитель описывает то, что приложение написало само.
 * <p>
 * Пример живой библиотеки — {@code ru.wds.wdl.stdlib.Std} в модуле {@code wdl-stdlib}.
 */
package ru.wds.wdl.bridge;
