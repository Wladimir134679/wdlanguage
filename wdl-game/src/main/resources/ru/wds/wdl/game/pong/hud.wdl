// Надписи поверх кадра: сетка, счёт и подсказка.
//
// Спрайты движок к этому моменту уже нарисовал — здесь только то, чего у него
// нет. Холст живёт кадром: всё, что не нарисовано сейчас, не появится вовсе.

import game as g

const NET = "#2b3440"
const PLAYER_COLOR = "#7ec8ff"
const RIVAL_COLOR = "#ff9f6e"
const HINT_COLOR = "#8d9aa8"
const SCORE_SIZE = 46
const HINT_SIZE = 20

def draw(game, c) {
    net(game, c)

    middle = game.width / 2
    c.text(middle - 92, 76, "" + game.player, size: SCORE_SIZE, color: PLAYER_COLOR)
    c.text(middle + 58, 76, "" + game.rival, size: SCORE_SIZE, color: RIVAL_COLOR)

    hint = message(game)
    if (hint != "") {
        // Текст по центру: ширину строки считает холст — своей у скрипта нет,
        // шрифт выбирает движок.
        c.text(middle - c.textWidth(hint, HINT_SIZE) / 2, game.height - 46, hint,
                size: HINT_SIZE, color: HINT_COLOR)
    }
}

// Разделительная сетка — попиксельно: штрихи по шесть точек через шесть.
def net(game, c) {
    x = game.width / 2
    for (y = 0; y < game.height; y += 12) {
        for (d = 0; d < 6; d += 1) c.pixel(x, y + d, NET)
    }
}

def message(game) {
    if (game.state == "over") {
        return game.winner == "player" ? "ВЫ ВЫИГРАЛИ! R — заново" : "СОПЕРНИК ВЫИГРАЛ. R — заново";
    }
    if (game.state == "serve") {
        return game.serving == "player" ? "ПРОБЕЛ — подача" : "подаёт соперник";
    }
    return "";
}
