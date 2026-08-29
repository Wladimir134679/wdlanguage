plugins {
    id("wdl.java-conventions")
    `java-library`
}

description = "Публичный фасад для встраивания: WdlEngine, компиляция скрипта, биндинг Java-объектов, лимиты."

dependencies {
    api(project(":wdl-core"))

    // Перевод значений через границу с Java живёт в мосте и один на весь проект:
    // фасад Values — его тонкая обёртка, а не второй список правил.
    implementation(project(":wdl-interop"))

    // Стандартная библиотека подключается движком, но не является частью
    // публичного API — потребитель не должен зависеть от неё напрямую.
    implementation(project(":wdl-stdlib"))
}
