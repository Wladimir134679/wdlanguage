package ru.wds.wdl.runtime;

import ru.wds.wdl.source.Span;
import ru.wds.wdl.value.Value;
import ru.wds.wdl.value.types.InstanceObjectValue;
import ru.wds.wdl.value.types.NullValue;

import java.util.Objects;

/**
 * Область внутри аксессора свойства со скрытым полем: то, что делает видимым
 * {@code field}.
 * <p>
 * <b>{@code field} — обычное имя, а не ключевое слово.</b> Ключевые слова лексера
 * глобальны: заведи мы {@code field} там — и переменная с таким именем перестала бы
 * существовать во всех чужих скриптах, включая примеры этого репозитория. Здесь
 * оно устроено ровно как {@code this}: имя, которого нигде нет, и которое заводит
 * область вызова. Вне аксессора свойства со скрытым полем {@code field} остаётся
 * обычным именем и разрешается по общему правилу — снаружи.
 * <p>
 * Слой стоит поверх {@link InstanceScope}, а не вместо него: внутри аксессора видно
 * всё то же, что внутри метода, — поля, свойства, методы, область объявления класса.
 * Добавляется одно имя.
 * <p>
 * Скрытое поле спрашивается у экземпляра на каждом обращении, а не снимается копией:
 * {@code field} — это место, а не значение, и запись в него обязана дойти до объекта.
 */
final class FieldScope implements Environment {

    private static final String FIELD = "field";

    private final InstanceObjectValue instance;
    private final String property;
    private final Environment outer;

    FieldScope(InstanceObjectValue instance, String property, Environment outer) {
        this.instance = Objects.requireNonNull(instance, "instance");
        this.property = Objects.requireNonNull(property, "property");
        this.outer = Objects.requireNonNull(outer, "outer");
    }

    /**
     * Скрытое поле до первой записи — {@code null}-значение языка, а не отсутствие
     * имени: начальное значение записывается при создании экземпляра, поэтому
     * непрочитанным оно быть не может, а если запись почему-то не дошла, пустое
     * место лучше показать значением, чем ошибкой «переменная не определена»
     * посреди чужого аксессора.
     */
    @Override
    public Value lookup(String name) {
        if (FIELD.equals(name)) {
            Value value = instance.hidden(property);
            return value != null ? value : NullValue.NULL;
        }
        return outer.lookup(name);
    }

    @Override
    public Value lookup(String name, ExecutionContext context, Span span) {
        return FIELD.equals(name) ? lookup(name) : outer.lookup(name, context, span);
    }

    @Override
    public Value lookupHere(String name) {
        return FIELD.equals(name) ? lookup(name) : null;
    }

    @Override
    public boolean isDefined(String name) {
        return lookup(name) != null;
    }

    /**
     * Затенить {@code field} своим именем внутри аксессора нельзя: параметры и локальные
     * имена садятся в {@link Scope}, который отдаёт {@link #child()}, а сюда попасть
     * можно только из-за ошибки в движке.
     */
    @Override
    public void define(String name, Value value) {
        throw new IllegalStateException("в области аксессора нельзя завести имя напрямую: " + name);
    }

    @Override
    public void defineConstant(String name, Value value) {
        throw new IllegalStateException("в области аксессора нельзя завести имя напрямую: " + name);
    }

    @Override
    public boolean isConstantHere(String name) {
        return false;
    }

    @Override
    public Assignment assign(String name, Value value) {
        return assign(name, value, null, null);
    }

    @Override
    public Assignment assign(String name, Value value, ExecutionContext context, Span span) {
        if (FIELD.equals(name)) {
            instance.hidden(property, Objects.requireNonNull(value, "value"));
            return Assignment.DONE;
        }
        return outer.assign(name, value, context, span);
    }

    @Override
    public Environment child() {
        return Scope.under(this);
    }

    @Override
    public String toString() {
        return "FieldScope[" + property + "]";
    }
}
