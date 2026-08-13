package com.ahad.lyricsoverlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import java.util.concurrent.Executors

class MusicListAdapter(
    private val context: Context,
    private val onSongClicked: (position: Int) -> Unit
) : RecyclerView.Adapter<MusicListAdapter.SongViewHolder>() {

    private val songs = mutableListOf<Song>()
    private val artworkExecutor = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var playingSongId: Long? = null

    private val artworkCache = object : LruCache<Long, Bitmap>(cacheSizeKb()) {
        override fun sizeOf(key: Long, value: Bitmap): Int = value.byteCount / 1024
    }

    fun submitList(newSongs: List<Song>) {
        songs.clear()
        songs.addAll(newSongs)
        notifyDataSetChanged()
    }

    fun setPlayingSong(songId: Long?) {
        val previousId = playingSongId
        playingSongId = songId
        if (previousId == songId) return
        songs.indexOfFirst { it.id == previousId }.takeIf { it >= 0 }?.let(::notifyItemChanged)
        songs.indexOfFirst { it.id == songId }.takeIf { it >= 0 }?.let(::notifyItemChanged)
    }

    fun loadArtworkInto(imageView: ImageView, song: Song) {
        imageView.tag = song.id
        artworkCache.get(song.id)?.let { bitmap ->
            showBitmap(imageView, bitmap)
            return
        }

        showPlaceholder(imageView)
        artworkExecutor.execute {
            val bitmap = loadArtwork(song)
            if (bitmap != null) artworkCache.put(song.id, bitmap)
            mainHandler.post {
                if (imageView.tag == song.id) {
                    if (bitmap != null) showBitmap(imageView, bitmap) else showPlaceholder(imageView)
                }
            }
        }
    }

    fun release() {
        artworkExecutor.shutdownNow()
        artworkCache.evictAll()
        mainHandler.removeCallbacksAndMessages(null)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SongViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_song, parent, false)
        return SongViewHolder(view as MaterialCardView)
    }

    override fun onBindViewHolder(holder: SongViewHolder, position: Int) {
        holder.bind(songs[position], position)
    }

    override fun getItemCount(): Int = songs.size

    inner class SongViewHolder(private val card: MaterialCardView) : RecyclerView.ViewHolder(card) {
        private val albumArt: ImageView = card.findViewById(R.id.albumArt)
        private val title: android.widget.TextView = card.findViewById(R.id.songTitle)
        private val artist: android.widget.TextView = card.findViewById(R.id.songArtist)
        private val duration: android.widget.TextView = card.findViewById(R.id.songDuration)

        fun bind(song: Song, adapterPosition: Int) {
            title.text = song.title
            artist.text = song.artist
            duration.text = MusicScannerUtil.formatDuration(song.durationMs)
            loadArtworkInto(albumArt, song)

            val isPlaying = song.id == playingSongId
            card.strokeWidth = if (isPlaying) dp(1.5f) else dp(1f)
            card.setStrokeColor(
                ContextCompat.getColor(
                    context,
                    if (isPlaying) R.color.primary else R.color.divider
                )
            )
            card.setCardBackgroundColor(
                ContextCompat.getColor(
                    context,
                    if (isPlaying) R.color.surface_elevated else R.color.surface
                )
            )
            card.setOnClickListener {
                val currentPosition = bindingAdapterPosition
                if (currentPosition != RecyclerView.NO_POSITION) {
                    onSongClicked(currentPosition)
                } else {
                    onSongClicked(adapterPosition)
                }
            }
        }
    }

    private fun loadArtwork(song: Song): Bitmap? {
        song.albumArtUri?.let { uri ->
            decodeUri(uri.toString())?.let { return it }
        }

        return try {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, song.contentUri)
                retriever.embeddedPicture?.let { decodeByteArray(it) }
            } finally {
                retriever.release()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeUri(uriString: String): Bitmap? {
        val uri = android.net.Uri.parse(uriString)
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            val options = BitmapFactory.Options().apply {
                inSampleSize = calculateSampleSize(bounds.outWidth, bounds.outHeight, 300)
            }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeByteArray(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateSampleSize(bounds.outWidth, bounds.outHeight, 300)
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private fun calculateSampleSize(width: Int, height: Int, target: Int): Int {
        var sample = 1
        var halfWidth = width / 2
        var halfHeight = height / 2
        while (halfWidth / sample >= target && halfHeight / sample >= target) {
            sample *= 2
        }
        return sample.coerceAtLeast(1)
    }

    private fun showBitmap(imageView: ImageView, bitmap: Bitmap) {
        imageView.setPadding(0, 0, 0, 0)
        imageView.scaleType = ImageView.ScaleType.CENTER_CROP
        imageView.setImageBitmap(bitmap)
    }

    private fun showPlaceholder(imageView: ImageView) {
        val padding = dp(13f)
        imageView.setPadding(padding, padding, padding, padding)
        imageView.scaleType = ImageView.ScaleType.CENTER_INSIDE
        imageView.setImageResource(R.drawable.ic_album)
    }

    private fun dp(value: Float): Int = (value * context.resources.displayMetrics.density).toInt()

    companion object {
        private fun cacheSizeKb(): Int {
            val maxMemoryKb = (Runtime.getRuntime().maxMemory() / 1024L).toInt()
            return (maxMemoryKb / 16).coerceAtLeast(4 * 1024)
        }
    }
}
