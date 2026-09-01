package ru.wds.wdl.bridge;

import ru.wds.wdl.value.Signature;
import ru.wds.wdl.value.Value;

import java.util.ArrayList;
import java.util.List;

/**
 * Параметры конструктора — цепочкой, тем же словарём, что и члены.
 * <p>
 * Заголовок нативного класса собирается из тех же кирпичей, что {@link Signature},
 * но пишется он в одном месте и подряд, а {@code Signature.Param.optional(...)}
 * перед каждым именем — это три слова служебного текста на одно объявление.
 * Здесь имя параметра стоит первым и читается списком:
 *
 * <pre>{@code
 * NativeClass.named("TextArea")
 *         .backing(JScrollPane.class)
 *         .init(Params.of()
 *                 .optional("text", "")
 *                 .optional("rows", 10)
 *                 .optional("cols", 30)
 *                 .optional("enabled", true),
 *                 (self, context, args, span) -> { ... })
 *         .members(FromJava.of(JTextArea.class)
 *                 .bean("text")
 *                 .method("append"))
 *         .build();
 * }</pre>
 *
 * Значение по умолчанию пишется литералом: заголовки, которые объявляет приложение,
 * почти всегда стоят на литералах, и {@code IntValue.of(10)} вокруг каждого ничего
 * к объявлению не добавляет. Что литералом не выражается ({@code null}, массив),
 * приходит {@link Value значением} — перегрузкой того же имени.
 * <p>
 * Правила у заголовка те же, что у языка, и проверяет их {@link Signature}: имена
 * не повторяются, обязательный параметр не идёт после необязательного.
 */
public final class Params {

    private final List<Signature.Param> params = new ArrayList<>();

    private Params() {
    }

    /** Пустой список, который дальше наполняется цепочкой. */
    public static Params of() {
        return new Params();
    }

    /** Обязательный: аргумент передать придётся. */
    public Params required(String name) {
        return add(Signature.Param.required(name));
    }

    /** Необязательный: пропуск заполнит значение по умолчанию. */
    public Params optional(String name, Value defaultValue) {
        return add(Signature.Param.optional(name, defaultValue));
    }

    /** Необязательный со строковым значением по умолчанию. */
    public Params optional(String name, String defaultValue) {
        return add(Signature.Param.optional(name, defaultValue));
    }

    /** Необязательный с целым значением по умолчанию. */
    public Params optional(String name, long defaultValue) {
        return add(Signature.Param.optional(name, defaultValue));
    }

    /** Необязательный с дробным значением по умолчанию. */
    public Params optional(String name, double defaultValue) {
        return add(Signature.Param.optional(name, defaultValue));
    }

    /** Необязательный с логическим значением по умолчанию. */
    public Params optional(String name, boolean defaultValue) {
        return add(Signature.Param.optional(name, defaultValue));
    }

    /**
     * Собранный контракт: то же, что {@code Signature.of(...)} со всеми параметрами.
     * <p>
     * Здесь же ловятся повтор имени и обязательный после необязательного — то есть
     * при сборке класса, на старте приложения.
     */
    public Signature signature() {
        return Signature.of(params);
    }

    private Params add(Signature.Param param) {
        params.add(param);
        return this;
    }
}
