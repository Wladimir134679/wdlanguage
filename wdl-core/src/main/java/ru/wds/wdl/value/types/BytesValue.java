package ru.wds.wdl.value.types;

import ru.wds.wdl.value.Value;
import ru.wds.wdl.value.ValueType;

import java.util.Arrays;
import java.util.Objects;

/**
 * Готовая последовательность байтов: то, что прочитали из файла, сокета или ответа,
 * и то, что собираются туда записать.
 * <p>
 * <b>Двенадцатый тип ядра, а не класс из библиотеки</b>, и признак тот же, по которому
 * им стал {@link RangeValue}: на все вопросы, которые тип обязан иметь, ответы есть.
 * {@code len} — число байтов, {@code b[0]} — число, {@code b[1..3]} — срез,
 * {@code ==} — сравнение содержимого, {@code for (x in b)} — обход, {@code println} —
 * короткий вид с длиной. Ни одного места, где пришлось бы соврать. Второй довод —
 * от круга пользователей: байты возвращают {@code sys.io}, {@code sys.net.socket},
 * {@code sys.net.http}, {@code sys.streams} и мост в Java, а тип, который отдают
 * пять модулей, — это словарь языка, а не деталь одного из них.
 *
 * <h2>Неизменяем, и от этого зависит всё остальное</h2>
 * Внутри {@code final byte[]}, наружу он не выходит ни в одном члене. Это покупает
 * сразу три вещи, каждая из которых у изменяемого буфера стоила бы дорого:
 * <ul>
 *   <li><b>потокобезопасность даром</b> — инвариант ядра «изменяемое состояние запуска
 *       потокобезопасно» выполняется без единого замка, тогда как {@code synchronized}
 *       на каждом байте убил бы ровно то, ради чего байты и заводят;</li>
 *   <li><b>{@code ==} по содержимому</b> — законное, как у строки, а не по ссылке,
 *       как у массива. {@code data == bin.hex("89504e47")} — самая частая строка
 *       во всей теме, и у изменяемого значения она всегда была бы ложью;</li>
 *   <li><b>снимок ничего не стоит объяснять</b> — отданные наружу байты уже не изменятся
 *       под читателем.</li>
 * </ul>
 * Место, где байты <i>собирают</i>, — это {@code bin.writer()} из {@code sys.bytes},
 * и разделение здесь ровно то же, что между {@code string} и {@code StringBuilder}:
 * <b>{@code bytes} — это данные, {@code Writer} — это то, где данные собирают.</b>
 *
 * <h2>Байт наружу выходит знаковым</h2>
 * {@code b[i]} — число {@code -128..127}, как в Java. Беззнаковый ответ просят
 * по имени: {@link #unsigned(int)} за {@code b.uint8(i)}. Знаковость — способ чтения,
 * а не свойство значения, поэтому она живёт в имени члена, а не в типе, и скалярного
 * {@code byte} в языке нет. Подробности и цена решения — в {@code docs/bytes.md}.
 *
 * <h2>Печать не выводит содержимое</h2>
 * {@link #display()} даёт длину и превью первых байтов шестнадцатеричным:
 * {@code bytes(1048576: 89 50 4e 47 0d 0a 1a 0a …)}. Полное содержимое — только
 * по явному запросу ({@code b.hex()}), и тогда это осознанное решение того, кто его
 * написал. Hex, а не знаковые числа: бинарные форматы описаны беззнаковым
 * шестнадцатеричным, и отладка должна сравниваться с документацией формата, а не
 * переводить {@code -119} в {@code 0x89} в голове.
 */
public final class BytesValue implements Value {

    /** Пустые байты — один экземпляр: {@code bin.of([])} и срез в ноль байтов частые. */
    public static final BytesValue EMPTY = new BytesValue(new byte[0]);

    /** Сколько байтов показывает {@link #display()} до многоточия. */
    private static final int PREVIEW = 8;

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private final byte[] data;

    /**
     * Кэш хеша, как у {@code String}: ключом байты бывают, а считать хеш мегабайта
     * на каждое обращение к карте незачем. Без {@code volatile} — гонка здесь
     * безобидна, оба потока вычислят одно и то же число.
     */
    private int hash;

    private BytesValue(byte[] owned) {
        this.data = owned;
    }

    /**
     * Байты из копии массива — вход для всех, у кого массив ещё кому-то известен.
     * <p>
     * Копия, а не ссылка: значение обещает неизменяемость, и обещание это стоит
     * ровно столько, сколько стоит уверенность, что массив больше никто не правит.
     */
    public static BytesValue of(byte[] source) {
        Objects.requireNonNull(source, "source");
        return source.length == 0 ? EMPTY : new BytesValue(source.clone());
    }

    /** Байты из части массива — копией по той же причине. */
    public static BytesValue of(byte[] source, int from, int length) {
        Objects.requireNonNull(source, "source");
        return length == 0 ? EMPTY : new BytesValue(Arrays.copyOfRange(source, from, from + length));
    }

