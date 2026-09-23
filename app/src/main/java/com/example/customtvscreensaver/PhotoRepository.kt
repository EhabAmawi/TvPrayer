package com.example.customtvscreensaver

import androidx.annotation.DrawableRes

data class PhotoSource(
    @DrawableRes val localDrawable: Int,
    val remoteUrl: String
)

object PhotoRepository {
    private val photos = listOf(
        PhotoSource(R.drawable.dream_preview, "https://images.unsplash.com/photo-1500534623283-312aade485b7?w=2400&q=85"),
        PhotoSource(R.drawable.dream_preview, "https://images.unsplash.com/photo-1464822759023-fed622ff2c3b?w=2400&q=85"),
        PhotoSource(R.drawable.dream_preview, "https://images.unsplash.com/photo-1470770841072-f978cf4d019e?w=2400&q=85")
    )

    /** Wraps, so callers can keep incrementing a counter forever. */
    fun at(index: Int): PhotoSource = photos[index.mod(photos.size)]

    val size: Int get() = photos.size
}
