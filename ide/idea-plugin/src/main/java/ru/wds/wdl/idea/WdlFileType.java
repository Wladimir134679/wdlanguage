package ru.wds.wdl.idea;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.fileTypes.LanguageFileType;
import org.jetbrains.annotations.NotNull;

import javax.swing.Icon;

/**
 * Файл {@code .wdl}.
 * <p>
 * После установки плагина внешняя настройка типа файла из
 * {@code setting ide/idea/filetypes/wdlang.xml} больше не нужна: она делала то же
 * самое руками и не умела ничего, кроме списка расширений.
 */
public final class WdlFileType extends LanguageFileType {

    public static final WdlFileType INSTANCE = new WdlFileType();

    private WdlFileType() {
        super(WdlLanguage.INSTANCE);
    }

    @Override
    public @NotNull String getName() {
        return "wdl";
    }

    @Override
    public @NotNull String getDescription() {
        return "Скрипт wdl";
    }

    @Override
    public @NotNull String getDefaultExtension() {
        return "wdl";
    }

    @Override
    public Icon getIcon() {
        // Своей иконки пока нет: рисовать её раньше, чем работает подсветка, незачем.
        return AllIcons.FileTypes.Text;
    }
}
