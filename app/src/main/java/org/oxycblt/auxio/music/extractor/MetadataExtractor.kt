/*
 * Copyright (c) 2022 Auxio Project
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
 
package org.oxycblt.auxio.music.extractor

import android.content.Context
import androidx.core.text.isDigitsOnly
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.MetadataRetriever
import com.google.android.exoplayer2.metadata.Metadata
import com.google.android.exoplayer2.metadata.id3.TextInformationFrame
import com.google.android.exoplayer2.metadata.vorbis.VorbisComment
import org.oxycblt.auxio.music.Date
import org.oxycblt.auxio.music.Song
import org.oxycblt.auxio.music.storage.toAudioUri
import org.oxycblt.auxio.util.logD
import org.oxycblt.auxio.util.logW

/**
 * The extractor that leverages ExoPlayer's [MetadataRetriever] API to parse metadata. This is the
 * last step in the music extraction process and is mostly responsible for papering over the
 * bad metadata that [MediaStoreExtractor] produces.
 *
 * @param context [Context] required for reading audio files.
 * @param mediaStoreExtractor [MediaStoreExtractor] implementation for cache optimizations and
 * redundancy.
 * @author Alexander Capehart (OxygenCobalt)
 */
class MetadataExtractor(
    private val context: Context,
    private val mediaStoreExtractor: MediaStoreExtractor
) {
    // We can parallelize MetadataRetriever Futures to work around it's speed issues,
    // producing similar throughput's to other kinds of manual metadata extraction.
    private val taskPool: Array<Task?> = arrayOfNulls(TASK_CAPACITY)

    /**
     * Initialize this extractor. This actually initializes the sub-extractors that this instance
     * relies on.
     * @return The amount of music that is expected to be loaded.
     */
    fun init() = mediaStoreExtractor.init().count

    /**
     * Finalize the Extractor by writing the newly-loaded [Song.Raw]s back into the cache,
     * alongside freeing up memory.
     * @param rawSongs The songs to write into the cache.
     */
    fun finalize(rawSongs: List<Song.Raw>) = mediaStoreExtractor.finalize(rawSongs)

    /**
     * Parse all [Song.Raw] instances queued by the sub-extractors. This will first delegate
     * to the sub-extractors before parsing the metadata itself.
     * @param emit A callback that will be invoked with every new [Song.Raw] instance when
     * they are successfully loaded.
     */
    suspend fun parse(emit: suspend (Song.Raw) -> Unit) {
        while (true) {
            val raw = Song.Raw()
            when (mediaStoreExtractor.populate(raw)) {
                ExtractionResult.NONE -> break
                ExtractionResult.PARSED -> {}
                ExtractionResult.CACHED -> {
                    // Avoid running the expensive parsing process on songs we can already
                    // restore from the cache.
                    emit(raw)
                    continue
                }
            }

            // Spin until there is an open slot we can insert a task in.
            spin@ while (true) {
                for (i in taskPool.indices) {
                    val task = taskPool[i]
                    if (task != null) {
                        val finishedRaw = task.get()
                        if (finishedRaw != null) {
                            emit(finishedRaw)
                            taskPool[i] = Task(context, raw)
                            break@spin
                        }
                    } else {
                        taskPool[i] = Task(context, raw)
                        break@spin
                    }
                }
            }
        }

        spin@ while (true) {
            // Spin until all of the remaining tasks are complete.
            for (i in taskPool.indices) {
                val task = taskPool[i]
                if (task != null) {
                    val finishedRaw = task.get() ?: continue@spin
                    emit(finishedRaw)
                    taskPool[i] = null
                }
            }

            break
        }
    }

    companion object {
        private const val TASK_CAPACITY = 8
    }
}

/**
 * Wraps a [MetadataExtractor] future and processes it into a [Song.Raw] when completed.
 * TODO: Re-unify with MetadataExtractor.
 * @param context [Context] required to open the audio file.
 * @param raw [Song.Raw] to process.
 * @author Alexander Capehart (OxygenCobalt)
 */
class Task(context: Context, private val raw: Song.Raw) {
    // Note that we do not leverage future callbacks. This is because errors in the
    // (highly fallible) extraction process will not bubble up to Indexer when a
    // callback is used, instead crashing the app entirely.
    private val future =
        MetadataRetriever.retrieveMetadata(
            context,
            MediaItem.fromUri(requireNotNull(raw.mediaStoreId) { "Invalid raw: No id" }.toAudioUri()))

    /**
     * Try to get a completed song from this [Task], if it has finished processing.
     * @return A [Song.Raw] instance if processing has completed, null otherwise.
     */
    fun get(): Song.Raw? {
        if (!future.isDone) {
            return null
        }

        val format =
            try {
                future.get()[0].getFormat(0)
            } catch (e: Exception) {
                logW("Unable to extract metadata for ${raw.name}")
                logW(e.stackTraceToString())
                null
            }
        if (format == null) {
            logD("Nothing could be extracted for ${raw.name}")
            return raw
        }

        // Populate the format mime type if we have one.
        // TODO: Check if this is even useful or not.
        format.sampleMimeType?.let { raw.formatMimeType = it }

        val metadata = format.metadata
        if (metadata != null) {
            populateWithMetadata(metadata)
        } else {
            logD("No metadata could be extracted for ${raw.name}")
        }

        return raw
    }

