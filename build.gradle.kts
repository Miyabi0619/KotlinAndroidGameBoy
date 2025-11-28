import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.jlleitschuh.gradle.ktlint.KtlintExtension
import org.jlleitschuh.gradle.ktlint.reporter.ReporterType as KtlintReporterType

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.detekt) apply false
}

subprojects {
    // Apply ktlint and detekt to every module
    pluginManager.apply("org.jlleitschuh.gradle.ktlint")
    pluginManager.apply("io.gitlab.arturbosch.detekt")

    extensions.configure<KtlintExtension>("ktlint") {
        android.set(true)
        ignoreFailures.set(false)
        reporters {
            reporter(KtlintReporterType.PLAIN)
            reporter(KtlintReporterType.CHECKSTYLE)
        }
        filter {
            exclude("**/generated/**")
            exclude("**/build/**")
        }
    }

    extensions.configure<DetektExtension>("detekt") {
        // Version is kept in gradle/libs.versions.toml for the plugin itself。
        // ここでは明示的に記述しておく（ビルドスクリプト内で libs に依存しないため）。
        toolVersion = "1.23.7"
        buildUponDefaultConfig = true
        allRules = false
        config.setFrom(files("$rootDir/detekt.yml"))
        autoCorrect = false
        basePath = rootProject.projectDir.absolutePath
    }

    tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
        jvmTarget = "11"
        reports {
            html.required.set(true)
            xml.required.set(false)
            txt.required.set(false)
            md.required.set(false)
            sarif.required.set(false)
        }
    }
}