plugins {
    id("wdl.publish-conventions")
    `java-library`
}

description = "Публичный фасад для встраивания: WdlEngine, компиляция скрипта, биндинг Java-объектов, лимиты."

dependencies {
    api(project(":wdl-core"))

    // api, а не implementation: без моста встраивание не обходится — приложение
    // отдаёт скрипту свой тип через WdlEngine.Builder.expose(...) и получает
    // FromJava, JavaPolicy, NativeClass прямо в сигнатурах фасада. Прятать модуль,
    // без которого нельзя написать ни одного нетривиального встраивания, значит
    // заставлять каждого потребителя объявлять его второй раз руками.
    api(project(":wdl-bridge"))

    // Стандартная библиотека подключается движком, но не является частью
    // публичного API — потребитель не должен зависеть от неё напрямую.
    implementation(project(":wdl-stdlib"))
}
