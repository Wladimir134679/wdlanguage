package ru.wds.wdl.idea.debug;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.project.Project;
import com.intellij.testFramework.LightVirtualFile;
import com.intellij.xdebugger.XExpression;
import com.intellij.xdebugger.XSourcePosition;
import com.intellij.xdebugger.evaluation.EvaluationMode;
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.wds.wdl.idea.WdlFileType;

import java.util.Objects;

/**
 * На каком языке набирают выражение в панели «Вычислить» и в «Watches».
 * <p>
 * На том же, на котором написан скрипт: подсветка и дополнение в этом поле приходят
 * от того же языкового сервера, что и в редакторе. Иначе человек вводил бы выражение
 * wdl в поле, которое считает его простым текстом.
 */
final class WdlEditorsProvider extends XDebuggerEditorsProvider {

    @Override
    public @NotNull FileType getFileType() {
        return WdlFileType.INSTANCE;
    }

    @Override
    public @NotNull Document createDocument(@NotNull Project project, @NotNull XExpression expression,
                                            @Nullable XSourcePosition sourcePosition,
                                            @NotNull EvaluationMode mode) {
        // Файл в памяти, а не на диске: выражение живёт ровно столько, сколько открыто
        // поле ввода, и класть его рядом со скриптом было бы мусором в проекте.
        LightVirtualFile file = new LightVirtualFile("wdl-expression.wdl",
                WdlFileType.INSTANCE, expression.getExpression());
        return Objects.requireNonNull(FileDocumentManager.getInstance().getDocument(file),
                "у файла выражения нет документа");
    }
}
