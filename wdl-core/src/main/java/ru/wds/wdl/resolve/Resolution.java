package ru.wds.wdl.resolve;

import ru.wds.wdl.ast.stmt.ClassDeclStmt;
import ru.wds.wdl.ast.stmt.Stmt;
import ru.wds.wdl.ast.stmt.TraitDeclStmt;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Что резолвер узнал о программе: форма каждого объявления класса и трейта.
 * <p>
 * Ключ — сам узел дерева, и сравнивается он по ссылке, а не по равенству: два
 * одинаковых с виду объявления в разных местах файла — это два разных класса.
 * <p>
 * Результат неизменяем и не зависит от запуска: одно и то же дерево можно выполнять
 * в нескольких потоках с разными областями видимости, а формы у них будут общие.
 * Значения ({@code WdlClass}) при этом у каждого запуска свои — форма знает имена
 * и порядок, значение знает замыкание и данные.
 */
public final class Resolution {

    private static final Resolution NONE = new Resolution(new IdentityHashMap<>());

    private final Map<Stmt, Shape> shapes;

    Resolution(Map<Stmt, Shape> shapes) {
        this.shapes = shapes;
    }

    /**
     * Пустой результат — для программ без классов.
     * <p>
     * Нужен затем, чтобы {@code Interpreter.run(program, context)} остался рабочей
     * точкой входа для скриптов, которым резолвер не нужен: встретив объявление
     * класса без формы, интерпретатор скажет об этом внятно.
     */
    public static Resolution none() {
        return NONE;
    }

    /** Форма класса или {@code null}, если программа не проходила резолвер. */
    public ClassShape classShape(ClassDeclStmt declaration) {
        return shapes.get(declaration) instanceof ClassShape shape ? shape : null;
    }

    /** Форма трейта или {@code null}. */
    public TraitShape traitShape(TraitDeclStmt declaration) {
        return shapes.get(declaration) instanceof TraitShape shape ? shape : null;
    }

    public boolean isEmpty() {
        return shapes.isEmpty();
    }
}
