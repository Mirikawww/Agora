package com.newoether.agora.sandbox

import android.util.Log
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Install a custom root filesystem from a local archive, modeled after
 * proot-distro's [install_local](https://github.com/termux/proot-distro) flow:
 *
 * - Plain rootfs tarball (optionally gzipped), with strip-count heuristic
 * - OCI image layout archive (`oci-layout` + `index.json` + `blobs/…`)
 * - Docker `docker save` archive (`manifest.json` + layer tars)
 *
 * Layers are applied with OCI whiteout handling (`.wh.*` / opaque dirs).
 */
object RootfsArchiveInstaller {

    private const val TAG = "RootfsArchiveInstaller"

    private val rootfsDirs = setOf(
        "bin", "dev", "etc", "home", "lib", "lib32", "lib64", "libx32",
        "media", "mnt", "opt", "proc", "root", "run", "sbin", "srv",
        "sys", "tmp", "usr", "var",
    )

    data class Result(
        val kind: Kind,
        val imageRef: String? = null,
        val architecture: String? = null,
        val layerCount: Int = 0,
    )

    enum class Kind { PLAIN_ROOTFS, OCI_LAYOUT, DOCKER_SAVE }

    fun install(archive: File, rootfsDir: File, preferredArch: String = "arm64", onProgress: (String) -> Unit = {}): Result {
        require(archive.isFile) { "Archive not found: ${archive.absolutePath}" }
        if (rootfsDir.exists()) {
            rootfsDir.deleteRecursively()
            if (rootfsDir.exists()) error("Cannot delete stale rootfs")
        }
        rootfsDir.mkdirs()

        val staging = File(archive.parentFile ?: rootfsDir.parentFile, "rootfs-import-staging-${System.currentTimeMillis()}")
        try {
            staging.deleteRecursively()
            staging.mkdirs()
            onProgress("Unpacking archive…")
            extractOuterArchive(archive, staging)

            return when {
                File(staging, "oci-layout").isFile || File(staging, "index.json").isFile -> {
                    onProgress("Detected OCI image layout…")
                    installFromOciLayout(staging, rootfsDir, preferredArch, onProgress)
                }
                File(staging, "manifest.json").isFile -> {
                    onProgress("Detected Docker save archive…")
                    installFromDockerSave(staging, rootfsDir, preferredArch, onProgress)
                }
                else -> {
                    onProgress("Detected plain rootfs tarball…")
                    installFromPlainStaging(staging, rootfsDir, onProgress)
                }
            }
        } finally {
            staging.deleteRecursively()
        }
    }

    // ── Outer archive (tar / tar.gz) ─────────────────────────────────────

    private fun extractOuterArchive(archive: File, destDir: File) {
        openTarStream(archive).use { tar ->
            extractTarEntries(tar, destDir, strip = 0, handleWhiteouts = false)
        }
    }

    private fun openTarStream(file: File): TarArchiveInputStream {
        val raw = BufferedInputStream(FileInputStream(file), 64 * 1024)
        val magic = ByteArray(6)
        raw.mark(16)
        val n = raw.read(magic)
        raw.reset()
        val stream: InputStream = when {
            n >= 2 && magic[0] == 0x1f.toByte() && magic[1] == 0x8b.toByte() ->
                try {
                    GzipCompressorInputStream(raw)
                } catch (_: Throwable) {
                    GZIPInputStream(raw)
                }
            else -> raw
        }
        return TarArchiveInputStream(stream)
    }

    // ── Plain rootfs ─────────────────────────────────────────────────────

    private fun installFromPlainStaging(staging: File, rootfsDir: File, onProgress: (String) -> Unit): Result {
        val names = staging.walkTopDown()
            .filter { it != staging }
            .map { it.relativeTo(staging).path.replace('\\', '/') }
            .take(500)
            .toList()
        val strip = detectStripCount(names)
        onProgress("Applying rootfs (strip=$strip)…")
        moveStagingIntoRootfs(staging, rootfsDir, strip)
        return Result(kind = Kind.PLAIN_ROOTFS)
    }

    private fun detectStripCount(memberNames: List<String>): Int {
        var bestStrip = 0
        var bestScore = -1
        for (strip in 0..4) {
            var score = 0
            for (name in memberNames) {
                val parts = name.trim('/').split('/').filter { it.isNotEmpty() && it != "." }
                if (parts.size > strip && parts[strip] in rootfsDirs) score++
            }
            if (score > bestScore) {
                bestScore = score
                bestStrip = strip
            }
        }
        return bestStrip
    }

