package ru.wds.wdl.runtime;

import ru.wds.wdl.ast.Node;
import ru.wds.wdl.ast.Nodes;
import ru.wds.wdl.ast.Program;
import ru.wds.wdl.ast.expr.Expr;
import ru.wds.wdl.ast.expr.VariableExpr;
import ru.wds.wdl.ast.stmt.ClassDeclStmt;
import ru.wds.wdl.ast.stmt.ConstDeclStmt;
import ru.wds.wdl.ast.stmt.DefDeclStmt;
import ru.wds.wdl.ast.stmt.Stmt;
import ru.wds.wdl.ast.stmt.TraitDeclStmt;
import ru.wds.wdl.module.Unit;
import ru.wds.wdl.source.Source;
import ru.wds.wdl.source.Span;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Где в файле объявлено имя, которого не нашлось в области видимости.
 * <p>
 * <b>Это диагностика, а не стадия выполнения.</b> Обход дерева случается только тогда,
 * когда имя уже не нашлось и ошибка всё равно бросается: на удачном пути этого кода
 * нет вовсе. Раньше здесь работал резолвер — отдельный проход перед первой инструкцией,
 * — и платили за него все скрипты, включая те, где ни одного класса нет. Теперь за него
 * платит только тот, кто уже ошибся, и цена не имеет значения.
 * <p>
 * Отвечает он на один вопрос: «объявление с таким именем в этом файле есть, просто
 * стоит не там». Правило языка — <b>имя существует с той строки, где его завели</b>,
 * — самое частое столкновение с ним выглядит как «переменная не определена» посреди
 * файла, где это имя явно написано. Сказать «объявлена ниже, на строке 42» и значит
 * назвать причину вместо симптома.
 */
final class Declarations {

    /** Имя скрытого поля свойства: см. {@link FieldScope}. */
    private static final String FIELD = "field";

    /**
     * Про {@code field} сказать есть что и без дерева: имени этого нигде не объявляют,
     * его заводит область аксессора — и только у свойства со скрытым полем.
     * <p>
     * Последней проверкой, а не первой: если в файле {@code field} и правда объявлен
     * ниже, точный ответ «объявление стоит там-то» полезнее общего рассказа
     * про свойства.
     */
    private static String field(String name) {
        return FIELD.equals(name)
                ? ": 'field' — это скрытое поле свойства, и оно есть только внутри "
                + "'def get()' и 'def set(value)' свойства, объявленного с начальным "
                + "значением ('property x = 0 { ... }')"
                : "";
    }

    private Declarations() {
    }

    /**
     * Подсказка про ненайденное имя или пустая строка, если сказать нечего.
     * <p>
     * Пустую строку возвращает и юнит-заглушка ({@code eval} строки, тест на голом
     * дереве): дерева файла у него нет, искать негде.
     */
    static String hint(String name, ExecutionContext context) {
        Unit unit = context.unit();
        if (unit == null || unit.program() == null) {
            return field(name);
        }
        Found top = findIn(unit.program().statements(), name, false);
        if (top != null) {
            return ": объявление стоит ниже" + at(top.span(), unit.source())
                    + ", а имя существует с той строки, где его завели";
        }
        Found nested = findIn(unit.program().statements(), name, true);
        if (nested != null) {
            return ": " + nested.what() + " с таким именем объявлен" + nested.ending()
                    + " внутри вложенной области" + at(nested.span(), unit.source())
                    + " — снаружи такого имени нет";
        }
        return field(name);
    }

    /**
     * То же для ссылки на тип в заголовке класса — с одной добавкой, ради которой
     * метод и отдельный.
     * <p>
     * Круг в наследовании ({@code class A : B}, {@code class B : A}) выглядит так же,
     * как обычная перестановка: «неизвестный класс 'B'». Разница в том, что совета
     * «объявите выше» здесь не существует — такого порядка нет. Это и надо сказать,
     * иначе человек будет двигать объявления по файлу и получать ту же ошибку зеркально.
     */
    static String typeHint(String name, ExecutionContext context) {
        Unit unit = context.unit();
        if (unit == null || unit.program() == null) {
            return "";
        }
        List<String> cycle = inheritanceCycle(unit.program(), name);
        if (!cycle.isEmpty()) {
            return ": " + String.join(" и ", cycle) + " наследуют друг друга по кругу,"
                    + " поэтому нужного порядка объявлений не существует";
        }
        return hint(name, context);
    }

