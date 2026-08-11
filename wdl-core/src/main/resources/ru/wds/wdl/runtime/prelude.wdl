// Прелюдия: иерархия ошибок языка.
//
// Выполняется в корневой области каждого запуска — там же, где появляются println
// и typeof. Написана на wdl, а не собрана построителем нативных классов, по причине
// технической: весь смысл Exception в том, чтобы от него наследовались, а наследоваться
// от нативного класса скрипт пока не умеет (docs/embedding.md).
//
// Разбирается файл один раз при загрузке класса Prelude; дерево неизменяемо, поэтому
// статическое поле здесь не нарушает запрета на изменяемую статику в ядре. А вот формы
// классов принадлежат запуску: IndexError из одного запуска и IndexError из другого —
// разные классы для 'is'. Хосту, который различает ошибки между запусками, надо смотреть
// на имя класса, а не на тождество.

/**
 * Корень всего, что можно поймать: catch (e is Exception) ловит это и всех потомков,
 * catch (e) — то же самое, короче записанное.
 *
 * Четыре последних поля заполняет движок в момент броска: имя класса, место,
 * путь по скрипту и ошибки, задавленные при закрытии ресурсов. В заголовке они стоят
 * ради одного — чтобы у любой ошибки они были всегда, ещё до броска: иначе text()
 * у созданной, но не брошенной ошибки не нашла бы имени, которого нет.
 */
class Exception(message = "", cause = null, kind = "", at = "", trace = [], suppressed = []) {

    /** Короткая строка для лога: "IndexError: индекс 5 вне границ массива размером 3". */
    fun text() => kind == "" ? message : kind + ": " + message

    /** Всё, что об ошибке известно: текст, место, путь по скрипту и цепочка причин. */
    fun report() {
        out = text()
        if (at != "") out = out + " (" + at + ")";
        for (line in trace) out = out + "\n  " + line;
        for (other in suppressed) out = out + "\n  при закрытии: " + other.text();
        if (cause != null) out = out + "\n  причина: " + cause.report();
        return out;
    }
}

/** Любая ошибка движка: типы, имена, границы, арифметика. */
class RuntimeError(message = "", cause = null) : Exception(message, cause)

/** "a" - 1, for (x in 5), обращение к числу через точку, len(true). */
class TypeError(message = "", cause = null) : RuntimeError(message, cause)

/** Имени нет в области видимости. */
class NameError(message = "", cause = null) : RuntimeError(message, cause)

/** Индекс вне границ, нецелый индекс, запись в строку. */
class IndexError(message = "", cause = null) : RuntimeError(message, cause)

/** Деление на ноль, остаток от нуля. */
class ArithmeticError(message = "", cause = null) : RuntimeError(message, cause)

/** Значение недопустимо для операции: неверный путь, неразбираемый JSON. */
class ValueError(message = "", cause = null) : RuntimeError(message, cause)

/** Вызов не-функции, неверное число аргументов, new от не-класса. */
class CallError(message = "", cause = null) : RuntimeError(message, cause)

/** Присваивание константе, невыполненное требование трейта, new от трейта. */
class DeclarationError(message = "", cause = null) : RuntimeError(message, cause)

/** Модуль не найден, ошибка разбора модуля, упавшая фабрика библиотеки. */
class ImportError(message = "", cause = null) : RuntimeError(message, cause)
