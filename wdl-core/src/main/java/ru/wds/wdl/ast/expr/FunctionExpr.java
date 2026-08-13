package ru.wds.wdl.ast.expr;

import ru.wds.wdl.ast.stmt.Stmt;
import ru.wds.wdl.source.Span;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Функция как выражение: {@code def(a, b) => a + b}.
 * <p>
 * Узел один и на анонимную функцию, и на объявление — {@code def имя(...)} это
 * {@link ru.wds.wdl.ast.stmt.DefDeclStmt} с этим самым узлом внутри. Логика создания
 * значения-функции остаётся в одном месте, а разница между двумя формами ровно та,
 * какая есть на самом деле: объявление ещё и заводит имя.
 * <p>
 * <b>Имя хранится и у анонимной, если его удалось узнать.</b> У {@code f = def(a) => a}
 * узел анонимный, но сообщение «функция 'f' принимает ровно 1 аргумент» полезнее, чем
 * «функция 'def' ...», поэтому имя цели простого присваивания подставляется при разборе.
 * Это только для диагностики: на поиск имени во время выполнения оно не влияет.
 * <p>
 * Тело — {@link Stmt}, и да, это делает зависимость пакетов взаимной: инструкции
 * ссылаются на выражения, а функция-выражение — на инструкции. Иначе в языке
 * с функциональными литералами не бывает: тело функции состоит из инструкций,
 * а сама функция — значение.
 *
 * @param name   имя для диагностики или {@code null}, если узнать его неоткуда
 * @param params параметры в порядке записи
 * @param body   тело: блок, одиночная инструкция или {@code return} из стрелки
 * @param style  как тело было записано — {@link BodyStyle}
 * @param span   место в исходнике: от {@code def} до конца тела
 */
public record FunctionExpr(String name, List<Param> params, Stmt body, BodyStyle style, Span span)
        implements Expr {

    public FunctionExpr {
        params = List.copyOf(Objects.requireNonNull(params, "params"));
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(style, "style");
        Objects.requireNonNull(span, "span");
    }

    /**
     * Параметр функции — имя и, если оно есть, значение по умолчанию.
     * <p>
     * Отдельный тип, а не просто строка, из-за места в исходнике: сообщение о дубликате
     * должно указывать на второе вхождение, а не на всю функцию.
     * <p>
     * <b>Значение по умолчанию хранится деревом, а не вычисленным значением.</b> Считается
     * оно при каждом вызове ({@code runtime.UserFunction}), и это отличие от Python
     * намеренное: там дефолт вычисляется один раз при объявлении и живёт с функцией,
     * отчего общий изменяемый список копит значения между вызовами. Массивы и объекты
     * здесь тоже изменяемые, так что снимок значения принёс бы ту же ловушку — да ещё
     * и в виде изменяемого состояния внутри узла дерева, который обязан оставаться данными.
     *
     * @param name         имя параметра
     * @param defaultValue значение по умолчанию или {@code null}, если параметр обязателен
     * @param span         место имени в исходнике
     */
    public record Param(String name, Expr defaultValue, Span span) {

        public Param {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(span, "span");
        }

        /** Обязательный параметр — без значения по умолчанию. */
        public Param(String name, Span span) {
            this(name, null, span);
        }

        public boolean hasDefault() {
            return defaultValue != null;
        }

        @Override
        public String toString() {
            return defaultValue != null ? name + " = " + defaultValue : name;
        }
    }

    /** Имя для сообщений: у анонимной — просто {@code def}. */
    public String title() {
        return name != null ? name : "def";
    }

    @Override
    public String toString() {
        return "def " + (name != null ? name : "")
                + params.stream().map(Param::toString).collect(Collectors.joining(", ", "(", ")"));
    }
}
