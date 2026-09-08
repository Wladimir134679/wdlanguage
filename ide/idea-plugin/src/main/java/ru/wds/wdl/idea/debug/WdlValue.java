package ru.wds.wdl.idea.debug;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.xdebugger.frame.XCompositeNode;
import com.intellij.xdebugger.frame.XNamedValue;
import com.intellij.xdebugger.frame.XValueChildrenList;
import com.intellij.xdebugger.frame.XValueNode;
import com.intellij.xdebugger.frame.XValuePlace;
import org.eclipse.lsp4j.debug.Variable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;

/**
 * Значение в панели переменных.
 * <p>
 * Своего представления значений плагин не строит: строку, тип и признак «есть дети»
 * называет адаптер, а он берёт их у самого языка ({@code Value.display()}). Иначе
 * панель показывала бы одно, а {@code println} того же значения печатал бы другое.
 * <p>
 * Дети достаются <b>по требованию</b> и в пуле, а не при построении узла. Причина
 * не в скорости панели: раскрыть значение — это запрос к адаптеру, а тот отвечает
 * из остановленного потока скрипта. Спросить его из потока событий IDEA значило бы
 * заморозить интерфейс на время ответа.
 */
final class WdlValue extends XNamedValue {

    private final DapSession dap;
    private final Variable variable;

    WdlValue(DapSession dap, Variable variable) {
        super(variable.getName() == null ? "" : variable.getName());
        this.dap = dap;
        this.variable = variable;
    }

    @Override
    public void computePresentation(@NotNull XValueNode node, @NotNull XValuePlace place) {
        node.setPresentation(iconOf(variable.getType()), variable.getType(),
                variable.getValue() == null ? "null" : variable.getValue(), expandable());
    }

    @Override
    public void computeChildren(@NotNull XCompositeNode node) {
        if (!expandable()) {
            node.addChildren(XValueChildrenList.EMPTY, true);
            return;
        }
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                XValueChildrenList children = new XValueChildrenList();
                for (Variable child : dap.variables(variable.getVariablesReference())) {
                    children.add(new WdlValue(dap, child));
                }
                node.addChildren(children, true);
            } catch (DapFailure failed) {
                node.setErrorMessage(failed.getMessage());
            }
        });
    }

    /**
     * Выражение, которым это значение можно назвать в «Watches».
     * <p>
     * Его придумывает адаптер: имя переменной осмысленно только там, где она видна,
     * и добавлять к нему путь вроде {@code items[0]} плагин не вправе — элемент
     * массива к этой минуте может быть уже другим.
     */
    @Override
    public @Nullable String getEvaluationExpression() {
        return variable.getEvaluateName();
    }

    private boolean expandable() {
        return variable.getVariablesReference() > 0;
    }

    /** Значок по типу: массив и объект видно в панели до раскрытия. */
    private static Icon iconOf(@Nullable String type) {
        if (type == null) {
            return AllIcons.Debugger.Value;
        }
        return switch (type) {
            case "array" -> AllIcons.Debugger.Db_array;
            case "function" -> AllIcons.Nodes.Method;
            case "class" -> AllIcons.Nodes.Class;
            case "trait" -> AllIcons.Nodes.Interface;
            // Всё остальное — обычное значение: число, строка, объект, экземпляр класса
            // (у него в этом поле стоит имя класса, и списком его не перечислить).
            default -> AllIcons.Debugger.Value;
        };
    }
}
