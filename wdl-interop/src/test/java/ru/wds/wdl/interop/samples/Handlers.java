package ru.wds.wdl.interop.samples;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/** Java, которая принимает обработчик: сюда уходят функции скрипта. */
public class Handlers {

    private Runnable kept;

    public String repeat(int times, Runnable body) {
        for (int i = 0; i < times; i++) {
            body.run();
        }
        return "готово";
    }

    public List<Object> map(List<Object> items, Function<Object, Object> step) {
        List<Object> result = new ArrayList<>(items.size());
        for (Object item : items) {
            result.add(step.apply(item));
        }
        return result;
    }

    public List<Object> sorted(List<Object> items, Comparator<Object> order) {
        List<Object> copy = new ArrayList<>(items);
        copy.sort(order);
        return copy;
    }

    /** Обработчик, который переживёт вызов: его позовут потом и из другого потока. */
    public void keep(Runnable body) {
        this.kept = body;
    }

    public void callKept() {
        kept.run();
    }

    /** Вызвать сохранённое из чужого потока — как это делает настоящая библиотека. */
    public void callKeptInThread() throws InterruptedException {
        Thread worker = new Thread(kept, "чужой-поток");
        worker.start();
        worker.join();
    }

    public String twoMethods(Pair pair) {
        return pair.first() + pair.second();
    }

    /** Интерфейс с двумя методами: одной функцией его не закрыть. */
    public interface Pair {

        String first();

        String second();
    }
}
