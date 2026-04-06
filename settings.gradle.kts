pluginManagement {
    includeBuild("build-logic")
}

rootProject.name = "bara-world"

include(
    ":apps:auth",
    ":libs:common",
)
