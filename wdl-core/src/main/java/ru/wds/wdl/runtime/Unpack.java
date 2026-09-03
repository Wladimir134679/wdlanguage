package ru.wds.wdl.runtime;

import ru.wds.wdl.ast.stmt.UnpackStmt;
import ru.wds.wdl.ast.stmt.UnpackTarget;
import ru.wds.wdl.diagnostic.Plural;
import ru.wds.wdl.source.Span;
import ru.wds.wdl.value.Value;
import ru.wds.wdl.value.types.ArrayValue;
import ru.wds.wdl.value.types.InstanceObjectValue;
import ru.wds.wdl.value.types.MapValue;
import ru.wds.wdl.value.types.StringValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Семантика распаковки: что значит {@code *} и {@code **} справа от {@code =}.
 * <p>
 * <b>Вся она здесь и больше нигде.</b> Инструкция зовёт этот класс, а не повторяет
 * правила — причина ровно та же, по которой у {@code match} нет своей системы образцов:
 * две реализации одного правила расходятся, и первым разъезжается текст ошибки.
 * Когда сюда придёт привязка в {@code match} ({@code case is Rect as (w, h)}), она
 * позовёт те же два метода и получит те же сообщения даром.
 * <p>
 * Наружу отсюда видны две операции — разложить по позициям и разложить по именам, —
 * и обе отдают <b>готовые значения в порядке целей</b>. Ни одна из них никуда
 * не пишет: запись — дело {@code Interpreter}, и это не разделение ради разделения.
 * Правая часть обязана вычислиться целиком до первой записи, иначе {@code a, b = b, a}
 * не сработает, а ошибка на середине оставит половину имён перезаписанными.
 *
 * <h2>Две асимметрии, которые здесь закреплены</h2>
 * <b>Длина в {@code *}-форме — контракт, и ошибка в обе стороны.</b> И недостача,
 * и лишнее: {@code x, y, z = *[1, 2]} почти всегда значит, что расчёт выше неверен,
 * — та же логика, по которой выход за границы массива это ошибка, а не {@code null}.
 * Явный отказ от остатка пишется {@code *_}.
 * <p>
 * <b>Размер объекта в {@code **}-форме — не контракт: лишние ключи не ошибка.</b>
 * Объект приходит из конфига, из JSON, из ответа сервера и почти всегда несёт больше,
 * чем берут; будь лишние ключи ошибкой, отказ от остатка пришлось бы дописывать
 * в каждую вторую строку. А вот недостающий ключ — ошибка: это как раз опечатка
 * в имени или изменившийся формат данных.
 */
final class Unpack {

    private Unpack() {
    }

    /**
     * Раскладка по позициям: {@code x, y, *rest = *source}.
     * <p>
     * Источником бывают массив и экземпляр класса — и больше ничто. У экземпляра
     * порядок задаёт заголовок ({@link ru.wds.wdl.value.ClassValue#fieldNames()}):
     * отдельного объявления полей в языке нет, заголовок и есть список компонентов.
     * Обычный объект по позициям не раскладывается сознательно — порядок вставки
     * у него сохраняется, но это данные, а не договорённость: объект из JSON или
     * из сети приехал бы переставленным, и распаковка молча положила бы не то.
     *
     * @return значения в порядке целей; на месте пропуска — {@code null}
     */
    static List<Value> positional(UnpackStmt stmt, Value source, Span span) {
        List<Value> items = positions(stmt, source, span);
        List<UnpackTarget> targets = stmt.targets();
        UnpackTarget rest = stmt.rest();
        int taken = stmt.positions();
        if (rest == null && items.size() != taken) {
            throw lengthMismatch(stmt, source, items.size(), taken, span);
        }
        if (rest != null && items.size() < taken) {
            throw lengthMismatch(stmt, source, items.size(), taken, span);
        }

        List<Value> values = new ArrayList<>(targets.size());
        int at = 0;
        for (UnpackTarget target : targets) {
            if (target.positional()) {
                // Пропуск позицию занимает, но значения не отдаёт: считать его
                // и выбросить — ровно то, за чем он и написан.
                values.add(target.kind() == UnpackTarget.Kind.HOLE ? null : items.get(at));
                at++;
                continue;
            }
            // Остаток — новый массив, а не вид на источник: иначе запись в 'rest'
            // меняла бы источник через вторую переменную, и выяснялось бы это далеко
            // от места. '*_' сюда доходит с target == null — посчитан и выброшен.
            values.add(target.writes()
                    ? ArrayValue.of(List.copyOf(items.subList(at, items.size())))
                    : null);
            at = items.size();
        }
        return values;
    }

