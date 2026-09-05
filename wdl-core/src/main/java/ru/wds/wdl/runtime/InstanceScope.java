package ru.wds.wdl.runtime;

import ru.wds.wdl.source.Span;
import ru.wds.wdl.value.Property;
import ru.wds.wdl.value.Value;
import ru.wds.wdl.value.types.InstanceObjectValue;
import ru.wds.wdl.value.types.StringValue;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Область видимости поверх экземпляра: то, что делает поле видимым по имени.
 * <p>
 * Внутри класса {@code имя} — это {@code this.имя}: одна и та же ячейка, а не две.
 * Это не исключение для классов, а общее правило языка — имя, которое снаружи есть,
 * именно меняется, а не заводится заново. Экземпляр просто ещё одно «снаружи»,
 * и самое близкое:
 * <pre>
 * локальные имена вызова → экземпляр (поля, свойства, методы) → область объявления класса → глобальные
 * </pre>
 * Порядок внутри экземпляра тот же, что при обращении по ключу: поля, свойства,
 * методы. Одно правило на две записи — иначе {@code имя} и {@code this.имя}
 * разошлись бы в каком-нибудь углу.
 * <p>
 * <b>Присваивание смотрит поля и свойства.</b> Если имени нет ни там, ни там, запись
 * уходит наружу, и новое имя внутри метода становится обычной локальной переменной,
 * а не полем: полем его делает только явное {@code this.имя = ...}. По той же причине
 * {@code текст = "x"} при существующем методе {@code текст} метод не подменяет —
 * методы живут в классе, а не в экземпляре.
 * <p>
 * Свойство в присваивании участвует, а метод нет, и разница не в прихоти: за свойством
 * стоит место, которое пишут, а за методом — значение, которое зовут. Не спроси мы
 * здесь про свойство, {@code size = 5} внутри метода молча завёл бы локальную
 * переменную вместо вызова setter — тихая ошибка, которую в коде не видно.
 * <p>
 * Интерпретатор об этой области ничего не знает: он видит только {@link Environment}.
 */
final class InstanceScope implements Environment {

    private static final String THIS = "this";
    private static final String SUPER = "super";

    private final InstanceObjectValue instance;
    /** Класс экземпляра: поиск метода по голому имени идёт по нему, то есть виртуально. */
    private final WdlClass owner;
    private final Method method;

    InstanceScope(InstanceObjectValue instance, WdlClass owner, Method method) {
        this.instance = Objects.requireNonNull(instance, "instance");
        this.owner = Objects.requireNonNull(owner, "owner");
        this.method = Objects.requireNonNull(method, "method");
    }

    @Override
    public Value lookup(String name) {
        Objects.requireNonNull(name, "name");
        if (THIS.equals(name)) {
            return instance;
        }
        if (SUPER.equals(name)) {
            return superView();
        }
        if (instance.has(name)) {
            // Именно has, а не get: отсутствующий ключ даёт null-значение, и любое
            // неизвестное имя внутри метода стало бы полем, отрезав println и len.
            return instance.get(name);
        }
        Value bound = owner.method(instance, name);
        return bound != null ? bound : method.closure().lookup(name);
    }

    /**
     * То же чтение, но свойство здесь можно прочитать по-настоящему: контекст пришёл
     * от вызывающего, а значит есть чем позвать getter.
     * <p>
     * Порядок тот же, что при обращении по ключу: поле, свойство, метод. Одно правило
     * на две записи — иначе {@code имя} и {@code this.имя} разошлись бы в каком-нибудь
     * углу. Одноимённого поля рядом со свойством не бывает: ячейку имени они делят,
     * и плоская таблица оставила одно из двух.
     * <p>
     * Без контекста ({@link #lookup(String)}) свойство пропускается, а не падает:
     * туда приходят с путей, где выполнять чужой код нельзя, — {@code isDefined},
     * проверка занятости имени. Возвращать оттуда «имени нет» честнее, чем звать
     * getter в момент, когда его никто не ждёт.
     */
    @Override
    public Value lookup(String name, ExecutionContext context, Span span) {
        Objects.requireNonNull(name, "name");
        if (THIS.equals(name)) {
            return instance;
        }
        if (SUPER.equals(name)) {
            return superView();
        }
        if (instance.has(name)) {
            return instance.get(name);
        }
        Property property = owner.property(name);
        if (property != null) {
            return property.read(instance, context, span);
        }
        Value bound = owner.method(instance, name);
        return bound != null ? bound : method.closure().lookup(name, context, span);
    }

