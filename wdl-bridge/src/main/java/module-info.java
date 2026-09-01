/**
 * Всё, что нужно приложению, чтобы дать скрипту свои классы, свои модули и чужие
 * Java-типы. Единственная зависимость встраивателя сверх ядра.
 */
module ru.wds.wdl.bridge {
    requires transitive ru.wds.wdl.core;

    exports ru.wds.wdl.bridge;
    exports ru.wds.wdl.bridge.reflect;
}
