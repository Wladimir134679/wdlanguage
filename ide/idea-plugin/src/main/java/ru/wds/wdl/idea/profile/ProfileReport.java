package ru.wds.wdl.idea.profile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Прочитанный отчёт профилировщика: то, что {@code wdl --profile-out} записал файлом.
 * <p>
 * Модель плагина, а не движка: своих классов {@code wdl-core} плагин не берёт вовсе,
 * иначе его пришлось бы пересобирать под каждую версию языка, а он ставится из ZIP
 * и живёт своей жизнью. Контракт между ними — формат файла, описанный
 * в {@code docs/profiling.md}.
 *
 * @param version    версия формата; читатель обязан отличать «поле пропало» от «файл не тот»
 * @param script     главный файл запуска — так, как его назвали {@code wdl}
 * @param calls      сколько вызовов всего попало в профиль
 * @param selfNanos  сумма собственного времени: то, что складывать можно
 * @param wallNanos  время запуска по часам
 * @param threads    сколько разных потоков что-нибудь позвало
 * @param sites      записи, от самой горячей по «сам» к холодной
 * @param edges      граф вызовов номерами записей
 */
public record ProfileReport(int version, String script, long calls, long selfNanos,
                            long wallNanos, int threads,
                            List<ProfileSite> sites, List<ProfileEdge> edges) {

    /** Версия формата, которую этот плагин умеет читать. */
    public static final int FORMAT_VERSION = 1;

    public ProfileReport {
        sites = List.copyOf(sites);
        edges = List.copyOf(edges);
    }

    /** Скрипт не дошёл до выполнения — окно обязано сказать это словами. */
    public boolean isEmpty() {
        return sites.isEmpty();
    }

    /**
     * Записи верхнего уровня файлов — корни дерева вызовов.
     * <p>
     * Если такой записи нет вовсе (профиль от вызова функции из приложения), корнями
     * становится всё, что никто не звал: дерево без корня показать нельзя.
     */
    public List<Integer> roots() {
        List<Integer> roots = new ArrayList<>();
        for (int index = 0; index < sites.size(); index++) {
            if (sites.get(index).kind().equals("script")) {
                roots.add(index);
            }
        }
        if (!roots.isEmpty()) {
            return roots;
        }
        boolean[] called = new boolean[sites.size()];
        for (ProfileEdge edge : edges) {
            if (edge.callee() >= 0 && edge.callee() < called.length) {
                called[edge.callee()] = true;
            }
        }
        for (int index = 0; index < sites.size(); index++) {
            if (!called[index]) {
                roots.add(index);
            }
        }
        return roots;
    }

    /**
     * Кого звала запись — по убыванию времени.
     * <p>
     * Дерево разворачивается отсюда, узел за узлом: готовое дерево росло бы вместе
     * с запуском (миллион итераций — миллион узлов), а рёбер столько, сколько
     * в коде таких пар.
     */
    public List<ProfileEdge> callees(int site) {
        List<ProfileEdge> children = new ArrayList<>();
        for (ProfileEdge edge : edges) {
            if (edge.caller() == site) {
                children.add(edge);
            }
        }
        children.sort(Collections.reverseOrder(
                java.util.Comparator.comparingLong(ProfileEdge::totalNanos)));
        return children;
    }
}
