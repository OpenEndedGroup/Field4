// Prints field's jvmRuntimeClasspath (one absolute path per line, prefixed "CP\t").
// Usage:
//   ./gradlew -q --init-script tools/print_field_classpath.init.gradle.kts printFieldCp \
//     | sed -n 's/^CP\t//p' > /tmp/fieldcp.txt
// The AA completion spike (tools/code-use-semantic-sidecar CompletionSpike) also appends
// build/classes/kotlin/jvm/main itself, since the runtime config excludes the project's own output.
gradle.projectsEvaluated {
    rootProject.tasks.register("printFieldCp") {
        doLast {
            val cfg = rootProject.configurations.firstOrNull { it.name == "jvmRuntimeClasspath" && it.isCanBeResolved }
                ?: rootProject.configurations.first { it.name.contains("jvmRuntimeClasspath") && it.isCanBeResolved }
            cfg.resolve().forEach { println("CP\t" + it.absolutePath) }
        }
    }
}
