import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform

// Отдельная сборка, а не модуль корневого проекта — намеренно.
//
// Плагину нужен дистрибутив IDE (пара гигабайт) и репозитории JetBrains; затянув
// это в корень, мы заставили бы каждый `./gradlew build` качать IDE ради сборки
// языка, к которому она отношения не имеет. Инвариант «репозитории только
// в settings.gradle.kts» при этом соблюдается сам собой: они объявлены здесь.
//
// Кода wdl плагин не содержит вовсе — он запускает готовый сервер как процесс,
// поэтому и includeBuild корня ему не нужен.

pluginManagement {
    repositories {
        gradlePluginPortal()
    }
}

plugins {
    // Дистрибутивы IDE лежат не в Maven, а в собственных хранилищах JetBrains,
    // и объявляются они этим плагином настроек: руками такой набор не собрать.
    id("org.jetbrains.intellij.platform.settings") version "2.18.1"
}

rootProject.name = "wdl-idea-plugin"

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        intellijPlatform {
            defaultRepositories()
        }
    }
}
