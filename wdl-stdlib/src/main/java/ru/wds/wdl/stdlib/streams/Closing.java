package ru.wds.wdl.stdlib.streams;

import java.util.Collection;

/**
 * Закрытие нескольких звеньев сразу: {@code zip}, {@code concat}, реестр модуля.
 * <p>
 * Правило то же, по которому {@code Module.close()} переживает упавший обработчик:
 * <b>один упавший не отменяет остальных</b>. Закрытие — последнее, что делает уже
 * отработавший конвейер, и терять на нём открытый файл из-за чужого исключения
 * незачем. Первая ошибка при этом не проглатывается: она уходит наверх, остальные
 * прицепляются к ней {@code addSuppressed}.
 */
final class Closing {

    private Closing() {
    }

    static void all(Collection<? extends AutoCloseable> parts) {
        RuntimeException failure = null;
        for (AutoCloseable part : parts) {
            try {
                part.close();
            } catch (RuntimeException error) {
                if (failure == null) {
                    failure = error;
                } else {
                    failure.addSuppressed(error);
                }
            } catch (Exception impossible) {
                // Source.close() объявлен без throws, и другой реализации здесь нет.
                throw new IllegalStateException(impossible);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
