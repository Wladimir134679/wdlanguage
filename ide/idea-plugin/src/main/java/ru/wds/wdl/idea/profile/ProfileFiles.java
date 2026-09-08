package ru.wds.wdl.idea.profile;

import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * Как из записи профиля попасть в редактор.
 * <p>
 * Переход идёт по смещению, а не по имени: {@code offset} записан в единицах UTF-16 —
 * ровно то, чем меряет документ IntelliJ, — и одноимённые функции из разных файлов
 * не путаются.
 */
public final class ProfileFiles {

    private ProfileFiles() {
    }

    /**
     * Файл записи или {@code null}, если его нет на диске.
     * <p>
     * Относительный путь считается от рабочего каталога запуска, а не от корня
     * проекта: главный файл назван в отчёте так, как его дали {@code wdl}
     * в командной строке, и разрешал его процесс от своего рабочего каталога.
     */
    public static VirtualFile locate(String file, Path workingDirectory) {
        if (file == null || file.isBlank()) {
            return null;
        }
        Path path;
        try {
            path = Path.of(file);
        } catch (InvalidPathException e) {
            return null;
        }
        if (!path.isAbsolute() && workingDirectory != null) {
            path = workingDirectory.resolve(path);
        }
        Path resolved = path.normalize();
        LocalFileSystem files = LocalFileSystem.getInstance();
        VirtualFile found = files.findFileByNioFile(resolved);
        // Файл вне проекта в виртуальной файловой системе может ещё не значиться:
        // профиль вправе указывать на модуль, которого редактор не открывал.
        return found != null ? found : files.refreshAndFindFileByNioFile(resolved);
    }

    /**
     * Открывает объявление записи.
     *
     * @return удалось ли: у встроенной функции места в тексте нет вовсе, и делать
     *         вид, что переход есть, было бы неправдой
     */
    public static boolean navigate(Project project, ProfileSite site, Path workingDirectory) {
        if (!site.hasPlace()) {
            return false;
        }
        VirtualFile file = locate(site.file(), workingDirectory);
        if (file == null) {
            return false;
        }
        new OpenFileDescriptor(project, file, site.offset()).navigate(true);
        return true;
    }
}
