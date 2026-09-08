package ru.wds.wdl.debug;

/**
 * Поток скрипта глазами отладчика.
 * <p>
 * Идентификатор — {@code Thread.threadId()}: он уникален в процессе и живёт дольше
 * ссылки на поток, а клиенту протокола нужно именно число. Имя — то, которое поток
 * получил при создании: у потоков скрипта это имя из {@code th.spawn("worker-1", …)},
 * и по нему автор скрипта узнаёт свой поток в списке.
 *
 * @param id        идентификатор потока
 * @param name      имя потока
 * @param suspended стоит ли он сейчас
 * @param reason    почему стоит; {@code null}, если работает
 */
public record ThreadInfo(long id, String name, boolean suspended, StopReason reason) {

    @Override
    public String toString() {
        return name + " (" + id + ")" + (suspended ? " — стоит: " + reason.title() : "");
    }
}