    /** Место объявления: {@code ", на строке 42"} или пусто, если исходника нет. */
    private static String at(Span span, Source source) {
        if (source == null || span == null || span.isNone()) {
            return "";
        }
        return ", на строке " + source.positionOf(span.start()).line();
    }

    private record Found(String what, String ending, Span span) {
    }

    /**
     * Ищет объявление имени: либо только среди инструкций уровня, либо только
     * во вложенных областях. Два прохода, а не один, потому что ответы разные:
     * «стоит ниже» и «стоит внутри» — это две разные ошибки автора.
     */
    private static Found findIn(List<Stmt> statements, String name, boolean nested) {
        for (Stmt statement : statements) {
            if (!nested) {
                Found here = declarationOf(statement, name);
                if (here != null) {
                    return here;
                }
                continue;
            }
            List<Stmt> nestedStatements = nested(statement);
            Found inside = findIn(nestedStatements, name, false);
            if (inside != null) {
                return inside;
            }
            Found deeper = findIn(nestedStatements, name, true);
            if (deeper != null) {
                return deeper;
            }
        }
        return null;
    }

    /** Объявляет ли инструкция это имя — и что именно она объявляет. */
    private static Found declarationOf(Stmt statement, String name) {
        return switch (statement) {
            case DefDeclStmt def when def.name().equals(name) ->
                    new Found("функция", "а", def.span());
            case ClassDeclStmt klass when klass.name().equals(name) ->
                    new Found("класс", "", klass.nameSpan());
            case TraitDeclStmt trait when trait.name().equals(name) ->
                    new Found("трейт", "", trait.span());
            case ConstDeclStmt constant when constant.name().equals(name) ->
                    new Found("константа", "а", constant.nameSpan());
            default -> null;
        };
    }

    /**
     * Инструкции на одну область глубже — те, внутри которых объявление могло бы
     * спрятаться. Тело функции сюда входит: {@code def} внутри {@code def} снаружи
     * не виден, и это самая частая причина «имя определено, но не находится».
     * <p>
     * Состав детей берётся у {@link Nodes}, а не перечисляется здесь заново. Раньше
     * перечислялся — и список был намеренно неполон: для подсказки хватало девяти
     * видов инструкций, а про тело анонимной функции и ветку {@code match} никто
     * не вспоминал. Общий обход снимает вопрос: новый вид узла попадает сюда сам.
     * <p>
     * Не-инструкции разворачиваются вглубь: между {@code def} и телом стоит
     * {@link ru.wds.wdl.ast.expr.FunctionExpr}, а между {@code match} и веткой —
     * фрагмент, и обрывать спуск на них значило бы потерять как раз те области,
     * ради которых всё это и считается.
     */
    private static List<Stmt> nested(Node node) {
        List<Stmt> found = new ArrayList<>(4);
        for (Node child : Nodes.children(node)) {
            if (child instanceof Stmt statement) {
                found.add(statement);
            } else {
                found.addAll(nested(child));
            }
        }
        return found;
    }

    /**
     * Классы файла, замкнутые в кольцо наследования и достижимые от {@code name},
     * — или пустой список, если кольца нет.
     * <p>
     * Смотрит только на простые имена: по {@code m.Shape} или {@code registry["Shape"]}
     * о зависимостях из текста ничего не видно, и гадать здесь не надо — сообщение
     * без подсказки честнее подсказки наугад.
     */
    private static List<String> inheritanceCycle(Program program, String name) {
        Set<String> seen = new LinkedHashSet<>();
        String current = name;
        while (current != null && seen.add(current)) {
            current = parentNameOf(program, current);
        }
        // Вернулись к тому, с чего начали, — кольцо замкнулось на самом искомом классе.
        return current != null && current.equals(name) ? List.copyOf(seen) : List.of();
    }

    /** Имя родителя класса, если и класс, и родитель записаны простыми именами. */
    private static String parentNameOf(Program program, String name) {
        for (Stmt statement : program.statements()) {
            if (statement instanceof ClassDeclStmt klass && klass.name().equals(name)
                    && klass.hasParent()) {
                Expr type = klass.parent().type();
                return type instanceof VariableExpr variable ? variable.name() : null;
            }
        }
        return null;
    }
}
