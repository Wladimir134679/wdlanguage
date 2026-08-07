package ru.wds.wdl.ast.expr;

import ru.wds.wdl.ast.stmt.Stmt;
import ru.wds.wdl.source.Span;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Функция как выражение: {@code fun(a, b) => a + b}.
 * <p>
 * Узел один и на анонимную функцию, и на объявление — {@code fun имя(...)} это
 * {@link ru.wds.wdl.ast.stmt.FunDeclStmt} с этим самым узлом внутри. Логика создания
 * значения-функции остаётся в одном месте, а разница между двумя формами ровно та,
 * какая есть на самом деле: объявление ещё и заводит имя.
 * <p>
 * <b>Имя хранится и у анонимной, если его удалось узнать.</b> У {@code f = fun(a) => a}
 * узел анонимный, но сообщение «функция 'f' принимает ровно 1 аргумент» полезнее, чем
 * «функция 'fun' ...», поэтому имя цели простого присваивания подставляется при разборе.
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
 * @param span   место в исходнике: от {@code fun} до конца тела
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
     * Параметр функции.
     * <p>
     * Отдельный тип, а не просто строка, из-за места в исходнике: сообщение о дубликате
     * должно указывать на второе вхождение, а не на всю функцию. Здесь же появится
     * значение по умолчанию ({@code fun f(a, b = 10)}), когда до него дойдёт дело.
     *
     * @param name имя параметра
     * @param span место имени в исходнике
     */
    public record Param(String name, Span span) {

        public Param {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(span, "span");
        }

        @Override
        public String toString() {
            return name;
        }
    }

    /** Имя для сообщений: у анонимной — просто {@code fun}. */
    public String title() {
        return name != null ? name : "fun";
    }

    @Override
    public String toString() {
        return "fun " + (name != null ? name : "")
                + params.stream().map(Param::name).collect(Collectors.joining(", ", "(", ")"));
    }
}
