package ru.wds.wdl.tools.analysis;

/**
 * Чем объявлено имя.
 * <p>
 * Вид берётся из <b>записи</b>, а не из значения: {@code f = def(x) => x} заводит
 * переменную, даже если в ней окажется функция. Анализ читает текст и не выполняет
 * его — узнать, что там на самом деле, он не может и не должен пытаться.
 * <p>
 * Список повторяет то, что в языке действительно заводит имя. Свериться легко:
 * каждому виду соответствует место в {@code Interpreter} или в связывании вызова,
 * где это имя кладут в область.
 */
public enum SymbolKind {

    /** Обычное присваивание, впервые встреченное в этой области: {@code price = 120}. */
    VARIABLE("переменная"),

    /** {@code const LIMIT = 10} — имя, которое нельзя переприсвоить. */
    CONSTANT("константа"),

    /** {@code def total(...)} на уровне файла или блока. */
    FUNCTION("функция"),

    /** Параметр функции, метода, фабрики или заголовка класса. */
    PARAMETER("параметр"),

    /** Остаток {@code *args} или {@code **named}. */
    REST("остаток"),

    CLASS("класс"),

    TRAIT("трейт"),

    /** Метод класса, трейта или расширения; конструктор — тоже он. */
    METHOD("метод"),

    /** Свойство: {@code property area => width * height}. */
    PROPERTY("свойство"),

    /** Фабрика: {@code def Rect.square(side)}. */
    FACTORY("фабрика"),

    /** Требование трейта — метод без тела. */
    REQUIREMENT("требование"),

    /** Имя модуля из {@code import lib.math as math}. */
    MODULE("модуль"),

    /** Переменная перебора: {@code for (item in cart)}. */
    LOOP_VARIABLE("переменная цикла"),

    /** Имя пойманной ошибки: {@code catch (e is IoError)}. */
    CATCH_VARIABLE("переменная 'catch'"),

    /** Ресурс: {@code use (file = io.open(path))}. */
    RESOURCE("ресурс"),

    /** Скрытое поле свойства — {@code field} внутри его аксессоров. */
    FIELD("скрытое поле");

    private final String title;

    SymbolKind(String title) {
        this.title = title;
    }

    /** Как назвать вид человеку — в подсказке и в сообщении. */
    public String title() {
        return title;
    }

    /** Зовётся ли такое имя как функция — вопрос, который задаёт дополнение. */
    public boolean isCallable() {
        return this == FUNCTION || this == METHOD || this == FACTORY
                || this == REQUIREMENT || this == CLASS;
    }
}
