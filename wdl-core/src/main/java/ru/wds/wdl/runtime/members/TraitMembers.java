package ru.wds.wdl.runtime.members;

import ru.wds.wdl.value.MemberSet;
import ru.wds.wdl.value.PropertyRequirement;
import ru.wds.wdl.value.Requirement;
import ru.wds.wdl.value.TraitValue;
import ru.wds.wdl.value.Value;
import ru.wds.wdl.value.types.StringValue;

import java.util.ArrayList;
import java.util.List;

/**
 * Члены трейта: имя, состав и — то, чего у класса нет, — список требований.
 * <p>
 * Требования собраны в один список намеренно, хотя внутри движка их три вида
 * (метод, поле, читаемое имя): скрипту важно «что класс обязан объявить», а чем
 * именно он это закроет — вопрос, на который отвечает уже сам класс. Разложить
 * список обратно по видам можно будет добавлением членов, а склеить три члена
 * в один потом — нельзя.
 */
public final class TraitMembers {

    private TraitMembers() {
    }

    public static MemberSet set() {
        return MemberSet.builder()
                .property("name", (receiver, context, span) -> StringValue.of(self(receiver).name()))
                .snapshot("annotations", (receiver, context, span) ->
                        Introspection.annotations(self(receiver).annotations()))
                .snapshot("methods", (receiver, context, span) ->
                        Introspection.names(self(receiver).methodNames()))
                .snapshot("properties", (receiver, context, span) ->
                        Introspection.names(self(receiver).propertyNames()))
                .snapshot("requirements", (receiver, context, span) -> {
                    TraitValue trait = self(receiver);
                    List<String> required = new ArrayList<>();
                    for (Requirement requirement : trait.requiredMethods()) {
                        required.add(requirement.name());
                    }
                    required.addAll(trait.requiredFields());
                    for (PropertyRequirement requirement : trait.requiredProperties()) {
                        required.add(requirement.name());
                    }
                    return Introspection.names(required);
                })
                .build();
    }

    private static TraitValue self(Value receiver) {
        return (TraitValue) receiver;
    }
}
