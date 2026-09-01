package ru.wds.wdl.bridge.reflect.samples;

import java.util.List;

/** Набор перегрузок для проверки выбора: он и есть предмет теста. */
public class Overloaded {

    public String take(int value) {
        return "int:" + value;
    }

    public String take(long value) {
        return "long:" + value;
    }

    public String take(String value) {
        return "String:" + value;
    }

    public String take(Object value) {
        return "Object:" + value;
    }

    public String only(double value) {
        return "double:" + value;
    }

    /** Ничья: числу подходят оба одинаково. */
    public String tie(int value, Object other) {
        return "int,Object";
    }

    public String tie(Object value, int other) {
        return "Object,int";
    }

    public String join(String separator, Object... parts) {
        StringBuilder result = new StringBuilder();
        for (Object part : parts) {
            if (!result.isEmpty()) {
                result.append(separator);
            }
            result.append(part);
        }
        return result.toString();
    }

    public int count(List<Object> items) {
        return items.size();
    }
}
