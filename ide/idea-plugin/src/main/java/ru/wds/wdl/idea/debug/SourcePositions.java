package ru.wds.wdl.idea.debug;

import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.xdebugger.XDebuggerUtil;
import com.intellij.xdebugger.XSourcePosition;
import org.jetbrains.annotations.Nullable;

import java.io.File;

/**
 * Место в файле: перевод между тем, как его называет протокол, и тем, как его называет
 * IDEA.
 * <p>
 * Различий два, и оба легко забыть. <b>Строки</b>: в DAP они нумеруются с единицы (мы
 * так и договариваемся в {@code initialize}), в {@code XSourcePosition} — с нуля.
 * <b>Пути</b>: адаптер отдаёт путь этой системы ({@code D:\project\main.wdl}), а
 * виртуальная файловая система IDEA знает только косые вперёд. Оба перевода живут
 * здесь одним местом — иначе однажды точка станет на строку выше, и искать это будут
 * в трёх файлах сразу.
 */
final class SourcePositions {

    private SourcePositions() {
    }

    /** Файл IDEA по пути от адаптера или {@code null}, если такого файла не видно. */
    static @Nullable VirtualFile fileOf(@Nullable String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        return LocalFileSystem.getInstance().findFileByPath(path.replace(File.separatorChar, '/'));
    }

    /** Место по пути и строке протокола (с единицы); {@code null}, если файла не видно. */
    static @Nullable XSourcePosition of(@Nullable String path, int line) {
        VirtualFile file = fileOf(path);
        return file == null ? null
                : XDebuggerUtil.getInstance().createPosition(file, Math.max(line - 1, 0));
    }

    /** Строка для протокола: у точки останова IDEA она нумеруется с нуля. */
    static int protocolLine(int ideaLine) {
        return ideaLine + 1;
    }

    /** Путь для протокола: тот же файл, но именем этой системы. */
    static String protocolPath(VirtualFile file) {
        return file.toNioPath().toString();
    }
}
