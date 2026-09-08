package ru.wds.wdl.idea.profile;

import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorCustomElementRenderer;
import com.intellij.openapi.editor.Inlay;
import com.intellij.openapi.editor.colors.EditorFontType;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.TextEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.JBColor;
import org.jetbrains.annotations.NotNull;

import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Rectangle;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Числа профиля прямо в редакторе — строкой за концом объявления.
 * <p>
 * Подсказки не спрашивают PSI и не ищут по именам: у записи есть файл и смещение
 * в единицах UTF-16, то есть ровно то, чем меряет документ IntelliJ. Поэтому они
 * встают на место и в файле, который плагин разбирать не умеет.
 * <p>
 * Выключены по умолчанию и включаются в окне профиля: подсказки, оставшиеся
 * от вчерашнего прогона, врут молча.
 */
public final class ProfileInlays {

    private ProfileInlays() {
    }

    /** Перерисовать подсказки во всех открытых редакторах проекта. */
    public static void refresh(@NotNull Project project) {
        if (project.isDisposed()) {
            return;
        }
        for (FileEditor editor : FileEditorManager.getInstance(project).getAllEditors()) {
            if (editor instanceof TextEditor text) {
                refresh(project, text.getEditor());
            }
        }
    }

    /** Перерисовать подсказки в одном редакторе. */
    public static void refresh(@NotNull Project project, @NotNull Editor editor) {
        clear(editor);
        WdlProfileService service = WdlProfileService.getInstance(project);
        WdlProfileService.Run run = service.last();
        if (!service.hintsEnabled() || run == null) {
            return;
        }
        VirtualFile shown = FileDocumentManager.getInstance().getFile(editor.getDocument());
        if (shown == null) {
            return;
        }
        int length = editor.getDocument().getTextLength();
        Map<String, VirtualFile> resolved = new HashMap<>();
        for (ProfileSite site : run.report().sites()) {
            if (!site.hasPlace() || site.offset() > length) {
                continue;
            }
            VirtualFile file = resolved.computeIfAbsent(site.file(),
                    path -> ProfileFiles.locate(path, run.workingDirectory()));
            if (!shown.equals(file)) {
                continue;
            }
            editor.getInlayModel().addAfterLineEndElement(site.offset(), false, new Hint(text(site)));
        }
    }

    private static void clear(Editor editor) {
        List<Inlay<? extends Hint>> ours = editor.getInlayModel()
                .getAfterLineEndElementsInRange(0, editor.getDocument().getTextLength(), Hint.class);
        for (Inlay<? extends Hint> inlay : ours) {
            com.intellij.openapi.util.Disposer.dispose(inlay);
        }
    }

    /** Надпись подсказки: сколько раз звали и сколько ушло на саму работу. */
    static String text(ProfileSite site) {
        return site.calls() + " " + plural(site.calls(), "вызов", "вызова", "вызовов")
                + " · " + ProfilePanel.millis(site.selfNanos()) + " сам";
    }

    private static String plural(long count, String one, String few, String many) {
        long tail = count % 100;
        if (tail >= 11 && tail <= 14) {
            return many;
        }
        return switch ((int) (count % 10)) {
            case 1 -> one;
            case 2, 3, 4 -> few;
            default -> many;
        };
    }

    /** Сама надпись: серая, шрифтом редактора, за концом строки объявления. */
    static final class Hint implements EditorCustomElementRenderer {

        private final String text;

        Hint(String text) {
            this.text = text;
        }

        @Override public int calcWidthInPixels(@NotNull Inlay inlay) {
            return metrics(inlay).stringWidth("  " + text);
        }

        @Override public void paint(@NotNull Inlay inlay, @NotNull Graphics graphics,
                                    @NotNull Rectangle area, @NotNull TextAttributes attributes) {
            Editor editor = inlay.getEditor();
            graphics.setFont(editor.getColorsScheme().getFont(EditorFontType.ITALIC));
            graphics.setColor(JBColor.GRAY);
            graphics.drawString("  " + text, area.x, area.y + metrics(inlay).getAscent());
        }

        private static FontMetrics metrics(Inlay<?> inlay) {
            Font font = inlay.getEditor().getColorsScheme().getFont(EditorFontType.ITALIC);
            return inlay.getEditor().getContentComponent().getFontMetrics(font);
        }
    }
}
