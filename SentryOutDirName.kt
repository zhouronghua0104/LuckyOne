/**
 * Get sentry output directory name.
 *
 * @return output directory name
 */
fun getSentryOutDirName(videoPath: String): String {
    return if (config().extractUseFileName) {
        val fileName = extractFileName(videoPath)
        if (fileName.isBlank()) {
            System.nanoTime().toString()
        } else {
            fileName.substringBeforeLast('.', fileName)
        }
    } else {
        System.nanoTime().toString()
    }
}

private fun extractFileName(videoPath: String): String {
    val trimmedPath = videoPath.trim()
    if (trimmedPath.isEmpty()) {
        return ""
    }
    val withoutFragment = trimmedPath.substringBefore('#')
    val withoutQuery = withoutFragment.substringBefore('?')
    return withoutQuery.substringAfterLast('/', withoutQuery)
}
