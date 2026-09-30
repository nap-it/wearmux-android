import com.android.build.api.dsl.ApplicationExtension

// Shared by the phone and watch: their package names and signatures must match
// for Wear OS Data Layer communication.
val wearMuxVersionCode = providers.gradleProperty("wearmux.versionCode").get().toIntOrNull()
    ?: throw GradleException("wearmux.versionCode must be a positive integer")
require(wearMuxVersionCode in 1..2_100_000_000) {
    "wearmux.versionCode must be between 1 and 2100000000"
}
val wearMuxVersionName = providers.gradleProperty("wearmux.versionName").get()
require(Regex("[0-9]+\\.[0-9]+\\.[0-9]+").matches(wearMuxVersionName)) {
    "wearmux.versionName must have the form major.minor.patch"
}

val signingEnvironment = listOf(
    "WEARMUX_KEYSTORE_PATH",
    "WEARMUX_KEYSTORE_PASSWORD",
    "WEARMUX_KEY_ALIAS",
    "WEARMUX_KEY_PASSWORD",
).associateWith { providers.environmentVariable(it).orNull }
val configureReleaseSigning = signingEnvironment.values.any { it != null }
if (configureReleaseSigning) {
    val missing = signingEnvironment.filterValues { it.isNullOrBlank() }.keys
    require(missing.isEmpty()) {
        "Incomplete WearMux release signing configuration: missing ${missing.joinToString()}"
    }
    require(rootProject.file(signingEnvironment.getValue("WEARMUX_KEYSTORE_PATH")!!).isFile) {
        "The WearMux release keystore does not exist"
    }
}

extensions.configure<ApplicationExtension> {
    defaultConfig {
        versionCode = wearMuxVersionCode
        versionName = wearMuxVersionName
    }
    if (configureReleaseSigning) {
        val wearMuxSigning = signingConfigs.create("wearmuxRelease") {
            storeFile = rootProject.file(signingEnvironment.getValue("WEARMUX_KEYSTORE_PATH")!!)
            storePassword = signingEnvironment.getValue("WEARMUX_KEYSTORE_PASSWORD")
            keyAlias = signingEnvironment.getValue("WEARMUX_KEY_ALIAS")
            keyPassword = signingEnvironment.getValue("WEARMUX_KEY_PASSWORD")
        }
        buildTypes.getByName("release").signingConfig = wearMuxSigning
    }
}