    /**
     * Раскладка по именам: {@code host, port, **rest = **source}.
     * <p>
     * Источником бывают объект и экземпляр. Экземпляр, таким образом, раскладывается
     * обеими формами — {@code *} по порядку заголовка, {@code **} по именам полей.
     * Свойства в {@code **} не участвуют: свойство вычисляемое, и дёргать неизвестно
     * сколько аксессоров на распаковке нельзя. Берётся то же, что даёт перебор, — поля.
     * <p>
     * Ключ ищется <b>наличием</b> ({@link MapValue#has}), а не значением: {@code {x: null}}
     * — это «ключ есть, значение null», и распаковка обязана его принять, а не сказать
     * «нет ключа».
     *
     * @return значения в порядке целей
     */
    static List<Value> named(UnpackStmt stmt, Value source, Span span) {
        if (!(source instanceof MapValue object)) {
            throw new WdlRuntimeError(ErrorKind.TYPE, span,
                    "по именам распаковывается объект или экземпляр класса, а здесь "
                            + source.type().title() + " (" + source.display() + ")");
        }
        List<Value> values = new ArrayList<>(stmt.targets().size());
        List<String> taken = new ArrayList<>(stmt.targets().size());
        for (UnpackTarget target : stmt.targets()) {
            if (target.isRest()) {
                values.add(null);
                continue;
            }
            // Имя цели проверено разбором: в '**'-форме цель обязана заканчиваться
            // именем, потому что это имя и есть ключ.
            String key = target.trailingName();
            taken.add(key);
            if (!object.has(StringValue.of(key))) {
                throw new WdlRuntimeError(ErrorKind.NAME, target.span(),
                        source instanceof InstanceObjectValue instance
                                ? "у экземпляра класса '" + instance.owner().name()
                                        + "' нет поля '" + key + "'"
                                : "в объекте нет ключа '" + key + "'");
            }
            values.add(object.get(StringValue.of(key)));
        }
        // Остаток заполняется вторым проходом: собрать «всё неназванное» можно только
        // когда список названных дочитан до конца.
        UnpackTarget rest = stmt.rest();
        if (rest != null && rest.writes()) {
            values.set(stmt.targets().indexOf(rest), remainder(object, taken));
        }
        return values;
    }

    /** Новый объект из ключей, которые не назвал никто. Копией — по причине из javadoc класса. */
    private static MapValue remainder(MapValue object, List<String> taken) {
        MapValue rest = new MapValue();
        for (Map.Entry<Value, Value> entry : object.entries().entrySet()) {
            if (entry.getKey() instanceof StringValue key && taken.contains(key.value())) {
                continue;
            }
            rest.put(entry.getKey(), entry.getValue());
        }
        return rest;
    }

    /**
     * Значения источника по позициям — и единственное место, где решается, что вообще
     * раскладывается по позициям.
     * <p>
     * Экземпляр проверяется <b>до</b> объекта: он наследует {@link MapValue}, и порядок
     * веток здесь и есть разница между «поля в порядке заголовка» и «отказ, потому что
     * порядок вставки не договорённость».
     */
    private static List<Value> positions(UnpackStmt stmt, Value source, Span span) {
        switch (source) {
            case ArrayValue array -> {
                return array.items();
            }
            case InstanceObjectValue instance -> {
                List<String> fields = instance.owner().fieldNames();
                List<Value> values = new ArrayList<>(fields.size());
                for (String field : fields) {
                    values.add(instance.get(StringValue.of(field)));
                }
                return values;
            }
            default -> throw wrongPositionalSource(stmt, source, span);
        }
    }

