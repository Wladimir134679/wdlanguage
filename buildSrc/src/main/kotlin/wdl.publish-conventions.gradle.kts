/**
 * Публикация модуля в Maven-репозиторий: локальный `~/.m2` или каталог внутри сборки.
 * Подключается вместо `wdl.java-conventions` в модуле, который отдают наружу:
 * plugins { id("wdl.publish-conventions") }
 *
 * Отдельная конвенция, а не строка в общей, потому что публикуются не все модули:
 * `wdl-cli` и `wdl-lsp` — приложения, их раздают дистрибутивом, а не координатами.
 */
plugins {
    id("wdl.java-conventions")
    `maven-publish`
}

java {
    // Исходники уже добавляет wdl.java-conventions; javadoc нужен тому, кто
    // встраивает движок: половина договора между приложением и языком описана там.
    withJavadocJar()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            pom {
                name.set(project.name)
                // Лениво: description модуль задаёт ниже блока plugins {},
                // то есть уже после того, как конвенция применилась.
                description.set(provider { project.description ?: project.name })
            }
        }
    }
    repositories {
        /*
         * Каталог-репозиторий внутри сборки: его можно отдать вместе с checkout,
         * положить на сетевую шару или раздать по HTTP, не поднимая Nexus.
         *
         * Правило «репозитории только в settings.gradle.kts» сюда не относится:
         * FAIL_ON_PROJECT_REPOS запрещает модулю искать здесь зависимости,
         * а это адрес, куда публикуют.
         */
        maven {
            name = "localDir"
            url = uri(rootProject.layout.buildDirectory.dir("repo"))
        }
    }
}
