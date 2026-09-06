package ru.wds.wdl.idea;

import com.intellij.execution.lineMarker.ExecutorAction;
import com.intellij.execution.lineMarker.RunLineMarkerContributor;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.project.DumbAware;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;

public final class WdlRunLineMarkerContributor extends RunLineMarkerContributor implements DumbAware {
    @Override public Info getInfo(@NotNull PsiElement element) {
        PsiFile file = element.getContainingFile();
        if (file == null || file.getVirtualFile() == null
                || !"wdl".equalsIgnoreCase(file.getVirtualFile().getExtension())
                || element != PsiTreeUtil.getDeepestFirst(file)) return null;
        return new Info(AllIcons.Actions.Execute, ExecutorAction.getActions(0), e -> "Запустить wdl");
    }
}