    private fun moveStagingIntoRootfs(staging: File, rootfsDir: File, strip: Int) {
        staging.walkTopDown().forEach { src ->
            if (src == staging) return@forEach
            val rel = src.relativeTo(staging).path.replace('\\', '/')
            val parts = rel.split('/').filter { it.isNotEmpty() && it != "." }
            if (parts.size <= strip) return@forEach
            val destRel = parts.drop(strip).joinToString("/")
            if (destRel.isEmpty() || destRel.contains("..")) return@forEach
            val dest = safeChild(rootfsDir, destRel) ?: return@forEach
            if (src.isDirectory) {
                dest.mkdirs()
            } else if (src.isFile) {
                dest.parentFile?.mkdirs()
                src.copyTo(dest, overwrite = true)
                if (src.canExecute()) dest.setExecutable(true, false)
            }
        }
    }

    // ── OCI layout ───────────────────────────────────────────────────────

    private fun installFromOciLayout(
        layoutDir: File,
        rootfsDir: File,
        preferredArch: String,
        onProgress: (String) -> Unit,
    ): Result {
        val indexFile = File(layoutDir, "index.json")
        if (!indexFile.isFile) error("OCI archive missing index.json")
        val index = JSONObject(indexFile.readText())
        val manifests = index.optJSONArray("manifests") ?: error("OCI index.json has no manifests")
        if (manifests.length() == 0) error("OCI index.json contains no manifests")

        val manifestEntry = pickOciManifestEntry(layoutDir, manifests, preferredArch)
        val manifestDigest = manifestEntry.getString("digest")
        val manifest = readOciJson(layoutDir, manifestDigest)
        val configDigest = manifest.optJSONObject("config")?.optString("digest").orEmpty()
        if (configDigest.isBlank()) error("OCI image manifest has no config digest")
        val config = readOciJson(layoutDir, configDigest)
        val arch = config.optString("architecture").ifBlank { preferredArch }
        val layers = manifest.optJSONArray("layers") ?: error("OCI image manifest has no layers")
        if (layers.length() == 0) error("OCI image manifest contains no layers")

        for (i in 0 until layers.length()) {
            val layer = layers.getJSONObject(i)
            val digest = layer.getString("digest")
            val shortId = digest.take(19)
            onProgress("Applying layer ${i + 1}/${layers.length()} ($shortId)…")
            val blob = ociBlobFile(layoutDir, digest)
            applyLayerArchive(blob, rootfsDir)
        }

        val annotations = manifestEntry.optJSONObject("annotations")
        val imageRef = annotations?.optString("io.containerd.image.name")
            ?.ifBlank { null }
            ?: annotations?.optString("org.opencontainers.image.ref.name")?.ifBlank { null }

        return Result(
            kind = Kind.OCI_LAYOUT,
            imageRef = imageRef,
            architecture = arch,
            layerCount = layers.length(),
        )
    }

    private fun pickOciManifestEntry(layoutDir: File, manifests: JSONArray, preferredArch: String): JSONObject {
        if (manifests.length() == 1) return manifests.getJSONObject(0)
        val dockerArch = preferredArch // arm64

        for (i in 0 until manifests.length()) {
            val entry = manifests.getJSONObject(i)
            val platform = entry.optJSONObject("platform") ?: continue
            if (platform.optString("os", "linux") != "linux") continue
            val arch = platform.optString("architecture")
            if (arch == dockerArch || (dockerArch == "arm64" && arch == "aarch64")) return entry
        }

        // Slow path: inspect config blobs
        for (i in 0 until manifests.length()) {
            val entry = manifests.getJSONObject(i)
            val digest = entry.optString("digest")
            if (digest.isBlank()) continue
            try {
                val manifest = readOciJson(layoutDir, digest)
                val configDigest = manifest.optJSONObject("config")?.optString("digest").orEmpty()
                if (configDigest.isBlank()) continue
                val config = readOciJson(layoutDir, configDigest)
                val arch = config.optString("architecture")
                if (arch == dockerArch || (dockerArch == "arm64" && arch == "aarch64")) return entry
            } catch (e: Throwable) {
                Log.w(TAG, "Skip OCI manifest entry: ${e.message}")
            }
        }
        error("No OCI manifest found for architecture '$preferredArch'")
    }

