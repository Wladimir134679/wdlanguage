package ru.wds.wdl.bridge.reflect;

import ru.wds.wdl.source.Span;
import ru.wds.wdl.value.CallContext;
import ru.wds.wdl.value.Value;

import java.util.List;

/**
 * Как добраться до значения за именем: публичным полем или парой аксессоров.
 * <p>
 * Один интерфейс на оба случая, потому что для свойства языка разницы нет: оно
 * читается, иногда пишется и не знает, что стоит с той стороны. Пока реализаций
 * было две, рядом жили два почти одинаковых {@code Property} — с одинаковой
 * проверкой получателя, одинаковым переводом результата и разными только двумя
 * строками посередине.
 */
interface JavaAccess {

    /** Можно ли писать: у {@code final} поля и у пары без сеттера — нет. */
    boolean writable();

    Object read(Object self, Marshal marshal, String subject, CallContext context, Span span);

    void write(Object self, Value value, Marshal marshal, String subject,
               CallContext context, Span span);

    /** Пара {@code getX()/setX(v)} как доступ к значению. */
    static JavaAccess of(JavaShape.Bean bean) {
        return new Bean(bean);
    }

    record Bean(JavaShape.Bean pair) implements JavaAccess {

        @Override
        public boolean writable() {
            return pair.setter() != null;
        }

        @Override
        public Object read(Object self, Marshal marshal, String subject,
                           CallContext context, Span span) {
            return pair.getter().invoke(self, List.of(), marshal, subject, context, span);
        }

        @Override
        public void write(Object self, Value value, Marshal marshal, String subject,
                          CallContext context, Span span) {
            pair.setter().invoke(self, List.of(value), marshal, subject, context, span);
        }
    }
}
