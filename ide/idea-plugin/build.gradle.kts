plugins {
    id("java")
    // Версия объявлена в settings.gradle.kts плагином настроек.
    id("org.jetbrains.intellij.platform")
}

group = "ru.wds.wdl"
version = "0.1.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    intellijPlatform {
        // Клиент LSP есть только в платных IDE JetBrains: в Community и в сборках
        // с открытым кодом этого API нет вовсе. Отсюда и Ultimate, и зависимость
        // от com.intellij.modules.ultimate в plugin.xml.
        intellijIdeaUltimate("2025.3")
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild.set("253")
            untilBuild.set(provider { null })
        }
    }
    pluginVerification {
        ides {
            recommended()
        }
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

// Своих настроек у плагина нет, а задача ради их индексации поднимает целую IDE.
tasks.named("buildSearchableOptions") {
    enabled = false
}
