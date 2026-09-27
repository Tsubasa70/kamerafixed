package com.kamera.app

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Foto/video dosyalarini MediaStore'a (DCIM/Camera) yazar; galeride aninda gorunur. */
object MediaSaver {
    private const val TAG = "KameraApp"
    private const val REL_PATH = "DCIM/Camera"

    class VideoTarget(val uri: Uri, val pfd: ParcelFileDescriptor)

    private fun stamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())

    private val imagesUri: Uri get() = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    private val videosUri: Uri get() = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    fun savePhoto(ctx: Context, bytes: ByteArray): Uri? {
        val res = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "IMG_${stamp()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, REL_PATH)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = res.insert(imagesUri, values) ?: return null
        return try {
            res.openOutputStream(uri)?.use { it.write(bytes) } ?: throw IllegalStateException("stream acilamadi")
            val done = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            res.update(uri, done, null, null)
            uri
        } catch (e: Exception) {
            Log.e(TAG, "Foto kaydedilemedi", e)
            res.delete(uri, null, null)
            null
        }
    }

    fun newVideoTarget(ctx: Context): VideoTarget? {
        val res = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "VID_${stamp()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, REL_PATH)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = res.insert(videosUri, values) ?: return null
        return try {
            val pfd = res.openFileDescriptor(uri, "rw") ?: throw IllegalStateException("fd acilamadi")
            VideoTarget(uri, pfd)
        } catch (e: Exception) {
            Log.e(TAG, "Video hedefi acilamadi", e)
            res.delete(uri, null, null)
            null
        }
    }

    fun finishVideo(ctx: Context, target: VideoTarget, ok: Boolean) {
        val res = ctx.contentResolver
        try {
            target.pfd.close()
        } catch (_: Exception) {
        }
        if (ok) {
            val done = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
            res.update(target.uri, done, null, null)
        } else {
            res.delete(target.uri, null, null)
        }
    }

    /** Uygulamanin kaydettigi en son foto/video (varsa). */
    fun latest(ctx: Context): Uri? {
        val a = latestOf(ctx.contentResolver, imagesUri)
        val b = latestOf(ctx.contentResolver, videosUri)
        return when {
            a == null -> b?.first
            b == null -> a.first
            a.second >= b.second -> a.first
            else -> b.first
        }
    }

    private fun latestOf(res: ContentResolver, base: Uri): Pair<Uri, Long>? {
        return try {
            val proj = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_ADDED)
            res.query(base, proj, null, null, "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { c ->
                if (c.moveToFirst()) ContentUris.withAppendedId(base, c.getLong(0)) to c.getLong(1) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    fun thumbnail(ctx: Context, uri: Uri): Bitmap? = try {
        ctx.contentResolver.loadThumbnail(uri, Size(256, 256), null)
    } catch (e: Exception) {
        null
    }
}
