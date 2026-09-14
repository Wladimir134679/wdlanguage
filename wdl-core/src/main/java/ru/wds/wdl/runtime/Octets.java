package ru.wds.wdl.runtime;

import ru.wds.wdl.source.Span;
import ru.wds.wdl.value.CallContext;
import ru.wds.wdl.value.NumberValue;
import ru.wds.wdl.value.Value;
import ru.wds.wdl.value.types.BytesValue;
import ru.wds.wdl.value.types.FloatValue;
import ru.wds.wdl.value.types.IntValue;

/**
 * Одно правило байта и один словарь видов чисел на весь язык — то же место в теме
 * байтов, какое {@link Indexes} занимает в теме индексов.
 *
 * <h2>Три вопроса про байт, и они разные</h2>
 * <ul>
 *   <li>{@link #any} — «это байт в любой записи», {@code -128..255}. Так спрашивают
 *       на <b>входе</b>: {@code bin.of([...])}, {@code w.put(v)}, {@code x in data},
 *       {@code data.indexOf(x)}. {@code 200} и {@code -56} — две записи одного байта,
 *       результат от выбора записи не зависит, терять на этом нечего, зато
 *       {@code bin.of([0x89, 0x50, 0x4e, 0x47])}, переписанное из документации формата,
 *       продолжает работать буквально;</li>
 *   <li>{@link #signed} — «это знаковый байт», {@code -128..127}: {@code w.putInt8(v)};</li>
 *   <li>{@link #unsigned} — «это беззнаковый байт», {@code 0..255}: {@code w.putUint8(v)}.</li>
 * </ul>
 * Строгие две остаются строгими и с широкой не объединяются: их пишут тогда, когда
 * нужна именно проверка диапазона. Три текста ошибки, написанные рядом, — это и есть
 * причина, по которой класс существует: написанные в трёх файлах, они разошлись бы.
 * <p>
 * <b>На выходе вопрос один.</b> Байт, вышедший наружу поштучно ({@code b[i]},
 * {@code for (x in b)}, {@code b.first}), — знаковое число {@code -128..127}, как
 * в Java. Асимметрия входа и выхода намеренная и разобрана в {@code docs/bytes.md}.
 *
 * <h2>Словарь видов чисел — тоже здесь</h2>
 * {@link Kind} перечисляет девять видов ({@code int8} … {@code float64}) и знает про
 * каждый всё: сколько байтов, какой диапазон, как прочитать и как записать. Эти же
 * девять слов стоят суффиксом члена ({@code data.int32(16)}), аргументом вида элемента
 * и в тексте ошибки — поэтому и объявлены один раз. Синонимов ({@code i32},
 * {@code dword}, {@code long}) нет: синоним здесь дороже краткости. Беззнакового
 * {@code uint64} нет тоже — число в языке знаковое, и врать про это нечем.
 * <p>
 * Порядок байтов — два слова, {@code "be"} (по умолчанию) и {@code "le"},
 * и тоже везде одинаково ({@link #littleEndian}).
 */
public final class Octets {

    /** Наименьшее, что принимается за байт на входе. */
    public static final int MIN = -128;

    /** Наибольшее, что принимается за байт на входе. */
    public static final int MAX = 255;

    private Octets() {
    }

    /**
     * Байт в любой записи: {@code -128..255}.
     *
     * @param what чем это место называется в сообщении: {@code "элемент 3"}
     */
    public static byte any(long value, String what, Span span) {
        if (value < MIN || value > MAX) {
            throw new WdlRuntimeError(ErrorKind.VALUE, span, what + " не байт: " + value
                    + " вне " + MIN + ".." + MAX + " (байт пишется и знаковым, и беззнаковым)");
        }
        return (byte) value;
    }

    /** То же, но значение ещё и не проверено на «целое число». */
    public static byte any(Value value, String what, Span span) {
        return any(integer(value, what, span), what, span);
    }

    /**
     * Байт или {@code null}, если значение байтом не является ни в какой записи.
     * <p>
     * Ответ, а не ошибка, нужен там, где спрашивают про принадлежность:
     * {@code "текст" in data} — это не «байт не в диапазоне», а «искали не то»,
     * и сказать об этом должен тот, кто знает контекст поиска.
     */
    public static Byte matching(Value value) {
        if (!(value instanceof NumberValue number) || !number.isInteger()) {
            return null;
        }
        long raw = number.asLong();
        return raw < MIN || raw > MAX ? null : (byte) raw;
    }

