package ru.wds.wdl.api.samples;

import java.util.ArrayList;
import java.util.List;

/**
 * Приложение, в которое встраивают язык: игровой движок с состоянием.
 * <p>
 * Тип чужой для языка целиком — ни одного класса wdl в сигнатурах. Ровно так
 * выглядит то, что встраиватель отдаёт скрипту: свой готовый объект, который
 * он не собирается переписывать под построитель.
 */
public class Game {

    private final List<String> spawned = new ArrayList<>();

    private int frame;

    public void spawn(String kind, int x, int y) {
        spawned.add(kind + "@" + x + "," + y);
    }

    public int tick() {
        return ++frame;
    }

    public List<String> getSpawned() {
        return spawned;
    }
}
