@file:Suppress("UnstableApiUsage")

import java.io.File

// Read switch from gradle.properties:
// DEPENDENCIES_MODULE=true -> include source modules
// DEPENDENCIES_MODULE=false -> do not include modules; use AAR dependencies instead
val dependenciesModule: Boolean =
    providers.gradleProperty("DEPENDENCIES_MODULE")
        .orNull
        ?.trim()
        ?.equals("true", ignoreCase = true)
        ?: false

// Always-included modules
include(":app")
include(":asr")
include(":grpc")
include(":messagerclient")

// Conditionally include external modules by source
if (dependenciesModule) {
    include(":uiagentlib")
    project(":uiagentlib").projectDir = File("../NLPUIAgent_AndroidDemo/uiagentlib")

    include(":aiboxguiagent")
}

