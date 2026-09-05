package ru.wds.wdl.ast.stmt;

import ru.wds.wdl.ast.Fragment;
import ru.wds.wdl.source.Span;

import java.util.List;
import java.util.Objects;

/**
 * {@code try} с обработчиками и завершающим блоком.
 * <p>
 * Тело и все блоки здесь — именно блоки, а не одиночные инструкции, и причина
 * та же, что у тела метода: следующей строкой идёт {@code catch}, и без скобок
 * границу пришлось бы угадывать.
 * <p>
 * Обработчиков может быть сколько угодно, включая ноль: {@code try { } finally { }}
 * без единого {@code catch} — законная и частая форма. Порядок обработчиков значим —
 * берётся первый подходящий, сверху вниз. Недостижимый обработчик ошибкой не считается:
 * правило «первый подходящий» читается однозначно и без анализа достижимости, а сказать
 * о нём — работа линтера.
 *
 * @param handlers     обработчики в порядке записи
 * @param finallyBlock блок {@code finally} или {@code null}
 */
public record TryStmt(BlockStmt body, List<Catch> handlers, BlockStmt finallyBlock, Span span)
        implements Stmt {

    public TryStmt {
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(span, "span");
        handlers = List.copyOf(handlers);
    }

    public boolean hasFinally() {
        return finallyBlock != null;
    }

    /**
     * Один обработчик: {@code catch (e is IoError, ValueError) { ... }}.
     * <p>
     * Имя живёт только в теле обработчика — как параметр функции: {@code e} из одного
     * {@code catch} не виден в другом.
     *
     * @param types типы через запятую; пустой список — {@code catch (e)}, то есть
     *              «любая ошибка», то же самое, что {@code catch (e is Exception)}
     */
    public record Catch(String name, Span nameSpan, List<TypeRef> types, BlockStmt body, Span span)
            implements Fragment {

        public Catch {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(body, "body");
            types = List.copyOf(types);
        }

        /** {@code catch (e)} без типа: ловит всё, что вообще ловится. */
        public boolean catchesEverything() {
            return types.isEmpty();
        }
    }

    /**
     * Имя класса или трейта после {@code is} — простое или через имя импорта.
     * <p>
     * Именно имя, а не выражение, — как после {@code :} и {@code with} в объявлении
     * класса и по той же причине: тип обработчика должен быть виден глазами.
     */
    public record TypeRef(String alias, String name, Span span) implements Fragment {

        public TypeRef {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(span, "span");
        }

        /** Имя так, как оно написано в тексте. */
        public String title() {
            return alias == null ? name : alias + "." + name;
        }
    }
}