    private fun readOciJson(layoutDir: File, digest: String): JSONObject {
        val file = ociBlobFile(layoutDir, digest)
        return JSONObject(file.readText())
    }

    private fun ociBlobFile(layoutDir: File, digest: String): File {
        validateDigest(digest)
        val (algo, hex) = digest.split(":", limit = 2)
        val file = File(layoutDir, "blobs/$algo/$hex")
        if (!file.isFile) error("OCI archive is missing blob: blobs/$algo/$hex")
        return file
    }

    private fun validateDigest(digest: String) {
        val parts = digest.split(":", limit = 2)
        require(parts.size == 2) { "Malformed digest: $digest" }
        require(parts[0].equals("sha256", ignoreCase = true)) { "Unsupported digest algorithm: ${parts[0]}" }
        require(parts[1].matches(Regex("^[a-fA-F0-9]{64}$"))) { "Malformed digest hex: $digest" }
    }

    // ── Docker save ──────────────────────────────────────────────────────

    private fun installFromDockerSave(
        layoutDir: File,
        rootfsDir: File,
        preferredArch: String,
        onProgress: (String) -> Unit,
    ): Result {
        val manifestArr = JSONArray(File(layoutDir, "manifest.json").readText())
        if (manifestArr.length() == 0) error("Docker save archive has empty manifest.json")

        var chosen: JSONObject? = null
        var imageRef: String? = null
        var architecture: String? = null

        for (i in 0 until manifestArr.length()) {
            val entry = manifestArr.getJSONObject(i)
            val configName = entry.optString("Config")
            if (configName.isBlank()) continue
            val configFile = File(layoutDir, configName)
            if (!configFile.isFile) continue
            val config = JSONObject(configFile.readText())
            val arch = config.optString("architecture")
            val match = arch.isBlank() ||
                arch == preferredArch ||
                (preferredArch == "arm64" && arch == "aarch64") ||
                (preferredArch == "aarch64" && arch == "arm64")
            if (match) {
                chosen = entry
                architecture = arch.ifBlank { preferredArch }
                val repoTags = entry.optJSONArray("RepoTags")
                if (repoTags != null && repoTags.length() > 0) {
                    imageRef = repoTags.optString(0)
                }
                break
            }
            if (chosen == null) {
                chosen = entry
                architecture = arch.ifBlank { preferredArch }
                val repoTags = entry.optJSONArray("RepoTags")
                if (repoTags != null && repoTags.length() > 0) {
                    imageRef = repoTags.optString(0)
                }
            }
        }

        val entry = chosen ?: error("Docker save archive has no usable image")
        val layers = entry.optJSONArray("Layers") ?: error("Docker save entry has no Layers")
        if (layers.length() == 0) error("Docker save entry has empty Layers")

        for (i in 0 until layers.length()) {
            val layerRel = layers.getString(i)
            onProgress("Applying layer ${i + 1}/${layers.length()} ($layerRel)…")
            val layerFile = File(layoutDir, layerRel)
            if (!layerFile.isFile) error("Missing layer: $layerRel")
            applyLayerArchive(layerFile, rootfsDir)
        }

        return Result(
            kind = Kind.DOCKER_SAVE,
            imageRef = imageRef,
            architecture = architecture,
            layerCount = layers.length(),
        )
    }

    // ── Layer apply ──────────────────────────────────────────────────────

    private fun applyLayerArchive(layerFile: File, rootfsDir: File) {
        openTarStream(layerFile).use { tar ->
            extractTarEntries(tar, rootfsDir, strip = 0, handleWhiteouts = true)
        }
    }