    /**
     * Свои имена — поля экземпляра: их и показывает панель переменных отладчика,
     * остановившегося внутри метода. Методы и свойства принадлежат классу, и спрашивать
     * их надо у него, а не у объекта.
     */
    @Override
    public Set<String> namesHere() {
        Set<String> names = new LinkedHashSet<>();
        instance.entries().keySet().forEach(key -> {
            if (key instanceof StringValue text) {
                names.add(text.value());
            }
        });
        return Collections.unmodifiableSet(names);
    }

    @Override
    public Set<String> names() {
        Set<String> names = new LinkedHashSet<>(namesHere());
        names.addAll(method.closure().names());
        return Collections.unmodifiableSet(names);
    }

    /** Своё у экземпляра — поля: методы принадлежат классу, а не объекту. */
    @Override
    public Value lookupHere(String name) {
        Objects.requireNonNull(name, "name");
        return instance.has(name) ? instance.get(name) : null;
    }

    @Override
    public boolean isDefined(String name) {
        return lookup(name) != null;
    }

    /**
     * Заводить имена в экземпляре напрямую нельзя: параметры вызова и новые локальные
     * имена садятся в {@link Scope}, который отдаёт {@link #child()}. Если сюда всё же
     * пришли — это ошибка в движке, а не в скрипте.
     */
    @Override
    public void define(String name, Value value) {
        throw new IllegalStateException("в экземпляре нельзя завести имя напрямую: " + name);
    }

    /** Как и {@link #define}: имена экземпляра заводит класс, а не инструкция в теле метода. */
    @Override
    public void defineConstant(String name, Value value) {
        throw new IllegalStateException("в экземпляре нельзя завести имя напрямую: " + name);
    }

    /** Полей-констант не бывает: {@code const} в тело класса парсер не пропускает. */
    @Override
    public boolean isConstantHere(String name) {
        return false;
    }

    /**
     * Поле ближе внешней константы. Если снаружи есть {@code const count}, а у экземпляра
     * поле с тем же именем, то {@code count = 5} внутри метода пишет в поле и внешнюю
     * константу не трогает — это то же правило «имя экземпляра ближе», по которому
     * {@code имя} внутри метода означает {@code this.имя}.
     */
    @Override
    public Assignment assign(String name, Value value) {
        return assign(name, value, null, null);
    }

    /**
     * Запись: поле, свойство, наружу.
     * <p>
     * Свойство участвует, а метод нет, и это не прихоть: за свойством стоит место,
     * которое пишут, за методом — значение, которое зовут. Не спроси мы здесь про
     * свойство, {@code size = 5} внутри метода молча завёл бы локальную переменную
     * вместо вызова setter — тихая ошибка, которой в коде не видно.
     * <p>
     * Свойство только для чтения даёт ту же ошибку, что и запись через точку: правило
     * одно, и звучать по-разному в зависимости от формы записи оно не должно.
     */
    @Override
    public Assignment assign(String name, Value value, ExecutionContext context, Span span) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(value, "value");
        if (instance.has(name)) {
            instance.put(name, value);
            return Assignment.DONE;
        }
        Property property = context == null ? null : owner.property(name);
        if (property != null) {
            if (!property.writable()) {
                throw new WdlRuntimeError(ErrorKind.DECLARATION, span, "свойство '" + name
                        + "' класса '" + owner.name() + "' только для чтения: "
                        + "у него нет 'def set(value)'");
            }
            property.write(instance, value, context, span);
            return Assignment.DONE;
        }
        return method.closure().assign(name, value, context, span);
    }

    @Override
    public Environment child() {
        return Scope.under(this);
    }

    /**
     * {@code super} — тот же экземпляр, но поиск методов начинается с родителя
     * <b>того класса, где написан метод</b>. Метод {@code Shape.text()},
     * унаследованный кругом, обязан звать родителя Shape, а не родителя круга, —
     * иначе {@code super} в цепочке из трёх классов зациклился бы.
     * <p>
     * Поля у вида те же самые, не копия, а связанный через него метод помнит
     * <b>корневой</b> экземпляр: иначе {@code area()} и {@code this.area()} внутри
     * такого метода разошлись бы — первое искало бы виртуально, второе от родителя.
     */
    private Value superView() {
        WdlClass from = method.superFrom() == null ? null : method.superFrom().parent();
        return from == null ? null : InstanceObjectValue.viewOf(instance, from);
    }

    @Override
    public String toString() {
        return "InstanceScope[" + owner.name() + "]";
    }
}
