package ru.wds.wdl.runtime;

import ru.wds.wdl.value.Value;

/**
 * Чем кончилось выполнение файла: значение и область, в которой файл его получил.
 * <p>
 * Двух вещей, а не одной, потому что приложению нужны обе, и вторая — не меньше первой.
 * Файл выполняется <b>в своей области</b> поверх корневой (иначе модуль видел бы имена
 * того, кто его импортирует), и без этой ссылки всё, что скрипт объявил, оставалось бы
 * недостижимым: функцию-обработчик из него было бы не достать.
 * <pre>{@code
 * Execution done = new Interpreter().run(unit, context);
 * Value answer = done.value();                     // результат файла
 * Value handler = done.scope().lookup("onMessage"); // то, что он объявил
 * }</pre>
 *
 * @param value значение последней инструкции-выражения файла или {@code null}-значение
 * @param scope область файла: его переменные, функции, классы и модули
 */
public record Execution(Value value, ExecutionContext scope) {
}
