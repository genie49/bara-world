pluginManagement {
    includeBuild("build-logic")
}

rootProject.name = "bara-world"

include(
    ":apps:auth",
    ":apps:api",
    ":libs:common",
    ":libs:common-test",
)
