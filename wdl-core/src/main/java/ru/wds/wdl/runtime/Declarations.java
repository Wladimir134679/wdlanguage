package ru.wds.wdl.runtime;

import ru.wds.wdl.ast.Program;
import ru.wds.wdl.ast.expr.Expr;
import ru.wds.wdl.ast.expr.VariableExpr;
import ru.wds.wdl.ast.stmt.BlockStmt;
import ru.wds.wdl.ast.stmt.ClassDeclStmt;
import ru.wds.wdl.ast.stmt.ConstDeclStmt;
import ru.wds.wdl.ast.stmt.DefDeclStmt;
import ru.wds.wdl.ast.stmt.DeferStmt;
import ru.wds.wdl.ast.stmt.ForEachStmt;
import ru.wds.wdl.ast.stmt.ForStmt;
import ru.wds.wdl.ast.stmt.IfStmt;
import ru.wds.wdl.ast.stmt.Stmt;
import ru.wds.wdl.ast.stmt.TraitDeclStmt;
import ru.wds.wdl.ast.stmt.TryStmt;
import ru.wds.wdl.ast.stmt.UseStmt;
import ru.wds.wdl.ast.stmt.WhileStmt;
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
            return "";
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
        return "";
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
            Found inside = findIn(children(statement), name, false);
            if (inside != null) {
                return inside;
            }
            Found deeper = findIn(children(statement), name, true);
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
     * Вложенные инструкции — те, внутри которых объявление могло бы спрятаться.
     * Тело функции сюда входит: {@code def} внутри {@code def} снаружи не виден,
     * и это самая частая причина «имя определено, но не находится».
     */
    private static List<Stmt> children(Stmt statement) {
        List<Stmt> nested = new ArrayList<>(4);
        switch (statement) {
            case BlockStmt block -> nested.addAll(block.statements());
            case IfStmt branch -> {
                nested.add(branch.thenBranch());
                nested.add(branch.elseBranch());
            }
            case WhileStmt loop -> nested.add(loop.body());
            case ForStmt loop -> {
                nested.add(loop.init());
                nested.add(loop.step());
                nested.add(loop.body());
            }
            case ForEachStmt loop -> nested.add(loop.body());
            case TryStmt guarded -> {
                nested.add(guarded.body());
                guarded.handlers().forEach(handler -> nested.add(handler.body()));
                nested.add(guarded.finallyBlock());
            }
            case DeferStmt deferred -> nested.add(deferred.body());
            case UseStmt use -> nested.add(use.body());
            case DefDeclStmt def -> nested.add(def.function().body());
            default -> {
            }
        }
        nested.removeIf(java.util.Objects::isNull);
        return nested;
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
