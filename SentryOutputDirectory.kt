import java.io.File

fun prepareSentryOutputDir(outputParentDir: File, videoPath: String): File {
    val destDir = File(outputParentDir, getSentryOutDirName(videoPath))

    if (destDir.exists()) {
        destDir.deleteRecursively()
    }

    if (!destDir.exists()) {
        destDir.mkdirs()
    }

    return destDir
}

fun getSentryOutDirName(videoPath: String): String {
    val trimmedPath = videoPath.trim()
    if (trimmedPath.isEmpty()) {
        return System.nanoTime().toString()
    }

    val withoutFragment = trimmedPath.substringBefore('#')
    val withoutQuery = withoutFragment.substringBefore('?')
    val fileName = withoutQuery.substringAfterLast('/', withoutQuery)
    if (fileName.isBlank()) {
        return System.nanoTime().toString()
    }

    return fileName.substringBeforeLast('.', fileName)
}
