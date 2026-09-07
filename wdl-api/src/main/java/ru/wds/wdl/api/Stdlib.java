package ru.wds.wdl.api;

import ru.wds.wdl.module.Library;
import ru.wds.wdl.stdlib.Http;
import ru.wds.wdl.stdlib.Io;
import ru.wds.wdl.stdlib.Json;
import ru.wds.wdl.stdlib.Meta;
import ru.wds.wdl.stdlib.Std;
import ru.wds.wdl.stdlib.Times;
import ru.wds.wdl.stdlib.gui.Gui;
import ru.wds.wdl.stdlib.net.Sockets;
import ru.wds.wdl.stdlib.thread.Threads;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Что из стандартной библиотеки получает скрипт.
 * <p>
 * Набор, а не флаги, и это то же правило, по которому устроены встроенные модули:
 * <b>не положил в набор — модуля не существует</b>. Скрипт не «получает отказ в доступе
 * к файлам», он просто не находит {@code sys.io}, и ошибка у него обычная — «модуль
 * не найден», без разговоров о правах.
 * <p>
 * Перечисление здесь потому, что {@code wdl-stdlib} подключён к {@code wdl-api} как
 * {@code implementation}: приложение, встраивающее движок, на стандартную библиотеку
 * не зависит и назвать {@code Sys.modules()} у себя не может. Пресет — честный способ
 * отдать ему набор, не протаскивая зависимость наружу. Нужен состав, которого здесь
 * нет, — {@link WdlEngine.Builder#module} принимает любую свою {@link Library}.
 */
public enum Stdlib {

    /**
     * Ничего сверх встроенного в язык: {@code println}, {@code len}, {@code typeof}
     * и классы ошибок. {@code import} не найдёт ни одного модуля.
     * <p>
     * Это состояние движка по умолчанию, и выбрано оно намеренно: движок, встроенный
     * в чужое приложение, не открывает ни диска, ни сети, пока его об этом не попросили.
     */
    NONE,

    /**
     * Всё, что не трогает мир снаружи: математика {@code std}, {@code sys.json},
     * {@code sys.meta} и {@code sys.time}.
     * <p>
     * Набор для скрипта, пришедшего от пользователя: считать, разбирать и собирать
     * данные он может, читать файлы и ходить в сеть — нет. Класса {@code File}
     * здесь нет вовсе, поэтому и обойти нечего.
     * <p>
     * {@code sys.thread} сюда <b>не входит</b>, хотя мира и не трогает. Причина в другом:
     * поток — это ресурс процесса, а лимитов выполнения (шаги, таймаут, число потоков)
     * в движке пока нет. Значит, {@code for (;;) th.spawn(...)} в недоверенном скрипте
     * кладёт не скрипт, а хозяина — и до появления лимитов честнее просто не давать
     * этого модуля.
     */
    SAFE,

    /**
     * Весь набор {@code sys}: {@code std}, {@code sys.io}, {@code sys.json},
     * {@code sys.meta}, {@code sys.net.http}, {@code sys.net.socket},
     * {@code sys.time}, {@code sys.gui}, {@code sys.thread} — то же, что перечисляет
     * {@code Sys.registry()} в {@code wdl-stdlib}. Списка два, потому что
     * {@code wdl-stdlib} подключён сюда как {@code implementation} и назвать
     * {@code Sys} в сигнатуре нельзя; совпадение проверяет {@code StdlibTest}.
     * <p>
     * Набор для скрипта, которому доверяют, — своего, лежащего рядом с приложением.
     * Это же берёт консольный {@code wdl}.
     */
    STANDARD;

    /**
     * Модули набора: имя → фабрика.
     * <p>
     * Фабрика, а не готовая библиотека, потому что библиотека принадлежит запуску:
     * она заводит живое ({@code sys.net.http} — клиента) и закрывается вместе с ним.
     * Общая на процесс, она донесла бы состояние из одного скрипта в следующий.
     */
    Map<String, Supplier<Library>> modules() {
        Map<String, Supplier<Library>> modules = new LinkedHashMap<>();
        switch (this) {
            case NONE -> {
            }
            case SAFE -> {
                modules.put("std", Std::library);
                modules.put("sys/json", Json::library);
                // Даты и метаданные декораторов мира не трогают: java.time неизменяем
                // и умеет только арифметику, sys/meta собирает карту по значению,
                // которое скрипт и так держит в руках.
                modules.put("sys/meta", Meta::library);
                modules.put("sys/time", Times::library);
            }
            case STANDARD -> {
                modules.put("std", Std::library);
                modules.put("sys/io", Io::library);
                modules.put("sys/json", Json::library);
                modules.put("sys/meta", Meta::library);
                modules.put("sys/net/http", Http::library);
                modules.put("sys/net/socket", Sockets::library);
                modules.put("sys/time", Times::library);
                modules.put("sys/gui", Gui::library);
                modules.put("sys/thread", Threads::library);
            }
        }
        return modules;
    }

    /**
     * Библиотеки, чьи имена кладутся <b>прямо в корневую область</b>, без {@code import}.
     * <p>
     * Сейчас это {@code std}: {@code sqrt} и {@code File} привычнее без префикса,
     * а разница между «положить имена в корень» и «отдать по {@code import std as s}»
     * только в том, куда библиотеку установили, — сама она об этом не знает.
     */
    Map<String, Supplier<Library>> rootLibraries() {
        Map<String, Supplier<Library>> root = new LinkedHashMap<>();
        if (this != NONE) {
            root.put("std", Std::library);
        }
        return root;
    }
}
