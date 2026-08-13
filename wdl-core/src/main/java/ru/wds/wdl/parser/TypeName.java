package ru.wds.wdl.parser;

import ru.wds.wdl.source.Span;

/**
 * Имя типа так, как оно написано в обработчике {@code catch}: {@code Shape}
 * или {@code m.Shape}, где {@code m} — имя именованного импорта.
 * <p>
 * Промежуточная форма, в дерево не попадает: из неё собирается
 * {@link ru.wds.wdl.ast.stmt.TryStmt.TypeRef}. В заголовке класса вместо имени
 * стоит выражение, и {@link ru.wds.wdl.ast.stmt.ClassDeclStmt.Superclass}
 * с {@link ru.wds.wdl.ast.stmt.ClassDeclStmt.TraitRef} держат его целиком.
 */
record TypeName(String alias, String name, Span span) {

    String title() {
        return alias == null ? name : alias + "." + name;
    }
}
