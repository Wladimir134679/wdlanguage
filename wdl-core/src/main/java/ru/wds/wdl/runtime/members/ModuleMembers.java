package ru.wds.wdl.runtime.members;

import ru.wds.wdl.value.MemberSet;
import ru.wds.wdl.value.Value;
import ru.wds.wdl.value.types.ModuleValue;
import ru.wds.wdl.value.types.StringValue;

import java.util.ArrayList;
import java.util.List;

/**
 * Члены модуля: имя и список того, что в нём объявлено.
 * <p>
 * Набор короткий по той же причине, что у объекта: имена модуля перекрывают члены
 * (у модуля состав задан его файлом, и {@code m.name} — это имя из модуля, если оно
 * там есть). Надёжный путь — через дескриптор: {@code Module.names(m)}.
 * <p>
 * Ни файла, ни пути здесь нет намеренно: путь — это то, откуда модуль взяли, а
 * взять его могли и не из файла (встроенный {@code sys.io}, модуль от приложения,
 * сгенерированный текст). Обещать скрипту путь значило бы обещать файловую систему.
 */
public final class ModuleMembers {

    private ModuleMembers() {
    }

    static MemberSet set() {
        return MemberSet.builder()
                .property("name", (receiver, context, span) -> StringValue.of(self(receiver).name()))
                .snapshot("names", (receiver, context, span) -> {
                    List<String> names = new ArrayList<>(self(receiver).names());
                    return Introspection.names(names);
                })
                .build();
    }

    private static ModuleValue self(Value receiver) {
        return (ModuleValue) receiver;
    }
}
