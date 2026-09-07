// Пинг-понг: игра целиком на wdl, движок — модуль game (WDGame).
// Запуск: ./gradlew :wdl-game:run --args="examples/game/pong.wdl"
//
// Управление: W/S или стрелки — ракетка, ПРОБЕЛ — подача,
//             R — новая партия, ESC — выход.
//
// Читать файл стоит сверху вниз: сначала мир и объекты на нём, потом правила
// (onUpdate), потом рисование поверх спрайтов (onDraw) и ввод (onKey).
// Движок сам двигает спрайты по их скорости и сам их рисует — скрипту остаётся
// то, что и есть игра: столкновения, счёт и решение соперника.

import game as g

const WIDTH = 800
const HEIGHT = 480
const PADDLE_SPEED = 420        // пикселей в секунду
const RIVAL_SPEED = 260         // соперник заведомо медленнее: иначе у него не выиграть
const BALL_SPEED = 330
const SPIN = 3                  // насколько край ракетки закручивает мяч
const WIN_SCORE = 5

world = new g.World(title: "Пинг-понг — wdl", width: WIDTH, height: HEIGHT,
        background: "#101418", fps: 60)

// Спрайты заводятся по имени: дальше их можно взять у мира (world.get("ball")),
// не таская ссылки аргументами через весь файл.
player = world.spawn("player", x: 24, y: 190, w: 12, h: 100, color: "#7ec8ff")
rival = world.spawn("rival", x: WIDTH - 36, y: 190, w: 12, h: 100, color: "#ff9f6e")
ball = world.spawn("ball", x: 394, y: 234, w: 12, h: 12, color: "#f2f2f2", shape: "oval")

random = new Random()
score = {player: 0, rival: 0}
waiting = true                  // мяч на подаче: ждём пробела
message = "ПРОБЕЛ — подача"

def serve(toRight) {
    ball.moveTo(WIDTH / 2 - 6, HEIGHT / 2 - 6)
    ball.vx = toRight ? BALL_SPEED : -BALL_SPEED
    ball.vy = (random.next() - 0.5) * 240
    waiting = false
    message = ""
}

// Очко: мяч останавливается и ждёт следующей подачи.
def point(who) {
    score[who] += 1
    ball.stop()
    ball.moveTo(WIDTH / 2 - 6, HEIGHT / 2 - 6)
    waiting = true
    if (score[who] >= WIN_SCORE) {
        message = who == "player" ? "ВЫ ВЫИГРАЛИ! R — заново" : "СОПЕРНИК ВЫИГРАЛ. R — заново"
    } else {
        message = "ПРОБЕЛ — подача"
    }
}

def restart() {
    score.player = 0
    score.rival = 0
    player.moveTo(24, 190)
    rival.moveTo(WIDTH - 36, 190)
    ball.stop()
    ball.moveTo(WIDTH / 2 - 6, HEIGHT / 2 - 6)
    waiting = true
    message = "ПРОБЕЛ — подача"
}

// --- правила ----------------------------------------------------------------
// Обработчик получает время кадра в секундах и мир, уже сдвинутый на этот кадр:
// движок двигает спрайты до вызова, а скрипт правит то, что видит.

world.onUpdate(def (dt) {
    // Ракетка игрока: клавишу держат — едем, отпустили — стоим.
    up = world.down("up") || world.down("w")
    down = world.down("down") || world.down("s")
    player.vy = up ? -PADDLE_SPEED : (down ? PADDLE_SPEED : 0)
    player.y = g.clamp(player.y, 0, HEIGHT - player.h)

    // Соперник: тянется к мячу, но со своим пределом скорости. Весь его ум —
    // в этих двух строках, и этого хватает, чтобы игра не была скучной.
    rival.vy = g.clamp((ball.centerY - rival.centerY) * 4, -RIVAL_SPEED, RIVAL_SPEED)
    rival.y = g.clamp(rival.y, 0, HEIGHT - rival.h)

    // Верх и низ поля.
    if (ball.y < 0) {
        ball.y = 0
        ball.vy = -ball.vy
    }
    if (ball.bottom > HEIGHT) {
        ball.y = HEIGHT - ball.h
        ball.vy = -ball.vy
    }

    // Отбитый мяч сначала выставляется за край ракетки, а потом меняет знак
    // скорости: иначе на следующем кадре он снова окажется внутри неё и залипнет.
    if (ball.hits(player) && ball.vx < 0) {
        ball.x = player.right
        ball.vx = -ball.vx
        ball.vy = ball.vy + (ball.centerY - player.centerY) * SPIN
    }
    if (ball.hits(rival) && ball.vx > 0) {
        ball.x = rival.x - ball.w
        ball.vx = -ball.vx
        ball.vy = ball.vy + (ball.centerY - rival.centerY) * SPIN
    }

    // Мяч ушёл за поле — очко.
    if (ball.right < 0) point("rival")
    if (ball.x > WIDTH) point("player")
})

// --- рисование --------------------------------------------------------------
// Спрайты движок нарисовал сам; здесь только то, чего у него нет: сетка, счёт
// и подсказка.

world.onDraw(def (c) {
    // Пунктир по центру — попиксельно, штрихами по шесть точек.
    for (y = 0; y < HEIGHT; y += 12) {
        for (d = 0; d < 6; d += 1) c.pixel(WIDTH / 2, y + d, "#2b3440")
    }

    c.text(WIDTH / 2 - 90, 72, "" + score.player, size: 44, color: "#7ec8ff")
    c.text(WIDTH / 2 + 60, 72, "" + score.rival, size: 44, color: "#ff9f6e")

    if (message != "") {
        c.text(WIDTH / 2 - c.textWidth(message, 20) / 2, HEIGHT - 48, message,
                size: 20, color: "#8d9aa8")
    }
})

// --- ввод -------------------------------------------------------------------
// Опрос (world.down) отвечает на «держат ли клавишу сейчас» и нужен ракетке
// каждый кадр. Событие onKey отвечает на «нажали только что» — им подают мяч
// и выходят из игры.

world.onKey(def (key, pressed) {
    if (pressed) {
        if (key == "escape") world.stop()
        if (key == "space" && waiting) serve(random.next() < 0.5)
        if (key == "r") restart()
    }
})

world.onStart(def () {
    println("Пинг-понг: W/S или стрелки — ракетка, ПРОБЕЛ — подача, R — заново, ESC — выход")
})

world.onStop(def () {
    println("Счёт: ", score.player, " : ", score.rival, ", кадров сыграно: ", world.frames)
})

// Отсюда и до закрытия окна работает движок: кадр за кадром он двигает спрайты,
// зовёт обработчики и показывает картинку. Ошибка внутри обработчика дойдёт сюда
// обычным образом — с местом в этом файле.
world.run()
