package br.com.rafarezende.dxstudio

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Lista as gravacoes salvas em Filmes/DXStudio (MediaStore). */
class LibraryActivity : ComponentActivity() {

    private data class Recording(
        val uri: Uri,
        val name: String,
        val sizeBytes: Long,
        val durationMs: Long,
        val dateAddedSec: Long
    ) {
        override fun toString(): String {
            val date = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.US).format(Date(dateAddedSec * 1000))
            val sizeMb = sizeBytes / (1024.0 * 1024.0)
            val dur = formatDuration(durationMs)
            return "$name\n$date · $dur · ${String.format(Locale.US, "%.1f", sizeMb)} MB"
        }
    }

    private lateinit var listView: ListView
    private lateinit var emptyText: TextView
    private var recordings: List<Recording> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_library)
        listView = findViewById(R.id.recordingsList)
        emptyText = findViewById(R.id.emptyText)
        findViewById<Button>(R.id.backButton).setOnClickListener { finish() }

        listView.setOnItemClickListener { _, _, position, _ ->
            val rec = recordings.getOrNull(position) ?: return@setOnItemClickListener
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(rec.uri, "video/mp4")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        }
        listView.setOnItemLongClickListener { _, _, position, _ ->
            val rec = recordings.getOrNull(position) ?: return@setOnItemLongClickListener false
            showOptions(rec)
            true
        }
        loadRecordings()
    }

    override fun onResume() {
        super.onResume()
        loadRecordings()
    }

    private fun loadRecordings() {
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.DATE_ADDED
        )
        val selection = "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
        val args = arrayOf("Movies/DXStudio/%")
        val sort = "${MediaStore.Video.Media.DATE_ADDED} DESC"
        val result = mutableListOf<Recording>()
        contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection, selection, args, sort
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val durCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val uri = Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id.toString())
                result.add(
                    Recording(
                        uri = uri,
                        name = cursor.getString(nameCol) ?: "gravacao",
                        sizeBytes = cursor.getLong(sizeCol),
                        durationMs = cursor.getLong(durCol),
                        dateAddedSec = cursor.getLong(dateCol)
                    )
                )
            }
        }
        recordings = result
        emptyText.visibility = if (result.isEmpty()) View.VISIBLE else View.GONE
        listView.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            result.map { it.toString() }
        )
    }

    private fun showOptions(rec: Recording) {
        AlertDialog.Builder(this)
            .setTitle(rec.name)
            .setItems(arrayOf("Compartilhar", "Apagar")) { _, which ->
                when (which) {
                    0 -> {
                        val share = Intent(Intent.ACTION_SEND).apply {
                            type = "video/mp4"
                            putExtra(Intent.EXTRA_STREAM, rec.uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        startActivity(Intent.createChooser(share, "Compartilhar gravação"))
                    }
                    1 -> {
                        AlertDialog.Builder(this)
                            .setMessage("Apagar ${rec.name}?")
                            .setPositiveButton("Apagar") { _, _ ->
                                val deleted = contentResolver.delete(rec.uri, null, null)
                                if (deleted > 0) {
                                    Toast.makeText(this, "Gravação apagada", Toast.LENGTH_SHORT).show()
                                    loadRecordings()
                                } else {
                                    Toast.makeText(this, "Não consegui apagar", Toast.LENGTH_SHORT).show()
                                }
                            }
                            .setNegativeButton("Cancelar", null)
                            .show()
                    }
                }
            }
            .show()
    }

    companion object {
        fun formatDuration(ms: Long): String {
            val totalSec = ms / 1000
            val h = totalSec / 3600
            val m = (totalSec % 3600) / 60
            val s = totalSec % 60
            return if (h > 0) {
                String.format(Locale.US, "%d:%02d:%02d", h, m, s)
            } else {
                String.format(Locale.US, "%02d:%02d", m, s)
            }
        }
    }
}