    private fun extractTarEntries(
        tar: TarArchiveInputStream,
        destDir: File,
        strip: Int,
        handleWhiteouts: Boolean,
    ) {
        destDir.mkdirs()
        val destPrefix = destDir.canonicalPath + File.separator
        val deferredSymlinks = mutableListOf<Pair<String, String>>()
        val deferredHardlinks = mutableListOf<Pair<String, String>>()

        fun safe(name: String): File? = safeChild(destDir, name)

        var entry: TarArchiveEntry? = tar.nextEntry
        while (entry != null) {
            val rawName = entry.name.trimStart('/')
            val parts = rawName.split('/').filter { it.isNotEmpty() && it != "." }
            if (parts.size <= strip) {
                entry = tar.nextEntry
                continue
            }
            val relParts = parts.drop(strip)
            if (relParts.any { it == ".." }) {
                entry = tar.nextEntry
                continue
            }

            // OCI whiteouts
            if (handleWhiteouts) {
                val base = relParts.last()
                if (base == ".wh..wh..opq") {
                    // Opaque directory: clear siblings under parent
                    val parentRel = relParts.dropLast(1).joinToString("/")
                    val parent = if (parentRel.isEmpty()) destDir else safe(parentRel)
                    parent?.listFiles()?.forEach { child ->
                        try {
                            child.deleteRecursively()
                        } catch (_: Throwable) {
                        }
                    }
                    entry = tar.nextEntry
                    continue
                }
                if (base.startsWith(".wh.")) {
                    val targetName = base.removePrefix(".wh.")
                    val targetRel = (relParts.dropLast(1) + targetName).joinToString("/")
                    val target = safe(targetRel)
                    if (target != null && target.exists()) {
                        try {
                            target.deleteRecursively()
                        } catch (_: Throwable) {
                        }
                    }
                    entry = tar.nextEntry
                    continue
                }
            }

            val rel = relParts.joinToString("/")
            val outFile = safe(rel)
            if (outFile == null) {
                entry = tar.nextEntry
                continue
            }

            when {
                entry.isDirectory -> {
                    outFile.mkdirs()
                    outFile.setWritable(true, false)
                    outFile.setExecutable(true, false)
                }
                entry.isSymbolicLink -> {
                    outFile.parentFile?.mkdirs()
                    deferredSymlinks.add(rel to entry.linkName)
                }
                entry.isLink -> {
                    outFile.parentFile?.mkdirs()
                    deferredHardlinks.add(rel to entry.linkName)
                }
                entry.isFile -> {
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { tar.copyTo(it) }
                    if (entry.mode and 0x40 != 0 || entry.mode and 0x49 != 0) {
                        outFile.setExecutable(true, false)
                    }
                }
            }
            entry = tar.nextEntry
        }

        for ((name, target) in deferredHardlinks) {
            val outFile = safe(name) ?: continue
            if (outFile.exists()) continue
            val srcRel = target.trimStart('/').replace('\\', '/')
            val src = if (target.startsWith("/")) safe(srcRel) else {
                val parent = outFile.parentFile ?: destDir
                File(parent, target)
            } ?: continue
            if (!src.exists()) continue
            if (!src.canonicalPath.startsWith(destPrefix) && src.canonicalPath != destDir.canonicalPath) continue
            try {
                outFile.parentFile?.mkdirs()
                src.copyTo(outFile, overwrite = true)
            } catch (_: Throwable) {
            }
        }

        for ((name, target) in deferredSymlinks) {
            val outFile = safe(name) ?: continue
            if (outFile.exists()) continue
            val src = if (target.startsWith("/")) File(destDir, target.trimStart('/'))
            else File(outFile.parentFile ?: destDir, target)
            if (!src.exists()) continue
            if (src.canonicalPath != destDir.canonicalPath && !src.canonicalPath.startsWith(destPrefix)) continue
            try {
                if (src.isDirectory) {
                    src.walkTopDown().forEach { f ->
                        val rel = f.relativeTo(src).path
                        val dst = File(outFile, rel)
                        if (f.isDirectory) dst.mkdirs()
                        else {
                            dst.parentFile?.mkdirs()
                            f.copyTo(dst, overwrite = true)
                        }
                    }
                } else {
                    outFile.parentFile?.mkdirs()
                    src.copyTo(outFile, overwrite = true)
                }
            } catch (_: Throwable) {
            }
        }
    }

    private fun safeChild(destDir: File, name: String): File? {
        val cleaned = name.trimStart('/').replace('\\', '/')
        if (cleaned.isEmpty() || cleaned.contains("..")) return null
        val f = File(destDir, cleaned)
        val destPrefix = destDir.canonicalPath + File.separator
        return try {
            val path = f.canonicalPath
            if (path == destDir.canonicalPath || path.startsWith(destPrefix)) f else null
        } catch (_: Throwable) {
            null
        }
    }
}
