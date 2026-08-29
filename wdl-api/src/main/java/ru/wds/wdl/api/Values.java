package ru.wds.wdl.api;

import ru.wds.wdl.interop.Marshal;
import ru.wds.wdl.runtime.WdlRuntimeError;
import ru.wds.wdl.source.Span;
import ru.wds.wdl.value.Value;
import ru.wds.wdl.value.types.IntValue;
import ru.wds.wdl.value.types.MapValue;

/**
 * Перевод между значениями языка и обычными Java-объектами.
 * <p>
 * Нужен затем, что приложение, встроившее движок ради двух вызовов, не должно изучать
 * {@link IntValue} и {@link MapValue}. Оно передаёт строку и получает строку; типы
 * языка остаются тем, кто с ним работает всерьёз, — {@link Value} никуда не делся
 * и доступен рядом ({@link WdlCallable#call}, {@link WdlInstance#defineValue}).
 *
 * <h2>Это фасад, а не второй переводчик</h2>
 * Правила перевода живут в {@link Marshal} — одни на весь проект: и для моста в Java,
 * и для этого фасада. Раньше их было два списка, слово в слово похожих и уже начавших
 * расходиться (у одного {@code Optional} разворачивался, у другого нет). Здесь
 * остаётся только то, чем фасад от моста отличается:
 * <ul>
 *   <li><b>цели нет.</b> Мост знает, что метод просит {@code int}; здесь известно
 *       лишь «наружу» — то есть {@code Object};</li>
 *   <li><b>отказ — это {@link IllegalArgumentException}</b>, а не ошибка скрипта:
 *       ошибся тут не автор скрипта, а тот, кто вызвал API движка из Java.</li>
 * </ul>
 *
 * <h2>Перевод — по значению и в одну сторону</h2>
 * {@code List} и {@code Map} <b>копируются</b>, а не оборачиваются. Иначе скрипт менял бы
 * коллекцию приложения из своего потока — молча, в обход всякой синхронизации, — а список,
 * положенный в скрипт, продолжал бы жить чужой жизнью. Копия честнее: у каждой стороны
 * своё, и видно, где граница.
 * <p>
 * Функция, класс и объект скрипта наружу переводятся <b>сами собой</b>: заворачивать
 * {@code FunctionValue} в {@code Map} значило бы потерять то единственное, что от неё
 * нужно, — возможность позвать. Их приложение получает как {@link Value} и работает
 * с ними через {@link WdlCallable}.
 *
 * <h2>Где проходит граница</h2>
 * Типизированный слой ({@link WdlEngine}, {@link WdlScript}, {@link WdlInstance},
 * {@link WdlCallable#call}) работает значениями языка: он для тех, кто работает
 * с языком всерьёз, и терять на границе тип числа там незачем. Короткий фасад
 * ({@link Wdl}, {@link WdlCallable#invoke}, {@link WdlInstance#get}) отдаёт
 * Java-объекты. Перевести одно в другое можно в любой момент — этим классом.
 */
public final class Values {

    /**
     * Переводчик без обёрток вокруг чужих объектов.
     * <p>
     * {@code plain}, а не мост: заворачивать неизвестный Java-объект в класс языка —
     * решение приложения, и принимает его тот, кто собрал {@code JavaBridge},
     * а не фасад, о мостах не знающий.
     */
    private static final Marshal MARSHAL = Marshal.plain();

    private Values() {
    }

    /**
     * Java-объект как значение языка.
     *
     * @throws IllegalArgumentException если такого перевода нет: молча превращать
     *                                  неизвестный объект в строку — верный способ
     *                                  получить {@code "ru.wds.App@1a2b3c"} в скрипте
     */
    public static Value of(Object object) {
        try {
            return MARSHAL.toValue(object, Span.NONE);
        } catch (WdlRuntimeError refused) {
            throw new IllegalArgumentException("нечем представить в языке: "
                    + object.getClass().getName() + ". Строки, числа, логические, списки "
                    + "и карты переводятся сами; для своего типа есть embed.NativeClass "
                    + "или мост interop.JavaBridge", refused);
        }
    }

    /**
     * Значение языка как Java-объект.
     * <p>
     * Функции, классы и экземпляры возвращаются как есть — см. javadoc класса.
     * Исключение одно: за экземпляром нативного класса или обёрткой моста стоит
     * настоящий Java-объект, и наружу отдаётся именно он — приложению нужен его
     * сокет, а не карта полей вокруг.
     */
    public static Object toJava(Value value) {
        return MARSHAL.toJava(value, Object.class, "значение", Span.NONE);
    }
}
