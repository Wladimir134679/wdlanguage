package ru.wds.wdl.runtime;

import ru.wds.wdl.ast.stmt.PropertyDecl;
import ru.wds.wdl.module.Unit;
import ru.wds.wdl.source.Span;
import ru.wds.wdl.value.CallContext;
import ru.wds.wdl.value.Property;
import ru.wds.wdl.value.Value;
import ru.wds.wdl.value.types.InstanceObjectValue;

import java.util.List;
import java.util.Objects;

/**
 * Свойство, написанное на wdl: объявление плюс то, что нужно вызвать его аксессоры.
 * <p>
 * Одна запись на класс, а не своя копия у каждого экземпляра — по той же причине,
 * что у {@link Method}: у объектов разное состояние, а не разное поведение.
 * Экземпляр появляется в момент обращения и приходит аргументом.
 * <p>
 * <b>Отдельного вида значения аксессору не нужно.</b> Getter и setter — обычные
 * {@link UserFunction} с {@link InstanceScope} в замыкании, ровно как связанный метод:
 * объект помнится замыканием, а {@code return}, ограничение глубины и трассировка
 * достаются готовыми. Поэтому и бесконечная рекурсия {@code property x { def get() =>
 * this.x }} останавливается сама — тем же счётчиком глубины, что ловит рекурсивную
 * функцию.
 *
 * @param closure   область, где объявлен <b>тип с этим свойством</b>: у свойства,
 *                  доставшегося от родителя, это область родителя
 * @param unit      файл, где свойство написано, — по той же причине, что у метода
 * @param superFrom класс, чей родитель служит стартом для {@code super} внутри
 *                  аксессора; у свойства из трейта — {@code null}
 */
record WdlProperty(PropertyDecl declaration, Environment closure, Unit unit,
                   WdlClass superFrom, Run run, Interpreter interpreter) implements Property {

    WdlProperty {
        Objects.requireNonNull(declaration, "declaration");
        Objects.requireNonNull(closure, "closure");
        Objects.requireNonNull(unit, "unit");
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(interpreter, "interpreter");
    }

    @Override
    public String name() {
        return declaration.name();
    }

    @Override
    public boolean readable() {
        return accessorOf(declaration.getter()) != null;
    }

    @Override
    public boolean writable() {
        return accessorOf(declaration.setter()) != null;
    }

    @Override
    public Value read(InstanceObjectValue instance, CallContext context, Span span) {
        return bind(instance, declaration.getter()).call(context, List.of(), span);
    }

    @Override
    public void write(InstanceObjectValue instance, Value value, CallContext context, Span span) {
        bind(instance, declaration.setter()).call(context, List.of(value), span);
    }

    /**
     * Связывает аксессор с экземпляром.
     * <p>
     * Слоёв области здесь на один больше, чем у метода: поверх {@link InstanceScope}
     * встаёт {@link FieldScope}, и только он добавляет имя {@code field}. Свойству
     * без скрытого поля лишний слой не достаётся — {@code field} внутри такого
     * аксессора остаётся обычным именем и ищется снаружи, как и всякое незнакомое.
     */
    private ru.wds.wdl.value.FunctionValue bind(InstanceObjectValue container,
                                                PropertyDecl.Accessor accessor) {
        InstanceObjectValue self = container.identity();
        Environment scope = new InstanceScope(self, (WdlClass) self.owner(),
                new Method(accessor.function(), closure, unit, superFrom));
        if (declaration.hasBackingField()) {
            scope = new FieldScope(self, declaration.name(), scope);
        }
        // Замка нет и не будет: 'synchronized' у аксессора запрещён разбором, потому
        // что свойство читают внутри выражения, где замок экземпляра всё равно ничего
        // не склеит. Кому нужна атомарность вокруг свойства — берёт её явно.
        return new UserFunction(accessor.function(), scope, unit, run, interpreter, null);
    }

    /** Аксессор с телом или {@code null}: требование трейта телом не является. */
    private static PropertyDecl.Accessor accessorOf(PropertyDecl.Accessor accessor) {
        return accessor == null || accessor.isRequirement() ? null : accessor;
    }
}
