package ru.wds.wdl.idea;

import com.intellij.lang.Language;

/**
 * Язык wdl для платформы.
 * <p>
 * Ни грамматики, ни PSI за ним нет и на этом этапе не планируется: всё, что знает
 * про язык, живёт в языковом сервере. Объект нужен затем, что тип файла в IntelliJ
 * привязывается к языку, а без типа файла редактор не поймёт, к чему относить
 * ответы сервера.
 */
public final class WdlLanguage extends Language {

    public static final WdlLanguage INSTANCE = new WdlLanguage();

    private WdlLanguage() {
        super("wdl");
    }

    @Override
    public String getDisplayName() {
        return "wdl";
    }
}
