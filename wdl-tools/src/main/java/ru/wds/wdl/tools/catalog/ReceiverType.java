package ru.wds.wdl.tools.catalog;

import ru.wds.wdl.value.ValueType;

import java.util.List;
import java.util.Objects;

/**
 * Статически известная форма выражения слева от точки.
 *
 * <p>Это не система типов WDL и не LSP DTO. Она отвечает только на безопасный
 * вопрос инструмента: какие имена можно назвать точными, не исполняя скрипт.</p>
 */
public sealed interface ReceiverType permits ReceiverType.Unknown, ReceiverType.Builtin,
        ReceiverType.Class, ReceiverType.Trait, ReceiverType.Module, ReceiverType.Function,
        ReceiverType.Union, ReceiverType.Error {

    Confidence confidence();

    String source();

    enum Confidence {
        EXACT,
        INFERRED,
        UNKNOWN
    }

    /** Режим доступа к известному классу: экземпляр или сам объект класса. */
    enum ClassAccess {
        INSTANCE,
        STATIC
    }

    record Unknown(String source) implements ReceiverType {
        public Unknown {
            source = source == null ? "неизвестный получатель" : source;
        }

        @Override
        public Confidence confidence() {
            return Confidence.UNKNOWN;
        }
    }

    record Builtin(ValueType valueType, Confidence confidence, String source)
            implements ReceiverType {
        public Builtin {
            Objects.requireNonNull(valueType, "valueType");
            confidence = confidence == null ? Confidence.EXACT : confidence;
            source = source == null ? valueType.id() : source;
        }
    }

    record Class(SymbolDescriptor descriptor, ClassAccess access, Confidence confidence,
                 String source) implements ReceiverType {
        public Class {
            Objects.requireNonNull(descriptor, "descriptor");
            access = access == null ? ClassAccess.INSTANCE : access;
            confidence = confidence == null ? Confidence.EXACT : confidence;
            source = source == null ? descriptor.name() : source;
        }
    }

    record Trait(SymbolDescriptor descriptor, Confidence confidence, String source)
            implements ReceiverType {
        public Trait {
            Objects.requireNonNull(descriptor, "descriptor");
            confidence = confidence == null ? Confidence.EXACT : confidence;
            source = source == null ? descriptor.name() : source;
        }
    }

    record Module(ModuleDescriptor descriptor, Confidence confidence, String source)
            implements ReceiverType {
        public Module {
            Objects.requireNonNull(descriptor, "descriptor");
            confidence = confidence == null ? Confidence.EXACT : confidence;
            source = source == null ? descriptor.key() : source;
        }
    }

    record Function(SymbolDescriptor descriptor, Confidence confidence, String source)
            implements ReceiverType {
        public Function {
            Objects.requireNonNull(descriptor, "descriptor");
            confidence = confidence == null ? Confidence.EXACT : confidence;
            source = source == null ? descriptor.name() : source;
        }
    }

    /** Несколько совместимых форм; completion намеренно не объединяет их по умолчанию. */
    record Union(List<ReceiverType> alternatives, String source) implements ReceiverType {
        public Union {
            alternatives = alternatives == null ? List.of() : List.copyOf(alternatives);
            source = source == null ? "несколько получателей" : source;
        }

        @Override
        public Confidence confidence() {
            return alternatives.size() == 1 ? alternatives.getFirst().confidence()
                    : Confidence.UNKNOWN;
        }
    }

    record Error(String source) implements ReceiverType {
        public Error {
            source = source == null ? "ошибка разбора" : source;
        }

        @Override
        public Confidence confidence() {
            return Confidence.UNKNOWN;
        }
    }

    static Unknown unknown(String source) {
        return new Unknown(source);
    }
}
