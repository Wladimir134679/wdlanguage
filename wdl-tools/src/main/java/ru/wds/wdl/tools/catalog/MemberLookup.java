package ru.wds.wdl.tools.catalog;

import java.util.List;
import java.util.Objects;

/** Единая точка доступа к членам известного получателя. */
public final class MemberLookup {

    private final Catalog catalog;

    private MemberLookup(Catalog catalog) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    public static MemberLookup of(Catalog catalog) {
        return new MemberLookup(catalog);
    }

    /**
     * Только точные члены. Для {@link ReceiverType.Unknown} и объединения ответ
     * пуст: динамический язык не должен рекламировать случайно выбранный API.
     */
    public List<MemberDescriptor> members(ReceiverType receiver) {
        Objects.requireNonNull(receiver, "receiver");
        return switch (receiver) {
            case ReceiverType.Builtin builtin -> catalog.members(builtin.valueType());
            case ReceiverType.Class type -> type.access() == ReceiverType.ClassAccess.STATIC
                    ? type.descriptor().staticMembers() : type.descriptor().members();
            case ReceiverType.Trait trait -> trait.descriptor().members();
            case ReceiverType.Unknown ignored -> List.of();
            case ReceiverType.Function ignored -> List.of();
            case ReceiverType.Module ignored -> List.of();
            case ReceiverType.Union ignored -> List.of();
            case ReceiverType.Error ignored -> List.of();
        };
    }

    /** Имя из модуля остаётся {@link SymbolDescriptor}: это класс, функция или константа. */
    public List<SymbolDescriptor> names(ReceiverType.Module module) {
        Objects.requireNonNull(module, "module");
        return module.descriptor().names();
    }
}
