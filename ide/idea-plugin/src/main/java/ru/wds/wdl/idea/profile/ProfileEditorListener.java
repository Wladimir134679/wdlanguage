package ru.wds.wdl.idea.profile;

import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.fileEditor.TextEditor;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

/**
 * Файл, открытый после прогона, тоже должен показать числа.
 * <p>
 * Подсказки живут в самом редакторе, а не в проходе анализатора, поэтому вешать их
 * надо на открытие файла: иначе профиль виден только там, где он был снят.
 */
public final class ProfileEditorListener implements FileEditorManagerListener {

    @Override public void fileOpened(@NotNull FileEditorManager manager, @NotNull VirtualFile file) {
        if (!WdlProfileService.getInstance(manager.getProject()).hintsEnabled()) {
            return;
        }
        for (var editor : manager.getAllEditors(file)) {
            if (editor instanceof TextEditor text) {
                ProfileInlays.refresh(manager.getProject(), text.getEditor());
            }
        }
    }
}