    /**
     * Complete this instance's [Song.Raw] with the newly extracted [Metadata].
     * @param metadata The [Metadata] to complete the [Song.Raw] with.
     */
    private fun populateWithMetadata(metadata: Metadata) {
        val id3v2Tags = mutableMapOf<String, List<String>>()
        val vorbisTags = mutableMapOf<String, MutableList<String>>()

        // ExoPlayer only exposes ID3v2 and Vorbis metadata, which constitutes the vast majority
        // of audio formats. Load both of these types of tags into separate maps, letting the
        // "source of truth" be the last of a particular tag in a file.
        for (i in 0 until metadata.length()) {
            when (val tag = metadata[i]) {
                is TextInformationFrame -> {
                    // Map TXXX frames differently so we can specifically index by their
                    // descriptions.
                    val id = tag.description?.let { "TXXX:${it.sanitize()}" } ?: tag.id.sanitize()
                    val values = tag.values.map { it.sanitize() }
                    if (values.isNotEmpty() && values.all { it.isNotEmpty() }) {
                        id3v2Tags[id] = values
                    }
                }
                is VorbisComment -> {
                    // Vorbis comment keys can be in any case, make them uppercase for simplicity.
                    val id = tag.key.sanitize().uppercase()
                    val value = tag.value.sanitize()
                    if (value.isNotEmpty()) {
                        vorbisTags.getOrPut(id) { mutableListOf() }.add(value)
                    }
                }
            }
        }

        when {
            vorbisTags.isEmpty() -> populateWithId3v2(id3v2Tags)
            id3v2Tags.isEmpty() -> populateWithVorbis(vorbisTags)
            else -> {
                // Some formats (like FLAC) can contain both ID3v2 and Vorbis, so we apply
                // them both with priority given to vorbis.
                populateWithId3v2(id3v2Tags)
                populateWithVorbis(vorbisTags)
            }
        }
    }

    /**
     * Complete this instance's [Song.Raw] with ID3v2 Text Identification Frames.
     * @param textFrames A mapping between ID3v2 Text Identification Frame IDs and one or more
     * values.
     */
    private fun populateWithId3v2(textFrames: Map<String, List<String>>) {
        // Song
        textFrames["TXXX:MusicBrainz Release Track Id"]?.let { raw.musicBrainzId = it[0] }
        textFrames["TIT2"]?.let { raw.name = it[0] }
        textFrames["TSOT"]?.let { raw.sortName = it[0] }

        // Track. Only parse out the track number and ignore the total tracks value.
        textFrames["TRCK"]?.run { get(0).parsePositionNum() }?.let { raw.track = it }

        // Disc. Only parse out the disc number and ignore the total discs value.
        textFrames["TPOS"]?.run { get(0).parsePositionNum() }?.let { raw.disc = it }

        // Dates are somewhat complicated, as not only did their semantics change from a flat year
        // value in ID3v2.3 to a full ISO-8601 date in ID3v2.4, but there are also a variety of
        // date types.
        // Our hierarchy for dates is as such:
        // 1. ID3v2.4 Original Date, as it resolves the "Released in X, Remastered in Y" issue
        // 2. ID3v2.4 Recording Date, as it is the most common date type
        // 3. ID3v2.4 Release Date, as it is the second most common date type
        // 4. ID3v2.3 Original Date, as it is like #1
        // 5. ID3v2.3 Release Year, as it is the most common date type
        (textFrames["TDOR"]?.run { get(0).parseTimestamp() }
                ?: textFrames["TDRC"]?.run { get(0).parseTimestamp() }
                    ?: textFrames["TDRL"]?.run { get(0).parseTimestamp() } ?: parseId3v23Date(textFrames))
            ?.let { raw.date = it }

        // Album
        textFrames["TXXX:MusicBrainz Album Id"]?.let { raw.albumMusicBrainzId = it[0] }
        textFrames["TALB"]?.let { raw.albumName = it[0] }
        textFrames["TSOA"]?.let { raw.albumSortName = it[0] }
        (textFrames["TXXX:MusicBrainz Album Type"] ?: textFrames["GRP1"])?.let { raw.albumTypes = it }

        // Artist
        textFrames["TXXX:MusicBrainz Artist Id"]?.let { raw.artistMusicBrainzIds = it }
        textFrames["TPE1"]?.let { raw.artistNames = it }
        textFrames["TSOP"]?.let { raw.artistSortNames = it }

        // Album artist
        textFrames["TXXX:MusicBrainz Album Artist Id"]?.let { raw.albumArtistMusicBrainzIds = it }
        textFrames["TPE2"]?.let { raw.albumArtistNames = it }
        textFrames["TSO2"]?.let { raw.albumArtistSortNames = it }

        // Genre
        textFrames["TCON"]?.let { raw.genreNames = it }
    }

