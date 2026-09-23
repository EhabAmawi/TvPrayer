package com.example.customtvscreensaver

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes

/**
 * One slideshow photo. [data] is what Coil loads: a Wikimedia Commons URL for the online list, a
 * bundled drawable for the offline one. Every photo is CC BY / CC BY-SA, so [author] and
 * [license] are shown on screen with it (see CustomDreamService); they are kept verbatim from
 * Commons rather than translated.
 */
data class Photo(
    @StringRes val place: Int,
    val author: String,
    val license: String,
    val data: Any
)

/**
 * Jordan and Islamic landmarks from Wikimedia Commons, chosen by hand for a 16:9 crop. URLs are
 * Commons' own 1920px thumbnails (about 300 KB each), the size Wikimedia asks apps to hotlink.
 *
 * [OFFLINE] is four of the same photos bundled in res/drawable-nodpi at 1920px: the whole list
 * when the user picks offline photos, and the fallback when an online photo cannot load.
 */
object PhotoRepository {
    val ONLINE: List<Photo> = listOf(
        online(R.string.photo_place_king_abdullah_mosque, "Diego Delso", "CC BY-SA 3.0", "https://thumb.wikimedia.org/wikipedia/commons/thumb/c/c3/King_Abdullah_I_Mosque%2C_Amman%2C_Jordan1.jpg/1920px-King_Abdullah_I_Mosque%2C_Amman%2C_Jordan1.jpg"),
        online(R.string.photo_place_king_abdullah_mosque, "Diego Delso", "CC BY-SA 3.0", "https://thumb.wikimedia.org/wikipedia/commons/thumb/4/4e/King_Abdullah_I_Mosque%2C_Amman%2C_Jordan2.jpg/1920px-King_Abdullah_I_Mosque%2C_Amman%2C_Jordan2.jpg"),
        online(R.string.photo_place_grand_husseini_mosque, "Justwiki", "CC BY 4.0", "https://thumb.wikimedia.org/wikipedia/commons/thumb/c/cd/Grand_Husseini_Mosque_na_2026_%283%29.jpg/1920px-Grand_Husseini_Mosque_na_2026_%283%29.jpg"),
        online(R.string.photo_place_abu_darwish_mosque, "Qais E. Tweissi", "CC BY-SA 4.0", "https://thumb.wikimedia.org/wikipedia/commons/thumb/8/8b/Amman-The_Abu_Darwish_Mosque.jpg/1920px-Amman-The_Abu_Darwish_Mosque.jpg"),
        online(R.string.photo_place_dome_of_the_rock, "Diego Delso", "CC BY-SA 3.0", "https://thumb.wikimedia.org/wikipedia/commons/thumb/3/3a/Exterior_of_the_Dome_of_the_Rock%2C_Jerusalem1.jpg/1920px-Exterior_of_the_Dome_of_the_Rock%2C_Jerusalem1.jpg"),
        online(R.string.photo_place_dome_of_the_rock, "Diego Delso", "CC BY-SA 3.0", "https://thumb.wikimedia.org/wikipedia/commons/thumb/4/44/Exterior_of_the_Dome_of_the_Rock%2C_Jerusalem2.jpg/1920px-Exterior_of_the_Dome_of_the_Rock%2C_Jerusalem2.jpg"),
        online(R.string.photo_place_dome_of_the_rock, "Diego Delso", "CC BY-SA 3.0", "https://thumb.wikimedia.org/wikipedia/commons/thumb/d/d0/Exterior_of_the_Dome_of_the_Rock%2C_Jerusalem5.jpg/1920px-Exterior_of_the_Dome_of_the_Rock%2C_Jerusalem5.jpg"),
        online(R.string.photo_place_amman, "Daniel Case", "CC BY-SA 3.0", "https://thumb.wikimedia.org/wikipedia/commons/thumb/1/10/Amman_cityscape_and_skyline_from_Citadel_Hill.jpg/1920px-Amman_cityscape_and_skyline_from_Citadel_Hill.jpg"),
        online(R.string.photo_place_amman_citadel, "Davric", "CC BY-SA 4.0", "https://thumb.wikimedia.org/wikipedia/commons/thumb/4/48/Amman_citadel_8.jpg/1920px-Amman_citadel_8.jpg"),
        online(R.string.photo_place_petra_monastery, "Diego Delso", "CC BY-SA 3.0", "https://thumb.wikimedia.org/wikipedia/commons/thumb/b/b7/The_Monastery%2C_Petra%2C_Jordan8.jpg/1920px-The_Monastery%2C_Petra%2C_Jordan8.jpg"),
        online(R.string.photo_place_petra, "Berthold Werner", "CC BY-SA 3.0", "https://thumb.wikimedia.org/wikipedia/commons/thumb/d/d7/Petra_Jordan_BW_43.JPG/1920px-Petra_Jordan_BW_43.JPG"),
        online(R.string.photo_place_wadi_rum, "Berthold Werner", "CC BY-SA 3.0", "https://thumb.wikimedia.org/wikipedia/commons/thumb/b/b3/Wadi_Rum_BW_16.JPG/1920px-Wadi_Rum_BW_16.JPG"),
        online(R.string.photo_place_wadi_rum, "Bernard Gagnon", "CC BY-SA 3.0", "https://thumb.wikimedia.org/wikipedia/commons/thumb/c/c1/Wadi_Rum_03.jpg/1920px-Wadi_Rum_03.jpg"),
        online(R.string.photo_place_wadi_rum, "Berthold Werner", "CC BY-SA 3.0", "https://thumb.wikimedia.org/wikipedia/commons/thumb/d/df/Wadi_Rum_BW_13.JPG/1920px-Wadi_Rum_BW_13.JPG"),
        online(R.string.photo_place_qasr_kharana, "High Contrast", "CC BY 3.0 de", "https://thumb.wikimedia.org/wikipedia/commons/thumb/a/a6/Qasr_Kharana_in_Jordan.jpg/1920px-Qasr_Kharana_in_Jordan.jpg"),
        online(R.string.photo_place_dead_sea, "Ali Abu Ras", "CC BY-SA 4.0", "https://thumb.wikimedia.org/wikipedia/commons/thumb/c/c1/Dead_sea_sunset_view.jpg/1920px-Dead_sea_sunset_view.jpg"),
        online(R.string.photo_place_dead_sea, "Randa107", "CC BY-SA 4.0", "https://thumb.wikimedia.org/wikipedia/commons/thumb/4/48/Dead_sea_sunset_11.jpg/1920px-Dead_sea_sunset_11.jpg"),
        online(R.string.photo_place_mount_nebo, "Faris El-Gwely", "CC BY-SA 4.0", "https://thumb.wikimedia.org/wikipedia/commons/thumb/4/40/From_Mount_Nebo.JPG/1920px-From_Mount_Nebo.JPG"),
        online(R.string.photo_place_jerash, "Berthold Werner", "CC BY 3.0", "https://thumb.wikimedia.org/wikipedia/commons/thumb/d/dc/Jerash_BW_1.JPG/1920px-Jerash_BW_1.JPG"),
        online(R.string.photo_place_wadi_mujib, "Berthold Werner", "CC BY 3.0", "https://thumb.wikimedia.org/wikipedia/commons/thumb/2/2e/Wadi_Mujib_BW_1.JPG/1920px-Wadi_Mujib_BW_1.JPG"),
        online(R.string.photo_place_dana, "Bernard Gagnon", "CC BY-SA 3.0", "https://thumb.wikimedia.org/wikipedia/commons/thumb/a/a8/Camels_in_Dana_Reserve_02.jpg/1920px-Camels_in_Dana_Reserve_02.jpg")
    )

