package ru.wds.wdl.runtime;

import ru.wds.wdl.source.Span;
import ru.wds.wdl.value.Binding;
import ru.wds.wdl.value.Value;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Область видимости: таблица имён плюс ссылка на внешнюю область.
 * <p>
 * Никакой статики: корневая область создаётся вызовом {@link #root()}, и сколько
 * таких корней живёт в процессе — столько независимых друг от друга запусков скриптов.
 * Именно это делает изоляцию интерпретаторов настоящей, а не декларируемой.
 * <p>
 * Реализация нарочно самая простая, какая работает. Оптимизировать поиск имени
 * имеет смысл после того, как появятся функции и циклы, — и делать это надо будет
 * не здесь, а в резолвере, который заменит имена на номера слотов.
 *
 * <h2>Потоки</h2>
 * Таблица имён — {@link ConcurrentHashMap}, и это требование, а не осторожность.
 * Область живёт дольше вызова, который её завёл: она становится замыканием функции,
 * а функцию скрипт волен отдать в другой поток. Обычная {@code HashMap} на такое
 * отвечает не «неверным значением», а порчей структуры — потерянными элементами
 * при перестройке и, в худшем случае, зацикливанием чтения.
 * <p>
 * Обещание отсюда ровно одно и записано в {@code docs/threads.md}: <b>одно обращение
 * атомарно</b>. Чтение имени видит либо старое значение, либо новое, но не половину;
 * {@code count = count + 1} из двух потоков по-прежнему теряет обновления — это два
 * обращения, и склеивает их {@code synchronized} на функции, а не область видимости.
 */
public final class Scope implements Environment {

    private final Environment parent;
    private final Map<String, Value> values = new ConcurrentHashMap<>();

    /**
     * Имена, замороженные {@code const}. Отдельным множеством, а не признаком рядом
     * со значением, потому что {@link #lookup} — самая горячая операция интерпретатора,
     * и платить в ней за возможность, которой пользуется одна инструкция из ста, незачем.
     * <p>
     * Поле заводится лениво и остаётся {@code null}, пока констант нет: областей
     * создаётся по одной на вызов функции, блок и итерацию перебора, и в подавляющем
     * большинстве из них не объявляют ничего. {@code volatile} — цена этой лени
     * в многопоточном мире: без него чужой поток увидел бы ссылку на множество раньше,
     * чем его содержимое.
     */
    private volatile Set<String> constants;

    /**
     * Имена, заведённые развёрнутым {@code import}: связки на ячейки модуля.
     * <p>
     * Отдельной картой и лениво — по той же причине, что и {@link #constants}:
     * {@link #lookup} горячий, областей создаётся по одной на вызов, блок и итерацию,
     * и в подавляющем большинстве из них импорта нет. Пока карты нет, связки стоят
     * ровно одну проверку {@code != null} на промахе по своим именам.
     */
    private volatile Map<String, Binding> aliases;

    /**
     * Реестр членов запуска — только у корневой области, и заводится он не здесь,
     * а {@link ExecutionContext#of}: запуск создаётся из области, а не наоборот,
     * поэтому в конструкторе его ещё нет. Вложенные области спрашивают внешнюю.
     */
    private volatile ru.wds.wdl.embed.MemberRegistry members;

    private Scope(Environment parent) {
        this.parent = parent;
    }

    void useMembers(ru.wds.wdl.embed.MemberRegistry registry) {
        this.members = registry;
    }

    @Override
    public ru.wds.wdl.embed.MemberRegistry members() {
        ru.wds.wdl.embed.MemberRegistry own = members;
        if (own != null) {
            return own;
        }
        return parent == null ? null : parent.members();
    }

    /** Новая независимая корневая область. */
    public static Scope root() {
        return new Scope(null);
    }

    /**
     * Область поверх произвольного окружения.
     * <p>
     * Нужна там, где внешним окружением служит не другая {@code Scope}, а
     * {@link InstanceScope}: локальные имена вызова метода садятся сюда, а поля
     * и методы находятся снаружи. Отсюда и «новое имя полем не становится» —
     * {@code длина = x + y} внутри метода заводит имя здесь, а не в экземпляре.
     */
    static Scope under(Environment parent) {
        return new Scope(Objects.requireNonNull(parent, "parent"));
    }

    @Override
    public Value lookup(String name) {
        Objects.requireNonNull(name, "name");
        Value value = values.get(name);
        if (value != null) {
            return value;
        }
        Binding alias = alias(name);
        if (alias != null) {
            return alias.value();
        }
        return parent != null ? parent.lookup(name) : null;
    }

    /**
     * Своё имя сильнее свойства снаружи — как и всякое своё имя сильнее внешнего.
     * Локальная переменная {@code size} внутри метода затеняет свойство {@code size},
     * ровно как затеняет глобальную переменную.
     */
    @Override
    public Value lookup(String name, ExecutionContext context, Span span) {
        Objects.requireNonNull(name, "name");
        Value value = values.get(name);
        if (value != null) {
            return value;
        }
        Binding alias = alias(name);
        if (alias != null) {
            return alias.value();
        }
        return parent != null ? parent.lookup(name, context, span) : null;
    }

    @Override
    public Value lookupHere(String name) {
        Objects.requireNonNull(name, "name");
        Value value = values.get(name);
        if (value != null) {
            return value;
        }
        Binding alias = alias(name);
        return alias != null ? alias.value() : null;
    }

    /** Связка с этим именем или {@code null}. Своё имя спрашивается раньше — оно сильнее. */
    private Binding alias(String name) {
        Map<String, Binding> known = aliases;
        return known != null ? known.get(name) : null;
    }

    /**
     * Множество констант — готовое или заведённое сейчас.
     * <p>
     * Двойная проверка с {@code volatile}: на горячем пути ({@link #lookup},
     * {@link #assign}) ленивое поле читается без замка, а платит за создание
     * только тот, кто первым объявил здесь константу.
     */
    private Set<String> constants() {
        Set<String> known = constants;
        if (known != null) {
            return known;
        }
        synchronized (this) {
            if (constants == null) {
                constants = ConcurrentHashMap.newKeySet(4);
            }
            return constants;
        }
    }

    /** Карта связок — готовая или заведённая сейчас; по тому же правилу, что {@link #constants()}. */
    private Map<String, Binding> aliases() {
        Map<String, Binding> known = aliases;
        if (known != null) {
            return known;
        }
        synchronized (this) {
            if (aliases == null) {
                aliases = new ConcurrentHashMap<>(4);
            }
            return aliases;
        }
    }

    @Override
    public boolean isDefined(String name) {
        return lookup(name) != null;
    }

    @Override
    public void define(String name, Value value) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(value, "value");
        // Своё объявление вытесняет связку: имя после него означает эту переменную,
        // а не ячейку модуля, — иначе присваивание уходило бы в чужой файл.
        Map<String, Binding> known = aliases;
        if (known != null) {
            known.remove(name);
        }
        values.put(name, value);
    }

    @Override
    public void defineConstant(String name, Value value) {
        define(name, value);
        constants().add(name);
    }

    @Override
    public void defineAlias(String name, Binding binding) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(binding, "binding");
        // Обратная сторона того же правила: импорт вытесняет ранее объявленное имя,
        // как это делает любое объявление на его месте.
        values.remove(name);
        Set<String> frozen = constants;
        if (frozen != null) {
            frozen.remove(name);
        }
        aliases().put(name, binding);
    }

    @Override
    public boolean isConstantHere(String name) {
        Objects.requireNonNull(name, "name");
        if (isFrozen(name)) {
            return true;
        }
        Binding alias = alias(name);
        return alias != null && alias.constant();
    }

    private boolean isFrozen(String name) {
        Set<String> frozen = constants;
        return frozen != null && frozen.contains(name);
    }

    @Override
    public Assignment assign(String name, Value value) {
        return assign(name, value, null, null);
    }

    /**
     * Контекст здесь только транзитом: своих свойств у области нет, но за именем
     * снаружи может стоять свойство, и {@code size = 5} внутри метода обязано дойти
     * до его setter, а не завести имя рядом.
     */
    @Override
    public Assignment assign(String name, Value value, ExecutionContext context, Span span) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(value, "value");
        if (isFrozen(name)) {
            // Замороженное имя всегда лежит и в таблице значений: 'const' сначала
            // объявляет, потом морозит, а связка стирает обе записи разом.
            return Assignment.CONSTANT;
        }
        // Одной атомарной операцией, а не «проверил — записал»: между двумя обращениями
        // соседний поток вправе завести здесь это же имя, и тогда запись сюда потеряла бы
        // его объявление.
        if (values.replace(name, value) != null) {
            return Assignment.DONE;
        }
        // Связка уводит присваивание в модуль: имя, пришедшее развёрнутым импортом,
        // ведёт себя как объявленное здесь, а живёт там.
        Binding alias = alias(name);
        if (alias != null) {
            return alias.set(value) ? Assignment.DONE : Assignment.CONSTANT;
        }
        return parent != null ? parent.assign(name, value, context, span) : Assignment.ABSENT;
    }

    @Override
    public Environment child() {
        return new Scope(this);
    }

    @Override
    public String toString() {
        return "Scope[" + values.size() + " имён" + (parent != null ? ", вложенная]" : ", корневая]");
    }
}
