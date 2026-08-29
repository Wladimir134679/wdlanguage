package ru.wds.wdl.interop;

import ru.wds.wdl.value.TraitValue;
import ru.wds.wdl.value.Value;

import java.util.List;
import java.util.Objects;

/**
 * Интерфейс Java как трейт языка: {@code conn is Closeable}.
 * <p>
 * Трейтом, а не классом, потому что интерфейс — это в точности то, чем трейт
 * и является: контракт без создания. {@code new Closeable()} должно быть невозможно
 * не по запрету моста, а по виду значения.
 *
 * <h2>Требований он не объявляет</h2>
 * {@code requiredMethods} пуст, и это не заготовка на будущее. Требования трейта
 * язык проверяет на строке {@code class}, когда класс на wdl трейт подмешивает,
 * — а подмешать Java-интерфейс класс на wdl пока не может. Объявлять требования,
 * которые никто не проверяет, значило бы обещать то, чего нет.
 */
public final class JavaTrait implements TraitValue {

    private final String name;
    private final Class<?> type;

    JavaTrait(String name, Class<?> type) {
        this.name = Objects.requireNonNull(name, "name");
        this.type = Objects.requireNonNull(type, "type");
    }

    @Override
    public String name() {
        return name;
    }

    /**
     * Интерфейс Java, который стоит за этим именем.
     * <p>
     * Не {@code type()}: так у {@code Value} зовётся тип значения языка, и у трейта
     * он всегда {@code trait}.
     */
    public Class<?> javaType() {
        return type;
    }

    @Override
    public List<String> methodNames() {
        return List.copyOf(JavaShape.of(type).methodNames());
    }

    /**
     * Отвечает по живой иерархии Java, а не по перечню собранного.
     * <p>
     * Поэтому {@code conn is Closeable} верно и тогда, когда {@code Connection}
     * никто мосту не открывал: {@code isAssignableFrom} знает про иерархию всё,
     * а мост — только про то, что ему показали.
     */
    @Override
    public boolean matches(Value value) {
        Object object = Marshal.state(value);
        return object != null && type.isInstance(object);
    }

    @Override
    public String display() {
        return "trait " + name;
    }

    @Override
    public String toString() {
        return display();
    }
}