    /**
     * Байты поверх массива <b>без копии</b>.
     * <p>
     * Контракт у вызывающего: массив только что создан им самим и больше никому
     * не известен — ни полю, ни аргументу, ни коллекции. Так его отдают декодер hex,
     * чтение файла и снимок буфера: массив там ровно для этого и построен, а лишняя
     * копия мегабайта на каждом чтении — плата за аккуратность, которой никто
     * не заметит, кроме профиля.
     */
    public static BytesValue owning(byte[] owned) {
        Objects.requireNonNull(owned, "owned");
        return owned.length == 0 ? EMPTY : new BytesValue(owned);
    }

    /** Сколько байтов. */
    public int size() {
        return data.length;
    }

    public boolean isEmpty() {
        return data.length == 0;
    }

    /** Байт по готовому индексу — знаковый, {@code -128..127}. */
    public byte at(int index) {
        return data[index];
    }

    /** Тот же байт беззнаковым, {@code 0..255}: за {@code b.uint8(i)}. */
    public int unsigned(int index) {
        return data[index] & 0xFF;
    }

    /**
     * Копия содержимого — то, что уходит наружу: в Java-метод за мостом,
     * в {@code write}, в хеш.
     */
    public byte[] toArray() {
        return data.clone();
    }

    /**
     * Срез — <b>копия</b>, а не окно на тот же массив.
     * <p>
     * Окно было бы безопасно (портить его некому) и бесплатно, но окно на шестнадцать
     * байт удерживало бы в памяти исходные сто мегабайт — ровно та утечка, из-за
     * которой Java в своё время убрала разделяемое хранилище у {@code String.substring}.
     * Наблюдаемой разницы для скрипта нет ни в одном ответе.
     *
     * @param from начало включительно
     * @param to   конец исключительно
     */
    public BytesValue slice(int from, int to) {
        return from >= to ? EMPTY : new BytesValue(Arrays.copyOfRange(data, from, to));
    }

    /** Склейка для {@code a + b}. */
    public static BytesValue concat(BytesValue left, BytesValue right) {
        if (left.isEmpty()) {
            return right;
        }
        if (right.isEmpty()) {
            return left;
        }
        byte[] joined = new byte[left.data.length + right.data.length];
        System.arraycopy(left.data, 0, joined, 0, left.data.length);
        System.arraycopy(right.data, 0, joined, left.data.length, right.data.length);
        return new BytesValue(joined);
    }

    /** Есть ли такой байт — за {@code v in b}. Байт приходит уже проверенным. */
    public boolean contains(byte octet) {
        return indexOf(octet, 0) >= 0;
    }

    /** Где первый такой байт или {@code -1}. */
    public int indexOf(byte octet, int from) {
        for (int i = Math.max(0, from); i < data.length; i++) {
            if (data[i] == octet) {
                return i;
            }
        }
        return -1;
    }

    /** Где первое вхождение этих байтов или {@code -1}; пустое находится в начале. */
    public int indexOf(BytesValue part, int from) {
        byte[] needle = part.data;
        int start = Math.max(0, from);
        if (needle.length == 0) {
            return start <= data.length ? start : -1;
        }
        outer:
        for (int i = start; i + needle.length <= data.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (data[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    /** Начинаются ли эти байты с тех — так сравнивают подпись формата целиком. */
    public boolean startsWith(BytesValue prefix) {
        return prefix.data.length <= data.length
                && Arrays.equals(data, 0, prefix.data.length, prefix.data, 0, prefix.data.length);
    }

    /** Кончаются ли эти байты теми. */
    public boolean endsWith(BytesValue suffix) {
        int at = data.length - suffix.data.length;
        return at >= 0 && Arrays.equals(data, at, data.length, suffix.data, 0, suffix.data.length);
    }

    /** Всё содержимое шестнадцатеричным, строчными буквами и без разделителей. */
    public String hex() {
        char[] out = new char[data.length * 2];
        for (int i = 0; i < data.length; i++) {
            int octet = data[i] & 0xFF;
            out[i * 2] = HEX[octet >>> 4];
            out[i * 2 + 1] = HEX[octet & 0xF];
        }
        return new String(out);
    }

    @Override
    public ValueType type() {
        return ValueType.BYTES;
    }

    /**
     * Длина и превью, а не содержимое: {@code println} мегабайтного значения не должен
     * выводить мегабайт — смотреть его так всё равно нельзя.
     */
    @Override
    public String display() {
        if (data.length == 0) {
            return "bytes(0)";
        }
        StringBuilder out = new StringBuilder(32).append("bytes(").append(data.length).append(':');
        int shown = Math.min(PREVIEW, data.length);
        for (int i = 0; i < shown; i++) {
            int octet = data[i] & 0xFF;
            out.append(' ').append(HEX[octet >>> 4]).append(HEX[octet & 0xF]);
        }
        if (shown < data.length) {
            out.append(" …");
        }
        return out.append(')').toString();
    }

    /**
     * Равенство по содержимому — то, ради чего тип и сделан неизменяемым:
     * подпись файла, магическое число в заголовке, хеш сравнивают именно так.
     */
    @Override
    public boolean equals(Object other) {
        return this == other
                || other instanceof BytesValue that && Arrays.equals(data, that.data);
    }

    @Override
    public int hashCode() {
        int known = hash;
        if (known == 0 && data.length > 0) {
            known = Arrays.hashCode(data);
            hash = known;
        }
        return known;
    }

    @Override
    public String toString() {
        return display();
    }
}
