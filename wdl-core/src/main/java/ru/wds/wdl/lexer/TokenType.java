package ru.wds.wdl.lexer;

/**
 * Вид токена.
 * <p>
 * Ключевые слова и операторы несут здесь же свою лексему — таблицы для лексера
 * строятся из этого перечисления, а не пишутся рядом вторым списком. Забыть добавить
 * оператор в таблицу невозможно: он либо объявлен здесь, либо не существует.
 */
public enum TokenType {

    // --- имена и литералы ---------------------------------------------------
    /** Идентификатор: имя переменной, функции, поля. */
    WORD(Kind.VALUE),
    /** Целое десятичное число. Текст токена — цифры без разделителей {@code _}. */
    INT(Kind.VALUE),
    /** Дробное число или число с экспонентой. */
    FLOAT(Kind.VALUE),
    /** Шестнадцатеричное число. Текст токена — цифры без префикса {@code 0x}. */
    HEX(Kind.VALUE),
    /** Двоичное число. Текст токена — цифры без префикса {@code 0b}. */
    BIN(Kind.VALUE),
    /** Строковый литерал. Текст токена — уже развёрнутое значение, без кавычек. */
    STRING(Kind.VALUE),

    // --- ключевые слова -----------------------------------------------------
    /** Имя, которое нельзя переприсвоить: {@code const LIMIT = 10}. */
    CONST("const"),
    FUN("fun"),
    RETURN("return"),
    IF("if"),
    ELSE("else"),
    WHILE("while"),
    DO("do"),
    FOR("for"),
    /** Перебор: {@code for (товар in корзина)}. Отдельного {@code foreach} в языке нет. */
    IN("in"),
    BREAK("break"),
    CONTINUE("continue"),
    /** Бросок ошибки: {@code throw new ParseError(text)}. */
    THROW("throw"),
    TRY("try"),
    /** Обработчик: {@code catch (e is IoError, ValueError)}. Тип после {@code is} — имя. */
    CATCH("catch"),
    /** Блок, который выполняется при любом выходе из {@code try}. */
    FINALLY("finally"),
    MATCH("match"),
    CASE("case"),
    CLASS("class"),
    /** Трейт: класс, у которого забрали конструктор. Заменяет и {@code interface}, и {@code abstract}. */
    TRAIT("trait"),
    NEW("new"),
    THIS("this"),
    /** Тот же экземпляр, но поиск методов начинается с родителя. */
    SUPER("super"),
    /** Подмешивание трейтов: {@code class Basket(items) with Printable, Counted}. */
    WITH("with"),
    /** Проверка класса или трейта: {@code figure is Circle}. Обычный бинарный оператор. */
    IS("is"),
    IMPORT("import"),
    AS("as"),
    TRUE("true"),
    FALSE("false"),
    NULL("null"),

    // --- скобки и знаки препинания ------------------------------------------
    LPAREN("("),
    RPAREN(")"),
    LBRACE("{"),
    RBRACE("}"),
    LBRACKET("["),
    RBRACKET("]"),
    COMMA(","),
    DOT("."),
    COLON(":"),
    QUESTION("?"),
    ARROW("->"),
    /** Тело-выражение функции: {@code fun f(a, b) => a + b}. Заменяет {@code return}. */
    FATARROW("=>"),
    /** Необязательный разделитель инструкций. Обязателен там, где его требует синтаксис: {@code for (i = 0; i < n; i++)}. */
    SEMICOLON(";"),

    // --- арифметика ---------------------------------------------------------
    PLUS("+"),
    MINUS("-"),
    STAR("*"),
    SLASH("/"),
    PERCENT("%"),
    PLUSPLUS("++"),
    MINUSMINUS("--"),

    // --- сравнение ----------------------------------------------------------
    EQ("=="),
    NOTEQ("!="),
    LT("<"),
    GT(">"),
    LTEQ("<="),
    GTEQ(">="),

    // --- логика -------------------------------------------------------------
    ANDAND("&&"),
    OROR("||"),
    NOT("!"),

    // --- биты ---------------------------------------------------------------
    AMP("&"),
    BAR("|"),
    CARET("^"),
    TILDE("~"),
    SHL("<<"),
    SHR(">>"),
    USHR(">>>"),

    // --- присваивания -------------------------------------------------------
    ASSIGN("="),
    PLUSASSIGN("+="),
    MINUSASSIGN("-="),
    STARASSIGN("*="),
    SLASHASSIGN("/="),
    PERCENTASSIGN("%="),
    AMPASSIGN("&="),
    BARASSIGN("|="),
    CARETASSIGN("^="),
    SHLASSIGN("<<="),
    SHRASSIGN(">>="),
    USHRASSIGN(">>>="),

    // --- служебные ----------------------------------------------------------
    /** Конец файла. Всегда последний токен потока. */
    EOF(Kind.SERVICE);

    private enum Kind { VALUE, KEYWORD, OPERATOR, SERVICE }

    private final Kind kind;
    private final String text;

    TokenType(Kind kind) {
        this.kind = kind;
        this.text = "";
    }

    /** Ключевое слово, если лексема состоит из букв, иначе оператор. */
    TokenType(String text) {
        this.kind = Character.isLetter(text.charAt(0)) ? Kind.KEYWORD : Kind.OPERATOR;
        this.text = text;
    }

    /** Лексема токена с фиксированным написанием; для имён и литералов — пустая строка. */
    public String text() {
        return text;
    }

    public boolean isKeyword() {
        return kind == Kind.KEYWORD;
    }

    public boolean isOperator() {
        return kind == Kind.OPERATOR;
    }

    /** Число любой системы счисления. */
    public boolean isNumber() {
        return this == INT || this == FLOAT || this == HEX || this == BIN;
    }

    /** Как назвать токен в сообщении об ошибке. */
    public String describe() {
        return text.isEmpty() ? name() : "'" + text + "'";
    }
}
