// Правила: с какой скоростью подавать, куда отскакивать и когда партия сыграна.
//
// Здесь меняют игру, не пересобирая её: сделать подачу быстрее, отскок круче,
// а партию короче — правка трёх чисел в этом файле.

import game as g

const START_SPEED = 320         // скорость первой подачи, пикселей в секунду
const ROUND_BONUS = 12          // прибавка к подаче за каждый сыгранный розыгрыш
const SPEED_UP = 1.04           // во сколько раз мяч ускоряется от каждой ракетки
const MAX_SPEED = 880           // выше этого мяч не разгоняется: иначе он проходит
                                // сквозь ракетку между кадрами
const SPIN = 3.2                // насколько край ракетки закручивает мяч
const SPREAD = 200              // разброс подачи по вертикали

random = new Random()

// Подача. Мяч в этот момент стоит у ракетки подающего, скорость нулевая;
// направление — забота этой функции.
def serve(game, ball) {
    speed = START_SPEED + game.round * ROUND_BONUS
    if (speed > MAX_SPEED) speed = MAX_SPEED

    ball.vx = game.serving == "player" ? speed : -speed
    ball.vy = (random.next() - 0.5) * SPREAD
}

// Отскок от ракетки. Игра уже выставила мяч вплотную к её краю и оставила
// прежний знак скорости — значит здесь видно, откуда мяч пришёл.
def bounce(game, ball, paddle) {
    speed = g.clamp(abs(ball.vx) * SPEED_UP, 0, MAX_SPEED)
    ball.vx = ball.vx < 0 ? speed : -speed

    // Чем дальше от середины ракетки пришёлся удар, тем сильнее уводит мяч.
    // Это единственное, чем игрок управляет отскоком, — и потому главная строка
    // всего файла.
    ball.vy = ball.vy + (ball.centerY - paddle.centerY) * SPIN
}

// Очко засчитано, счёт уже изменён. Вернуть true — значит закончить партию.
def point(game, side) {
    println("Очко: ", game.player, " : ", game.rival)
    return game.player >= game.target || game.rival >= game.target;
}
