package ru.wds.wdl.idea.debug;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.ui.ColoredTextContainer;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.xdebugger.XSourcePosition;
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator;
import com.intellij.xdebugger.frame.XCompositeNode;
import com.intellij.xdebugger.frame.XStackFrame;
import com.intellij.xdebugger.frame.XValueChildrenList;
import org.eclipse.lsp4j.debug.EvaluateResponse;
import org.eclipse.lsp4j.debug.Scope;
import org.eclipse.lsp4j.debug.StackFrame;
import org.eclipse.lsp4j.debug.Variable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Кадр вызова: строка в панели кадров, её переменные и вычисление в ней.
 * <p>
 * Кадр — это <b>адрес</b>, выданный адаптером, и живёт он от остановки до
 * возобновления: после шага тот же вызов — уже другой кадр. Поэтому здесь ничего
 * не кэшируется: и переменные, и вычисление спрашиваются заново каждый раз, когда
 * их показывают.
 */
final class WdlStackFrame extends XStackFrame {

    private final DapSession dap;
    private final StackFrame frame;
    private final XSourcePosition position;

    WdlStackFrame(DapSession dap, StackFrame frame) {
        this.dap = dap;
        this.frame = frame;
        this.position = SourcePositions.of(
                frame.getSource() == null ? null : frame.getSource().getPath(), frame.getLine());
    }

    @Override
    public @Nullable XSourcePosition getSourcePosition() {
        return position;
    }

    /**
     * Что отличает один кадр от другого, когда панель перерисовывается.
     * <p>
     * Имя и место, а не выданный адаптером номер: номер у каждой остановки новый,
     * и по нему платформа считала бы все кадры сменившимися — панель схлопывалась бы
     * на каждом шаге, теряя раскрытые узлы.
     */
    @Override
    public @Nullable Object getEqualityObject() {
        return frame.getName() + "@" + (frame.getSource() == null ? "" : frame.getSource().getPath());
    }

    @Override
    public void customizePresentation(@NotNull ColoredTextContainer component) {
        component.append(frame.getName(), SimpleTextAttributes.REGULAR_ATTRIBUTES);
        if (frame.getSource() != null) {
            component.append(" (" + frame.getSource().getName() + ":" + frame.getLine() + ")",
                    SimpleTextAttributes.GRAYED_ATTRIBUTES);
        }
        component.setIcon(AllIcons.Debugger.Frame);
    }

    /**
     * Переменные кадра: локальные — списком, остальные панели — свёрнутой группой.
     * <p>
     * В пуле, а не здесь же: за ответом адаптер идёт в остановленный поток скрипта.
     */
    @Override
    public void computeChildren(@NotNull XCompositeNode node) {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                XValueChildrenList children = new XValueChildrenList();
                boolean first = true;
                for (Scope scope : dap.scopes(frame.getId())) {
                    if (first) {
                        for (Variable variable : dap.variables(scope.getVariablesReference())) {
                            children.add(new WdlValue(dap, variable));
                        }
                        first = false;
                    } else {
                        children.addBottomGroup(new WdlScopeGroup(dap, scope.getName(),
                                scope.getVariablesReference()));
                    }
                }
                node.addChildren(children, true);
            } catch (DapFailure failed) {
                node.setErrorMessage(failed.getMessage());
            }
        });
    }

    /**
     * Вычисление в этом кадре: панель «Вычислить», «Watches» и подсказка при наведении.
     * <p>
     * Считает выражение сам остановленный поток скрипта — так устроен движок, и это
     * то, ради чего вычисление привязано к кадру, а не к сеансу.
     */
    @Override
    public @Nullable XDebuggerEvaluator getEvaluator() {
        return new XDebuggerEvaluator() {
            @Override
            public void evaluate(@NotNull String expression, @NotNull XEvaluationCallback callback,
                                 @Nullable XSourcePosition expressionPosition) {
                ApplicationManager.getApplication().executeOnPooledThread(() -> {
                    try {
                        EvaluateResponse answer = dap.evaluate(frame.getId(), expression, "watch");
                        callback.evaluated(new WdlValue(dap, asVariable(expression, answer)));
                    } catch (DapFailure failed) {
                        callback.errorOccurred(failed.getMessage());
                    }
                });
            }
        };
    }

    /** Ответ на вычисление — тем же узлом, что и переменная: раскрывается он так же. */
    private static Variable asVariable(String expression, EvaluateResponse answer) {
        Variable variable = new Variable();
        variable.setName(expression);
        variable.setValue(answer.getResult());
        variable.setType(answer.getType());
        variable.setVariablesReference(answer.getVariablesReference());
        variable.setEvaluateName(expression);
        return variable;
    }
}
