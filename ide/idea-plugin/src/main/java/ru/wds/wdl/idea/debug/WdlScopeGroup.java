package ru.wds.wdl.idea.debug;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.xdebugger.frame.XCompositeNode;
import com.intellij.xdebugger.frame.XValueChildrenList;
import com.intellij.xdebugger.frame.XValueGroup;
import org.eclipse.lsp4j.debug.Variable;
import org.jetbrains.annotations.NotNull;

/**
 * Одна панель имён кадра — свёрнутой группой: «Видимые».
 * <p>
 * Группой, а не списком вперемешку с локальными, потому что это разные вещи. Локальные
 * имена завёл сам этот блок; видимые — то, что кадру досталось снаружи: параметры
 * вызова, имена файла и вся корневая область с библиотекой. Свалить их в один список
 * значило бы утопить две переменные скрипта в сотне имён {@code std}.
 * <p>
 * Свёрнута она ещё и по цене: адаптер отвечает на неё обходом всех внешних областей,
 * и платить за это на каждом шаге, пока никто не открыл группу, незачем.
 */
final class WdlScopeGroup extends XValueGroup {

    private final DapSession dap;
    private final int reference;

    WdlScopeGroup(DapSession dap, String name, int reference) {
        super(name);
        this.dap = dap;
        this.reference = reference;
    }

    @Override
    public boolean isAutoExpand() {
        return false;
    }

    @Override
    public void computeChildren(@NotNull XCompositeNode node) {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                XValueChildrenList children = new XValueChildrenList();
                for (Variable variable : dap.variables(reference)) {
                    children.add(new WdlValue(dap, variable));
                }
                node.addChildren(children, true);
            } catch (DapFailure failed) {
                node.setErrorMessage(failed.getMessage());
            }
        });
    }
}