    val OFFLINE: List<Photo> = listOf(
        offline(R.string.photo_place_king_abdullah_mosque, "Diego Delso", "CC BY-SA 3.0", R.drawable.photo_offline_king_abdullah_mosque),
        offline(R.string.photo_place_dome_of_the_rock, "Diego Delso", "CC BY-SA 3.0", R.drawable.photo_offline_dome_of_the_rock),
        offline(R.string.photo_place_petra_monastery, "Diego Delso", "CC BY-SA 3.0", R.drawable.photo_offline_petra_monastery),
        offline(R.string.photo_place_dead_sea, "Ali Abu Ras", "CC BY-SA 4.0", R.drawable.photo_offline_dead_sea_sunset)
    )

    fun list(online: Boolean): List<Photo> = if (online) ONLINE else OFFLINE

    /** Wraps, so callers can keep incrementing a counter forever. */
    fun at(online: Boolean, index: Int): Photo = list(online).let { it[index.mod(it.size)] }

    /** The bundled photo shown when [index]'s online photo fails to load. */
    fun fallback(index: Int): Photo = OFFLINE[index.mod(OFFLINE.size)]

    private fun online(@StringRes place: Int, author: String, license: String, url: String) =
        Photo(place, author, license, url)

    private fun offline(@StringRes place: Int, author: String, license: String, @DrawableRes res: Int) =
        Photo(place, author, license, res)
}
