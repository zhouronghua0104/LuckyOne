const val VIDEO_POSITION_FRONT_UP = 1
const val VIDEO_POSITION_FRONT = 0
const val VIDEO_POSITION_RIGHT = 2
const val VIDEO_POSITION_BACK = 3
const val VIDEO_POSITION_LEFT = 4

fun getVideoPosition(videoPath: String): Int {
    if (videoPath.isBlank()) return VIDEO_POSITION_FRONT

    val lastSegment = try {
        android.net.Uri.parse(videoPath).lastPathSegment
    } catch (_: Exception) {
        null
    }

    val fileName = (lastSegment ?: videoPath.substringAfterLast('/'))
        .substringBefore('?')
        .substringBefore('#')

    val baseName = fileName.substringBeforeLast('.', fileName)
    val suffix = baseName
        .substringAfterLast('_', missingDelimiterValue = "")
        .uppercase()

    return when (suffix) {
        "FA" -> VIDEO_POSITION_FRONT_UP
        "FR" -> VIDEO_POSITION_FRONT
        "RI" -> VIDEO_POSITION_RIGHT
        "RE" -> VIDEO_POSITION_BACK
        "LE" -> VIDEO_POSITION_LEFT
        else -> VIDEO_POSITION_FRONT
    }
}
