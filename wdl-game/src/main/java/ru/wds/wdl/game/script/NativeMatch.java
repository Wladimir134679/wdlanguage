package ru.wds.wdl.game.script;

import ru.wds.wdl.bridge.NativeClass;
import ru.wds.wdl.bridge.NativeInstance;
import ru.wds.wdl.game.pong.Match;
import ru.wds.wdl.value.types.FloatValue;
import ru.wds.wdl.value.types.IntValue;
import ru.wds.wdl.value.types.NullValue;
import ru.wds.wdl.value.types.StringValue;

/**
 * Класс {@code Match}: партия глазами скрипта.
 *
 * <pre>{@code
 * def point(match, side) {
 *     println("счёт ", match.player, " : ", match.rival)
 *     return match.player >= match.target || match.rival >= match.target
 * }
 * }</pre>
 *
 * <h2>Только чтение</h2>
 * Ни одного свойства с записью, и это не забывчивость. Счёт ведёт игра: она знает,
 * когда мяч пересёк край поля, и она же обязана ответить за то, что показано
 * на экране. Скрипт решает, <b>что это значит</b> — закончилась ли партия, с какой
 * скоростью подавать дальше, — а не переписывает состояние за спиной у игры.
 * Разрешить запись значило бы завести второе место, где меняется счёт, и рано или
 * поздно эти два места разошлись бы.
 *
 * <h2>Стороны — строки</h2>
 * {@code "player"} и {@code "rival"} вместо отдельного типа: сторон ровно две,
 * сравнивать их скрипт будет с литералом, а тип ради двух значений — это ещё одно
 * имя, которое надо знать, чтобы написать простое условие. По той же причине
 * состояние партии — {@code "serve"}, {@code "play"} или {@code "over"}.
 */
public final class NativeMatch {

    private NativeMatch() {
    }

    /** Класс {@code Match} этого запуска. */
    public static NativeClass build() {
        return NativeClass.named("Match")
                .doc("партия: счёт, чья подача, идёт ли розыгрыш")
                .wrapper(Match.class)

                .property("width", (self, context, span) -> IntValue.of(match(self).width()))
                .doc("ширина поля в пикселях")
                .property("height", (self, context, span) -> IntValue.of(match(self).height()))
                .doc("высота поля в пикселях")

                .property("player", (self, context, span) -> IntValue.of(match(self).player()))
                .doc("счёт игрока")
                .property("rival", (self, context, span) -> IntValue.of(match(self).rival()))
                .doc("счёт соперника")
                .property("target", (self, context, span) -> IntValue.of(match(self).target()))
                .doc("до скольких очков идёт партия")

                .property("round", (self, context, span) -> IntValue.of(match(self).round()))
                .doc("номер розыгрыша с начала партии: им разгоняют подачу")
                .property("rally", (self, context, span) -> IntValue.of(match(self).rally()))
                .doc("сколько раз мяч отбили в текущем розыгрыше")
                .property("time", (self, context, span) -> FloatValue.of(match(self).time()))
                .doc("игровое время партии в секундах")

                .property("state", (self, context, span) ->
                        StringValue.of(match(self).state().title()))
                .doc("что на поле: serve, play или over")
                .property("serving", (self, context, span) ->
                        StringValue.of(match(self).serving().title()))
                .doc("чья подача: player или rival")
                .property("winner", (self, context, span) -> {
                    Match.Side won = match(self).winner();
                    return won == null ? NullValue.NULL : StringValue.of(won.title());
                })
                .doc("победитель партии или null, пока она не сыграна")

                .build();
    }

    private static Match match(NativeInstance self) {
        return self.state(Match.class);
    }
}