    /**
     * «По позициям так нельзя» — с подсказкой, как то же самое пишется правильно.
     * <p>
     * Подсказка зависит от того, что принесли, и в этом весь смысл сообщения:
     * у объекта распаковка есть, просто другая; у строки её нет вовсе, зато есть
     * обращение по номеру. Общая часть текста при этом одна на все случаи.
     */
    private static WdlRuntimeError wrongPositionalSource(UnpackStmt stmt, Value source, Span span) {
        String head = "по позициям распаковывается массив или экземпляр класса, а здесь "
                + source.type().title();
        String hint = switch (source) {
            case MapValue ignored -> "; по именам это пишется так: '" + stmt.targetsText()
                    + " = **" + stmt.source().value() + "'";
            case StringValue ignored -> "; символ берётся по номеру: "
                    + stmt.source().value() + "[0]";
            default -> " (" + source.display() + ")";
        };
        return new WdlRuntimeError(ErrorKind.TYPE, span, head + hint);
    }

    /**
     * Длина не сошлась — в ту или в другую сторону.
     * <p>
     * Сообщения разные, потому что и поправки разные: недостача значит, что имён
     * написали больше, чем источник может дать, а излишек — что забыли про остаток,
     * и вот его-то написать и предлагается.
     */
    private static WdlRuntimeError lengthMismatch(UnpackStmt stmt, Value source,
                                                  int have, int need, Span span) {
        String what = source instanceof InstanceObjectValue instance
                ? "экземпляр класса '" + instance.owner().name() + "'"
                : "массив";
        if (have < need) {
            return new WdlRuntimeError(ErrorKind.VALUE, span, "распаковка ждёт "
                    + Plural.values(need) + ", а " + what + " даёт " + have);
        }
        return new WdlRuntimeError(ErrorKind.VALUE, span, "распаковка берёт "
                + Plural.values(need) + ", а " + what + " даёт " + have
                + "; остаток пишется как '" + stmt.targetsText() + ", *rest' или '"
                + stmt.targetsText() + ", *_'");
    }

    /**
     * Попарный список: {@code a, b = b, a}. Раскрытие {@code *pair} внутри списка
     * разворачивается ровно так же, как в аргументах вызова, — и по той же причине
     * его длина видна только здесь, при выполнении.
     *
     * @param values значения источников в порядке записи
     * @return плоский список значений, по одному на цель
     */
    static List<Value> pairwise(UnpackStmt stmt, List<Value> values, Span span) {
        List<Value> flat = new ArrayList<>(values.size());
        for (int i = 0; i < values.size(); i++) {
            if (stmt.sources().get(i).isSpread()) {
                if (!(values.get(i) instanceof ArrayValue array)) {
                    throw new WdlRuntimeError(ErrorKind.TYPE, stmt.sources().get(i).span(),
                            "раскрыть в список значений можно только массив, а здесь "
                                    + values.get(i).type().title()
                                    + " (" + values.get(i).display() + ")");
                }
                flat.addAll(array.items());
                continue;
            }
            flat.add(values.get(i));
        }
        int need = stmt.targets().size();
        if (flat.size() != need) {
            throw new WdlRuntimeError(ErrorKind.VALUE, span, "слева " + Plural.names(need)
                    + ", а справа " + Plural.values(flat.size()));
        }
        // Пропуск и здесь ничего не хранит: значение посчитано, и на этом всё.
        List<Value> result = new ArrayList<>(need);
        for (int i = 0; i < need; i++) {
            result.add(stmt.targets().get(i).writes() ? flat.get(i) : null);
        }
        return result;
    }
}
