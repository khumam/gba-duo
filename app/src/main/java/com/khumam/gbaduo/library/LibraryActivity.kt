package com.khumam.gbaduo.library

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.khumam.gbaduo.R
import com.khumam.gbaduo.companion.PackAssignments
import com.khumam.gbaduo.companion.PackInstaller
import com.khumam.gbaduo.companion.PackStore
import com.khumam.gbaduo.player.PlayerActivity
import java.io.File

class LibraryActivity : Activity() {

    private lateinit var library: RomLibrary
    private lateinit var adapter: RomAdapter
    private lateinit var empty: LinearLayout
    private lateinit var emptyText: TextView

    private val packsDir by lazy { File(filesDir, "packs") }
    private val packStore by lazy { PackStore(packsDir) }
    private val assignments by lazy { PackAssignments(File(filesDir, "pack_assignments.json")) }
    private var pickPackForEntry: RomEntry? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_library)

        library = RomLibrary(this)
        adapter = RomAdapter(onOpen = { entry -> openGame(entry) }, onOverflow = { entry -> showCompanionMenu(entry) })

        findViewById<RecyclerView>(R.id.list).apply {
            layoutManager = LinearLayoutManager(this@LibraryActivity)
            adapter = this@LibraryActivity.adapter
        }
        findViewById<FloatingActionButton>(R.id.choose_folder).setOnClickListener { pickFolder() }
        empty = findViewById(R.id.empty)
        emptyText = findViewById(R.id.empty_text)
    }

    private fun pickFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        startActivityForResult(intent, REQUEST_PICK_FOLDER)
    }

    private fun showCompanionMenu(entry: RomEntry) {
        val matches = packStore.matching(entry.gameCode)
        val assignedId = assignments.get(entry.fileName)
        val labels = mutableListOf(getString(R.string.no_pack))
        labels += matches.map { pack -> if (pack.id == assignedId) "✓ ${pack.name}" else pack.name }
        labels += getString(R.string.import_pack)

        AlertDialog.Builder(this)
            .setTitle(R.string.companion_pack)
            .setItems(labels.toTypedArray()) { _, which ->
                when (which) {
                    0 -> assignments.set(entry.fileName, null)
                    labels.size - 1 -> {
                        pickPackForEntry = entry
                        pickPackZip()
                    }
                    else -> assignments.set(entry.fileName, matches[which - 1].id)
                }
            }
            .show()
    }

    private fun pickPackZip() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
        }
        startActivityForResult(intent, REQUEST_PICK_PACK)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_PICK_FOLDER -> handleFolderPicked(resultCode, data)
            REQUEST_PICK_PACK -> handlePackPicked(resultCode, data)
        }
    }

    private fun handleFolderPicked(resultCode: Int, data: Intent?) {
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        library.folderUri = uri
        refresh()
    }

    private fun handlePackPicked(resultCode: Int, data: Intent?) {
        val entry = pickPackForEntry
        pickPackForEntry = null
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return

        val tempZip = File(cacheDir, "import.zip")
        contentResolver.openInputStream(uri)?.use { input ->
            tempZip.outputStream().use { output -> input.copyTo(output) }
        }
        val result = runCatching { PackInstaller.install(tempZip, packsDir) }
        tempZip.delete()

        result.onSuccess { manifest ->
            if (entry != null) assignments.set(entry.fileName, manifest.id)
            Toast.makeText(this, manifest.name, Toast.LENGTH_SHORT).show()
        }.onFailure { error ->
            Toast.makeText(this, getString(R.string.pack_import_failed, error.message), Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val entries = library.list()
        adapter.submit(entries)
        when {
            entries.isNotEmpty() -> empty.visibility = View.GONE
            library.folderUri == null -> {
                emptyText.setText(R.string.no_folder_chosen)
                empty.visibility = View.VISIBLE
            }
            else -> {
                emptyText.setText(R.string.no_roms)
                empty.visibility = View.VISIBLE
            }
        }
    }

    private fun openGame(entry: RomEntry) {
        val uri = entry.uri ?: return
        val intent = Intent(this, PlayerActivity::class.java)
        intent.putExtra(PlayerActivity.EXTRA_ROM_URI, uri.toString())
        intent.putExtra(PlayerActivity.EXTRA_SAVE_NAME, entry.saveFileName())
        startActivity(intent)
    }

    companion object {
        private const val REQUEST_PICK_FOLDER = 1
        private const val REQUEST_PICK_PACK = 2
    }
}

private class RomAdapter(
    private val onOpen: (RomEntry) -> Unit,
    private val onOverflow: (RomEntry) -> Unit,
) : RecyclerView.Adapter<RomAdapter.Holder>() {
    private var items = emptyList<RomEntry>()

    fun submit(entries: List<RomEntry>) {
        items = entries
        notifyDataSetChanged()
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val avatar: TextView = view.findViewById(R.id.avatar)
        val title: TextView = view.findViewById(R.id.title)
        val subtitle: TextView = view.findViewById(R.id.subtitle)
        val overflow: ImageView = view.findViewById(R.id.overflow)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_rom, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val entry = items[position]
        holder.avatar.text = entry.title.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        holder.title.text = entry.title
        holder.subtitle.text = "${entry.fileName} · ${entry.gameCode}"
        holder.itemView.setOnClickListener { onOpen(entry) }
        holder.overflow.setOnClickListener { onOverflow(entry) }
    }
}
