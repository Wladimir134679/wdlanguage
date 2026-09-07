// Соперник: весь его ум — одно число на кадр, скорость ракетки по вертикали.
//
// Ракетку двигает движок, в поле её держит игра. Скрипту остаётся решение,
// и именно поэтому сложность правится здесь: SPEED — насколько он быстр,
// REACTION — насколько резок, DEAD_ZONE — насколько спокоен.

import game as g

const SPEED = 340               // предел скорости: соперник заведомо медленнее игрока
const IDLE_SPEED = 170          // ... и ещё медленнее, когда возвращается в центр
const REACTION = 5              // насколько резко тянется к цели
const DEAD_ZONE = 6             // мёртвая зона у цели: без неё ракетка дрожит

def speed(game, ball, paddle) {
    gap = aim(game, ball, paddle) - paddle.centerY
    if (abs(gap) < DEAD_ZONE) return 0;

    limit = ball.vx > 0 ? SPEED : IDLE_SPEED
    return g.clamp(gap * REACTION, -limit, limit);
}

// Куда смотреть. Мяч летит от соперника — тот возвращается в центр: так он готов
// к любой подаче. Летит к нему — соперник считает, где мяч окажется у его края.
//
// Отскок от верхней и нижней стены в расчёт не берётся, и это сделано нарочно:
// соперник, считающий стены, не ошибается никогда и выиграть у него нельзя.
def aim(game, ball, paddle) {
    if (ball.vx <= 0) return game.height / 2;

    fly = (paddle.x - ball.right) / ball.vx
    if (fly < 0) return ball.centerY;
    return ball.centerY + ball.vy * fly;
}
