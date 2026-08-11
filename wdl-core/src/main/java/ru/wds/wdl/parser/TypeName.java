package ru.wds.wdl.parser;

import ru.wds.wdl.source.Span;

/**
 * Имя типа так, как оно написано в заголовке: {@code Shape} или {@code m.Shape},
 * где {@code m} — имя именованного импорта.
 * <p>
 * Промежуточная форма, в дерево не попадает: из неё собираются
 * {@link ru.wds.wdl.ast.stmt.ClassDeclStmt.Superclass},
 * {@link ru.wds.wdl.ast.stmt.ClassDeclStmt.TraitRef} и
 * {@link ru.wds.wdl.ast.stmt.TryStmt.TypeRef} — три записи с одинаковой парой полей
 * и разной судьбой дальше по конвейеру.
 */
record TypeName(String alias, String name, Span span) {

    String title() {
        return alias == null ? name : alias + "." + name;
    }
}
