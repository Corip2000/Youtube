package ru.corip.shortsoffline

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Сохранение готового файла в галерею телефона.
 *
 * Начиная с Android 10 приложению не нужно разрешение на запись, если оно
 * кладёт файл через MediaStore в общую папку — там он и появится в галерее.
 */
object Gallery {

    suspend fun saveVideo(context: Context, source: File, displayName: String): String =
        withContext(Dispatchers.IO) {
            val safeName = displayName
                .replace(Regex("""[\\/:*?"<>|]"""), " ")
                .trim()
                .take(80)
                .ifBlank { "video" } + ".mp4"

            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, safeName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(
                    MediaStore.Video.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_MOVIES + "/ShortsOffline",
                )
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }

            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("Галерея не приняла файл.")

            resolver.openOutputStream(uri)?.use { out ->
                source.inputStream().use { it.copyTo(out) }
            } ?: error("Не удалось открыть файл на запись.")

            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)

            source.delete()
            "Movies/ShortsOffline/$safeName"
        }
}
