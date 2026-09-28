pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version ("1.0.0")
}
rootProject.name = "afield"

include(":tools:linalg-generator")
include(":tools:linalg-bench")
include(":tools:imaging-bench")

// --- Honour `.field-backdoor/verification.lock` -------------------------------------------------
// The smoke, UI-regression and docs wrappers (tools/verification_runtime.py `verification_lock`)
// serialise themselves on this file because two builds in one checkout corrupt shared Kotlin class
// output and incremental-compiler backups. A raw `./gradlew` used to ignore it, so AGENTS.md asked
// agents not to run one alongside a wrapper. This makes Gradle ask instead: while another live
// process holds the lock, wait (printing the owner) or, with AFIELD_VERIFICATION_LOCK=fail, stop.
//
// - Builds the lock owner spawns carry AFIELD_VERIFICATION_LOCK_OWNER=<owner pid> and go straight
//   through; that is how the wrapper's own `runJvm` avoids waiting on itself.
// - A lock whose pid is dead, or whose pid was reused by a process started after the lock was
//   taken, is stale and ignored. The file is only telemetry: the real lock is the owner's flock.
// - AFIELD_VERIFICATION_LOCK=off disables the check; IDE sync never waits.
// - AFIELD_VERIFICATION_LOCK_WAIT_SEC bounds the wait (default 1800), after which the build fails.
run {
    val mode = providers.environmentVariable("AFIELD_VERIFICATION_LOCK").orNull?.trim()?.lowercase() ?: "wait"
    val ideSync = System.getProperty("idea.sync.active") == "true"
    val lockFile = settingsDir.resolve(".field-backdoor/verification.lock")
    if (mode != "off" && !ideSync && lockFile.isFile) {
        val ownerMarker = providers.environmentVariable("AFIELD_VERIFICATION_LOCK_OWNER").orNull?.trim()
        val maxWaitSec = providers.environmentVariable("AFIELD_VERIFICATION_LOCK_WAIT_SEC").orNull
            ?.toLongOrNull() ?: 1800L

        /** The live other owner's description, or null when the lock is free, stale, or ours. */
        fun liveForeignOwner(): String? {
            val text = runCatching { lockFile.readText() }.getOrNull()?.trim().orEmpty()
            if (text.isEmpty()) return null
            val pid = Regex("\"pid\"\\s*:\\s*(\\d+)").find(text)?.groupValues?.get(1)?.toLongOrNull() ?: return null
            if (pid.toString() == ownerMarker || pid == ProcessHandle.current().pid()) return null
            val handle = ProcessHandle.of(pid).orElse(null) ?: return null
            if (!handle.isAlive) return null
            val started = Regex("\"started\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1)
            val lockTakenAt = started?.let {
                runCatching {
                    java.time.OffsetDateTime.parse(
                        it, java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxx")
                    ).toInstant()
                }.getOrNull()
            }
            val processStart = handle.info().startInstant().orElse(null)
            // A pid reused by a process that began after the lock was written is not the owner.
            if (lockTakenAt != null && processStart != null && processStart.isAfter(lockTakenAt.plusSeconds(2))) return null
            return text
        }

        var owner = liveForeignOwner()
        if (owner != null) {
            val offHint = "Set AFIELD_VERIFICATION_LOCK=off to build anyway (risks corrupting this " +
                "checkout's Kotlin outputs)"
            val hint = "$offHint, or AFIELD_VERIFICATION_LOCK=fail to stop instead of waiting."
            if (mode == "fail") {
                throw GradleException(
                    "This checkout's verification lock is held by $owner -- a smoke/UI/docs " +
                        "verification is building from these outputs right now. Wait for it, " +
                        "or use another checkout. $offHint."
                )
            }
            val startedAt = System.nanoTime()
            var nextReport = 0L
            while (owner != null) {
                val waited = (System.nanoTime() - startedAt) / 1_000_000_000L
                if (waited >= maxWaitSec) {
                    throw GradleException(
                        "Gave up after ${waited}s waiting for this checkout's verification lock, " +
                            "held by $owner. $hint"
                    )
                }
                if (waited >= nextReport) {
                    println("WAIT gradle: verification lock held by $owner; waited ${waited}s (limit ${maxWaitSec}s). $hint")
                    nextReport = waited + 15
                }
                Thread.sleep(500)
                owner = liveForeignOwner()
            }
            println("gradle: verification lock released; continuing.")
        }
    }
}