    /** Знаковый байт: {@code -128..127}. */
    public static byte signed(long value, String what, Span span) {
        if (value < Byte.MIN_VALUE || value > Byte.MAX_VALUE) {
            throw new WdlRuntimeError(ErrorKind.VALUE, span, what + ": ожидался int8 ("
                    + Byte.MIN_VALUE + ".." + Byte.MAX_VALUE + "), а здесь " + value);
        }
        return (byte) value;
    }

    /** Беззнаковый байт: {@code 0..255}. */
    public static byte unsigned(long value, String what, Span span) {
        if (value < 0 || value > 0xFF) {
            throw new WdlRuntimeError(ErrorKind.VALUE, span, what + ": ожидался uint8 (0..255),"
                    + " а здесь " + value);
        }
        return (byte) value;
    }

    /**
     * Порядок байтов по имени: {@code "be"} — старший первым (по умолчанию),
     * {@code "le"} — младший первым.
     */
    public static boolean littleEndian(String order, String what, Span span) {
        if ("le".equals(order)) {
            return true;
        }
        if ("be".equals(order)) {
            return false;
        }
        throw new WdlRuntimeError(ErrorKind.VALUE, span, what + ": порядок байтов — это"
                + " \"be\" (старший первым) или \"le\" (младший первым), а здесь \"" + order + "\"");
    }

    /**
     * Шаги за работу над {@code bytes} байтами — пачкой, пропорционально объёму.
     * <p>
     * {@code b.hex} на ста мегабайтах — это цикл целиком внутри Java, и точек
     * {@code checkpoint} он не проходит ни одной: предел шагов обошёлся бы одной
     * строкой скрипта. Ответ — тот же библиотечный шаг, которым пользуются источники
     * конвейеров ({@link CallContext#step}), только взятый сразу
     * за весь объём: <b>шаг на килобайт</b>, но не меньше одного.
     * <p>
     * Килобайт, а не байт: шаг стоит чтения поля и сравнения, и миллион таких на
     * мегабайт был бы дороже самой работы, а точность в один шаг тому, кто ставит
     * предел в миллионы, безразлична — тот же счёт, что у {@link Limits#STEP_BATCH}.
     */
    public static void work(CallContext context, long bytes, Span span) {
        long steps = bytes / 1024 + 1;
        for (long i = 0; i < steps; i++) {
            context.step(span);
        }
    }

    private static long integer(Value value, String what, Span span) {
        if (!(value instanceof NumberValue number) || !number.isInteger()) {
            throw new WdlRuntimeError(ErrorKind.TYPE, span, what
                    + ": байт задаётся целым числом, а здесь " + value.type().title()
                    + " (" + value + ")");
        }
        return number.asLong();
    }

    /**
     * Вид числа в байтах: сколько байтов занимает, что помещается и как читается.
     * <p>
     * Одно перечисление на весь язык — см. javadoc класса. Новый вид добавляется сюда
     * и сразу становится доступен и членам {@code bytes}, и {@code Writer},
     * и {@code Reader}: ни одной второй таблицы для него заводить не надо.
     */
    public enum Kind {

        INT8("int8", 1), UINT8("uint8", 1),
        INT16("int16", 2), UINT16("uint16", 2),
        INT32("int32", 4), UINT32("uint32", 4),
        INT64("int64", 8),
        FLOAT32("float32", 4), FLOAT64("float64", 8);

        private final String id;
        private final int size;

        Kind(String id, int size) {
            this.id = id;
            this.size = size;
        }

        /** Имя вида — то самое слово, что стоит суффиксом члена и в тексте ошибки. */
        public String id() {
            return id;
        }

        /** Сколько байтов занимает. */
        public int size() {
            return size;
        }

        /** Целый ли это вид — у дробных диапазон не проверяется. */
        public boolean integral() {
            return this != FLOAT32 && this != FLOAT64;
        }

