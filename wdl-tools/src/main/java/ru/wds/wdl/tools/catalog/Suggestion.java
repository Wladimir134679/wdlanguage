package ru.wds.wdl.tools.catalog;

import ru.wds.wdl.tools.analysis.Symbol;
import ru.wds.wdl.tools.analysis.SymbolKind;

import java.util.Objects;

/**
 * Один ответ редактору: имя, которое можно предложить в этой точке или показать
 * по наведению.
 * <p>
 * Один тип на два источника — имя из файла и имя из каталога, — потому что редактору
 * они нужны в одном списке и различаются для него только значком
 * {@linkplain #origin() происхождения}. Разводить их по двум типам значило бы
 * заставить каждого потребителя складывать списки самому.
 * <p>
 * {@link #symbol()} есть только у имени из файла: у {@code println} нет места
 * в тексте, и переходить по нему некуда. Именно это поле и отвечает на вопрос
 * «есть ли куда перейти», а не {@code origin}.
 *
 * @param name          имя
 * @param kind          чем объявлено
 * @param signature     краткая запись для подсказки
 * @param documentation описание или {@code null}
 * @param origin        откуда имя: файл, язык, библиотека, модуль, приложение
 * @param symbol        объявление в файле или {@code null} для имени извне
 */
public record Suggestion(String name, SymbolKind kind, String signature, String documentation,
                         Origin origin, Symbol symbol) {

    public Suggestion {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(origin, "origin");
        signature = signature == null ? name : signature;
    }

    /** Имя, объявленное в файле: сигнатуру и описание собирает сам символ. */
    public static Suggestion of(Symbol symbol) {
        Objects.requireNonNull(symbol, "symbol");
        return new Suggestion(symbol.name(), symbol.kind(), symbol.signature(),
                symbol.documentation(), Origin.FILE, symbol);
    }

    /** Имя из каталога: места в тексте у него нет. */
    public static Suggestion of(SymbolDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        return new Suggestion(descriptor.name(), descriptor.kind(), descriptor.signature(),
                descriptor.documentation(), descriptor.origin(), null);
    }

    /** Есть ли куда перейти по этому имени. */
    public boolean isDeclaredHere() {
        return symbol != null;
    }

    public boolean hasDocumentation() {
        return documentation != null && !documentation.isBlank();
    }

    @Override
    public String toString() {
        return signature + " (" + origin.title() + ")";
    }
}
