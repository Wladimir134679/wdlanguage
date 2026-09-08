package ru.wds.wdl.dap;

/**
 * Просьба клиента, которую выполнить нельзя: неизвестный поток, устаревшая ссылка,
 * шаг в потоке, который не стоит.
 * <p>
 * Отдельный класс, а не {@code IllegalArgumentException}, ради одного: такое
 * исключение адаптер превращает в <b>ответ об ошибке</b> с внятным текстом, а не
 * в аварию транспорта. Клиент при этом остаётся жив и показывает человеку причину —
 * это обычное течение сеанса отладки, а не поломка.
 */
final class DapError extends RuntimeException {

    private static final long serialVersionUID = 1L;

    DapError(String message) {
        super(message);
    }
}
