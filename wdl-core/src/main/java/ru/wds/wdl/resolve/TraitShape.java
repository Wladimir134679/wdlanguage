package ru.wds.wdl.resolve;

import ru.wds.wdl.value.PropertyRequirement;
import ru.wds.wdl.value.Requirement;

import java.util.List;

/**
 * Форма трейта: имена, требования и порядок — без области видимости и данных.
 * <p>
 * Отличие от класса ровно одно, и оно определяет всё остальное: и поле, и метод
 * здесь бывают <b>требованием</b>. Объявленное со значением или с телом достаётся
 * классу; объявленное без — обязанность класса.
 * <p>
 * Реализаций две, и {@link Linker} их не различает: трейт, написанный на wdl
 * ({@link ScriptTraitShape}), и трейт, объявленный приложением
 * ({@link NativeTraitShape}). Требования проверяются одним кодом в одном месте —
 * иначе «забыл метод» звучало бы по-разному в зависимости от того, откуда взялся
 * трейт, а это разница, которой в языке нет.
 */
public sealed interface TraitShape extends Shape permits ScriptTraitShape, NativeTraitShape {

    /** Имена полей, которые класс обязан объявить сам. */
    List<String> requiredFields();

    /** Методы, которые класс обязан объявить сам, — с числом аргументов. */
    List<Requirement> requiredMethods();

    /**
     * Имена, которые класс обязан уметь читать или писать, — требования к свойствам.
     * <p>
     * Закрыть их можно чем угодно, что даёт нужную возможность: полем, свойством
     * с подходящими аксессорами. См. {@link PropertyRequirement}.
     */
    List<PropertyRequirement> requiredProperties();
}
