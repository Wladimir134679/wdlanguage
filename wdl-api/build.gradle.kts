plugins {
    id("wdl.java-conventions")
    `java-library`
}

description = "Публичный фасад для встраивания: WdlEngine, компиляция скрипта, биндинг Java-объектов, лимиты."

dependencies {
    api(project(":wdl-core"))

    // Стандартная библиотека подключается движком, но не является частью
    // публичного API — потребитель не должен зависеть от неё напрямую.
    implementation(project(":wdl-stdlib"))
}