    /**
     * Parses the ID3v2.3 timestamp specification into a [Date] from the given Text Identification
     * Frames.
     * @param textFrames A mapping between ID3v2 Text Identification Frame IDs and one or more
     * values.
     * @retrn A [Date]  of a year value from TORY/TYER, a month and day value from TDAT,
     * and a hour/minute value from TIME. No second value is included. The latter two fields may
     * not be included in they cannot be parsed. Will be null if a year value could not be parsed.
     */
    private fun parseId3v23Date(textFrames: Map<String, List<String>>): Date? {
        // Assume that TDAT/TIME can refer to TYER or TORY depending on if TORY
        // is present.
        val year =
            textFrames["TORY"]?.run { get(0).toIntOrNull() }
                ?: textFrames["TYER"]?.run { get(0).toIntOrNull() } ?: return null

        val tdat = textFrames["TDAT"]
        return if (tdat != null && tdat[0].length == 4 && tdat[0].isDigitsOnly()) {
            // TDAT frames consist of a 4-digit string where the first two digits are
            // the month and the last two digits are the day.
            val mm = tdat[0].substring(0..1).toInt()
            val dd = tdat[0].substring(2..3).toInt()

            val time = textFrames["TIME"]
            if (time != null && time[0].length == 4 && time[0].isDigitsOnly()) {
                // TIME frames consist of a 4-digit string where the first two digits are
                // the hour and the last two digits are the minutes. No second value is
                // possible.
                val hh = time[0].substring(0..1).toInt()
                val mi = time[0].substring(2..3).toInt()
                // Able to return a full date.
                Date.from(year, mm, dd, hh, mi)
            } else {
                // Unable to parse time, just return a date
                Date.from(year, mm, dd)
            }
        } else {
            // Unable to parse month/day, just return a year
            return Date.from(year)
        }
    }

    /**
     * Complete this instance's [Song.Raw] with Vorbis comments.
     * @param comments A mapping between vorbis comment names and one or more vorbis comment
     * values.
     */
    private fun populateWithVorbis(comments: Map<String, List<String>>) {
        // Song
        comments["MUSICBRAINZ_RELEASETRACKID"]?.let { raw.musicBrainzId = it[0] }
        comments["TITLE"]?.let { raw.name = it[0] }
        comments["TITLESORT"]?.let { raw.sortName = it[0] }

        // Track. The total tracks value is in a different comment, so we can just
        // convert the entirety of this comment into a number.
        comments["TRACKNUMBER"]?.run { get(0).toIntOrNull() }?.let { raw.track = it }

        // Disc. The total discs value is in a different comment, so we can just
        // convert the entirety of this comment into a number.
        comments["DISCNUMBER"]?.run { get(0).toIntOrNull() }?.let { raw.disc = it }

        // Vorbis dates are less complicated, but there are still several types
        // Our hierarchy for dates is as such:
        // 1. Original Date, as it solves the "Released in X, Remastered in Y" issue
        // 2. Date, as it is the most common date type
        // 3. Year, as old vorbis tags tended to use this (I know this because it's the only
        // date tag that android supports, so it must be 15 years old or more!)
        (comments["ORIGINALDATE"]?.run { get(0).parseTimestamp() }
                ?: comments["DATE"]?.run { get(0).parseTimestamp() }
                    ?: comments["YEAR"]?.run { get(0).parseYear() })
            ?.let { raw.date = it }

        // Album
        comments["MUSICBRAINZ_ALBUMID"]?.let { raw.albumMusicBrainzId = it[0] }
        comments["ALBUM"]?.let { raw.albumName = it[0] }
        comments["ALBUMSORT"]?.let { raw.albumSortName = it[0] }
        comments["RELEASETYPE"]?.let { raw.albumTypes = it }

        // Artist
        comments["MUSICBRAINZ_ARTISTID"]?.let { raw.artistMusicBrainzIds = it }
        comments["ARTIST"]?.let { raw.artistNames = it }
        comments["ARTISTSORT"]?.let { raw.artistSortNames = it }

        // Album artist
        comments["MUSICBRAINZ_ALBUMARTISTID"]?.let { raw.albumArtistMusicBrainzIds = it }
        comments["ALBUMARTIST"]?.let { raw.albumArtistNames = it }
        comments["ALBUMARTISTSORT"]?.let { raw.albumArtistSortNames = it }

        // Genre
        comments["GENRE"]?.let { raw.genreNames = it }
    }

    /**
     * Copies and sanitizes a possibly native/non-UTF-8 string.
     * @return A new string allocated in a memory-safe manner with any UTF-8 errors
     * replaced with the Unicode replacement byte sequence.
     */
    private fun String.sanitize() = String(encodeToByteArray())
}
