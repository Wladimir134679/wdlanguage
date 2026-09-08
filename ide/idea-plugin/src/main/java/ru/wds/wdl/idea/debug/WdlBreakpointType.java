package ru.wds.wdl.idea.debug;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.xdebugger.breakpoints.XBreakpointProperties;
import com.intellij.xdebugger.breakpoints.XLineBreakpointType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.wds.wdl.idea.WdlFileType;

/**
 * Точка останова в файле {@code .wdl}: то, что человек ставит щелчком по левому полю
 * редактора.
 * <p>
 * Разрешается щёлкнуть по любой строке, и это не небрежность. Куда точка встанет
 * на самом деле, знает не редактор, а дерево файла: адаптер спрашивает
 * {@code BreakpointPlaces} и отвечает строкой ближайшей инструкции — точка,
 * поставленная на комментарий или на пустую строку, уезжает вниз, как во всех
 * отладчиках. Запрещать здесь значило бы держать в плагине второе, приблизительное
 * знание о том, где в скрипте инструкции.
 */
public final class WdlBreakpointType extends XLineBreakpointType<XBreakpointProperties> {

    /** Идентификатор вида точек; хранится в файле настроек проекта. */
    private static final String ID = "wdl-line";

    public WdlBreakpointType() {
        super(ID, "Точки останова wdl");
    }

    @Override
    public boolean canPutAt(@NotNull VirtualFile file, int line, @NotNull Project project) {
        return WdlFileType.INSTANCE.equals(file.getFileType());
    }

    /**
     * Своих свойств у точки нет: ни условия, ни счёта попаданий отладчик пока не умеет,
     * и хранить для них место значило бы обещать их настройками.
     */
    @Override
    public @Nullable XBreakpointProperties createBreakpointProperties(@NotNull VirtualFile file,
                                                                     int line) {
        return null;
    }
}