        /** Вид по имени; промах — ошибка со списком имён, а не молчаливое умолчание. */
        public static Kind of(String name, String what, Span span) {
            for (Kind kind : values()) {
                if (kind.id.equals(name)) {
                    return kind;
                }
            }
            StringBuilder known = new StringBuilder();
            for (Kind kind : values()) {
                known.append(known.isEmpty() ? "" : ", ").append(kind.id);
            }
            throw new WdlRuntimeError(ErrorKind.VALUE, span, what + ": неизвестный вид числа \""
                    + name + "\". Известны: " + known);
        }

        /**
         * Читает число из байтов начиная с этой позиции.
         * <p>
         * Позиция уже проверена вызывающим: чтение за концом — ошибка адресации,
         * и говорить о ней должен {@link Indexes}, тем же текстом, что и {@code b[i]}.
         */
        public Value read(BytesValue data, int at, boolean little) {
            if (this == FLOAT32) {
                return FloatValue.of(Float.intBitsToFloat((int) bits(data, at, little)));
            }
            if (this == FLOAT64) {
                return FloatValue.of(Double.longBitsToDouble(bits(data, at, little)));
            }
            long raw = bits(data, at, little);
            return IntValue.of(switch (this) {
                case INT8 -> (byte) raw;
                case UINT8 -> raw & 0xFFL;
                case INT16 -> (short) raw;
                case UINT16 -> raw & 0xFFFFL;
                case INT32 -> (int) raw;
                case UINT32 -> raw & 0xFFFFFFFFL;
                default -> raw;
            });
        }

        /**
         * Записывает число в массив начиная с этой позиции; диапазон проверяется
         * у целых видов, у дробных — нет.
         */
        public void write(byte[] out, int at, NumberValue value, boolean little,
                          String what, Span span) {
            long bits = switch (this) {
                case FLOAT32 -> Float.floatToIntBits((float) value.asDouble()) & 0xFFFFFFFFL;
                case FLOAT64 -> Double.doubleToLongBits(value.asDouble());
                default -> checked(value, what, span);
            };
            for (int i = 0; i < size; i++) {
                int shift = little ? i * 8 : (size - 1 - i) * 8;
                out[at + i] = (byte) (bits >>> shift);
            }
        }

        private long bits(BytesValue data, int at, boolean little) {
            long raw = 0;
            for (int i = 0; i < size; i++) {
                int octet = data.unsigned(at + i);
                raw |= (long) octet << (little ? i * 8 : (size - 1 - i) * 8);
            }
            return raw;
        }

        /**
         * Целое, влезающее в этот вид.
         * <p>
         * Отказ, а не усечение: язык держит один тип {@code number} ровно затем, чтобы
         * автор не думал про размер машинного слова, и молчаливое усечение вернуло бы
         * ему эту заботу неверным результатом без единого сообщения — то же правило,
         * что на границе с Java ({@code bridge.reflect.Marshal}).
         */
        private long checked(NumberValue value, String what, Span span) {
            if (!value.isInteger()) {
                throw new WdlRuntimeError(ErrorKind.VALUE, span, what + ": ожидался " + id
                        + " (целое), а здесь " + value.display());
            }
            long raw = value.asLong();
            if (raw < min() || raw > max()) {
                throw new WdlRuntimeError(ErrorKind.VALUE, span, what + ": ожидался " + id
                        + " (" + min() + ".." + max() + "), а здесь " + raw);
            }
            return raw;
        }

        private long min() {
            return switch (this) {
                case INT8 -> Byte.MIN_VALUE;
                case INT16 -> Short.MIN_VALUE;
                case INT32 -> Integer.MIN_VALUE;
                case INT64 -> Long.MIN_VALUE;
                default -> 0;
            };
        }

        /** Верхняя граница; у {@code int64} она и есть {@code Long.MAX_VALUE}. */
        private long max() {
            return switch (this) {
                case INT8 -> Byte.MAX_VALUE;
                case UINT8 -> 0xFFL;
                case INT16 -> Short.MAX_VALUE;
                case UINT16 -> 0xFFFFL;
                case INT32 -> Integer.MAX_VALUE;
                case UINT32 -> 0xFFFFFFFFL;
                default -> Long.MAX_VALUE;
            };
        }
    }
}
