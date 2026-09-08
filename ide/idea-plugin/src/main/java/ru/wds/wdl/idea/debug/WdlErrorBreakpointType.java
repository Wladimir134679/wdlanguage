package ru.wds.wdl.idea.debug;

import com.intellij.xdebugger.breakpoints.XBreakpoint;
import com.intellij.xdebugger.breakpoints.XBreakpointProperties;
import com.intellij.xdebugger.breakpoints.XBreakpointType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Флажок «Ошибки выполнения wdl» в окне точек останова.
 * <p>
 * Останов на ошибке — не свойство файла и не свойство строки: он либо включён на весь
 * сеанс, либо нет. Поэтому это точка без места — та самая, что в окне точек живёт
 * отдельным пунктом, как «Any exception» у Java.
 * <p>
 * <b>Выключена по умолчанию</b>, и это не осторожность, а честность: поток встаёт
 * на <i>любой</i> ошибке, а ошибка внутри {@code try} — обычное течение скрипта.
 * Отличить пойманную от непойманной движок пока не умеет, и включать такое всем
 * по умолчанию значило бы останавливать отладчик там, где ничего не случилось.
 */
public final class WdlErrorBreakpointType
        extends XBreakpointType<XBreakpoint<XBreakpointProperties>, XBreakpointProperties> {

    /** Идентификатор вида точек; хранится в файле настроек проекта. */
    private static final String ID = "wdl-error";

    public WdlErrorBreakpointType() {
        super(ID, "Ошибки выполнения wdl");
    }

    @Override
    public @NotNull String getDisplayText(XBreakpoint<XBreakpointProperties> breakpoint) {
        return "Любая ошибка выполнения wdl";
    }

    @Override
    public @Nullable XBreakpointProperties createProperties() {
        return null;
    }

    @Override
    public @Nullable XBreakpoint<XBreakpointProperties> createDefaultBreakpoint(
            @NotNull XBreakpointCreator<XBreakpointProperties> creator) {
        XBreakpoint<XBreakpointProperties> breakpoint = creator.createBreakpoint(null);
        breakpoint.setEnabled(false);
        return breakpoint;
    }
}
