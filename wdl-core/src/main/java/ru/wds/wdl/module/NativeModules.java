package ru.wds.wdl.module;

import ru.wds.wdl.module.Library;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Встроенные модули: имя → библиотека, которую отдаёт {@code import}.
 * <p>
 * Модуль на Java — это {@link Library}, положенная не в корневую область, а в свою
 * собственную. Дальше он неотличим от модуля-файла: {@code import sys.json as json},
 * {@code json.parse(...)}, {@code typeof(json)} → {@code module}, опечатка в имени —
 * ошибка, присваивание запрещено. Так вышло потому, что модуль в этом языке с самого
 * начала определён через имена в области, а не через файл, — и кто эти имена положил,
 * интерпретатору знать незачем.
 * <p>
 * <b>Имя, а не путь.</b> Ключ файла считается от каталога того файла, где написан
 * {@code import}: в {@code lib/tools.wdl} запись {@code import sys.json} дала бы ключ
 * {@code lib/sys/json}. У встроенного модуля каталога нет, поэтому он ищется здесь
 * по написанному имени — до разрешения относительного пути. Отсюда и способ явно
 * попросить файл: путь, начинающийся с {@code .} или {@code /}, встроенным не считается
 * никогда (см. {@link ModuleKey#isExplicitPath}).
 * <p>
 * <b>Регистрируется фабрика, а не готовая библиотека.</b> Причин две, и обе важные:
 * <ul>
 *   <li>её зовут при выполнении {@code import} и не зовут никогда, если импорта
 *       не случилось, — то же обещание «за неиспользуемое не платят», что у файлов.
 *       Для встроенного модуля оно весит больше: у него бывает соединение, клиент
 *       или разобранная конфигурация;</li>
 *   <li>один реестр обслуживает много запусков, а библиотека у каждого запуска своя,
 *       поэтому состояние модуля между запусками не течёт.</li>
 * </ul>
 * По умолчанию встроенных модулей нет ({@link #none()}): движок внутри чужого
 * приложения не даёт скрипту ни файлов, ни сети, пока его об этом не попросили.
 * Ограничить набор — значит собрать другой реестр, обходить тут нечего: глобальной
 * таблицы модулей в языке не существует, как нет таблицы функций и таблицы библиотек.
 */
@FunctionalInterface
public interface NativeModules {

    /**
     * Библиотека под этим именем или {@code null}, если такого встроенного модуля нет.
     * <p>
     * Зовётся один раз за запуск на каждое имя: готовое значение модуля дальше берётся
     * из реестра выполненных, как и у файлов.
     */
    Library find(String name);

    /**
     * Пустой реестр: встроенных модулей в этом запуске нет вовсе.
     * <p>
     * Отдельная константа, а не просто лямбда: по ней видно, что о встроенных
     * модулях речи не шло совсем, и сообщение о ненайденном модуле не поминает
     * места, которого нет.
     */
    NativeModules NONE = name -> null;

    /** Встроенных модулей нет — любое имя разрешается как путь к файлу. */
    static NativeModules none() {
        return NONE;
    }

    /**
     * Реестр из карты «имя → фабрика».
     * <p>
     * Имя пишется так же, как ключ модуля: {@code "sys/json"}, {@code "sys/net/http"}.
     * В скрипте это {@code import sys.json} и {@code import sys.net.http} — точка
     * означает каталог и здесь, хотя каталога нет: запись одна на все модули.
     */
    static NativeModules of(Map<String, Supplier<Library>> registry) {
        Map<String, Supplier<Library>> copy = new LinkedHashMap<>(registry);
        copy.forEach((name, factory) -> {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(factory, "factory");
        });
        return name -> {
            Supplier<Library> factory = copy.get(name);
            return factory == null ? null : factory.get();
        };
    }

    /**
     * Два набора подряд: первый отвечает — второй не спрашивают.
     * <p>
     * Нужно приложению, которое добавляет свои модули к готовому набору движка,
     * и ему же — чтобы подменить встроенный модуль своим: свой набор ставится первым.
     */
    static NativeModules first(NativeModules primary, NativeModules fallback) {
        Objects.requireNonNull(primary, "primary");
        Objects.requireNonNull(fallback, "fallback");
        return name -> {
            Library found = primary.find(name);
            return found != null ? found : fallback.find(name);
        };
    }
}
