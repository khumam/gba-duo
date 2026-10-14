package com.khumam.gbaduo.library

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
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
import com.khumam.gbaduo.companion.PackInstaller
import com.khumam.gbaduo.companion.PackStore
import com.khumam.gbaduo.player.PlayerActivity
import com.khumam.gbaduo.storage.GameStorage
import com.khumam.gbaduo.storage.LegacyMigration
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class LibraryActivity : Activity() {

    private lateinit var library: RomLibrary
    private lateinit var adapter: RomAdapter
    private lateinit var empty: LinearLayout
    private lateinit var emptyText: TextView

    private val packsDir by lazy { File(filesDir, "packs") }
    private val packStore by lazy { PackStore(packsDir) }
    private var pickPackFor: GameStorage? = null

    /** Folder scans and ROM hashing are blocking SAF I/O; one background thread keeps them ordered. */
    private val io: ExecutorService = Executors.newSingleThreadExecutor()
    private var refreshGeneration = 0

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
        findViewById<ImageView>(R.id.manage_folders).setOnClickListener { showFoldersMenu() }
        empty = findViewById(R.id.empty)
        emptyText = findViewById(R.id.empty_text)
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    /** Runs [work] on [io], then [onResult] on the main thread unless the activity has gone away. */
    private fun <T> background(work: () -> T, onResult: (Result<T>) -> Unit) {
        if (io.isShutdown) return
        io.execute {
            val result = runCatching(work)
            runOnUiThread { if (!isDestroyed) onResult(result) }
        }
    }

    private fun pickFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        startActivityForResult(intent, REQUEST_PICK_FOLDER)
    }

    private fun showFoldersMenu() {
        val folders = library.describeFolders()
        val labels = folders.map { it.name } + getString(R.string.add_folder)
        AlertDialog.Builder(this)
            .setTitle(R.string.manage_folders)
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == folders.size) pickFolder() else confirmRemoveFolder(folders[which])
            }
            .show()
    }

    private fun confirmRemoveFolder(folder: LibraryFolder) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.remove_folder_question, folder.name))
            .setPositiveButton(R.string.remove) { _, _ ->
                library.removeFolder(folder.uri)
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showCompanionMenu(entry: RomEntry) {
        background(
            work = {
                // The game's folder is keyed by CRC; migrating here means an assignment made
                // before the new save layout still shows (and is kept) in this menu.
                val crc = library.crc32(entry) ?: error("Could not read ${entry.fileName}")
                val storage = GameStorage.forGame(filesDir, entry.platform, entry.title, entry.gameCode, crc)
                LegacyMigration.migrate(filesDir, entry.fileName, crc, storage)
                Triple(crc, storage, packStore.matching(entry.gameCode, crc))
            },
            onResult = { result ->
                result.onSuccess { (_, storage, matches) -> showCompanionDialog(storage, matches) }
                    .onFailure { Toast.makeText(this, getString(R.string.load_failed, entry.fileName), Toast.LENGTH_SHORT).show() }
            },
        )
    }

    private fun showCompanionDialog(storage: GameStorage, matches: List<com.khumam.gbaduo.companion.PackManifest>) {
        val assignedId = storage.packId
        val labels = mutableListOf(getString(R.string.no_pack))
        labels += matches.map { pack -> if (pack.id == assignedId) "✓ ${pack.name}" else pack.name }
        labels += getString(R.string.import_pack)

        AlertDialog.Builder(this)
            .setTitle(R.string.companion_pack)
            .setItems(labels.toTypedArray()) { _, which ->
                when (which) {
                    0 -> storage.packId = null
                    labels.size - 1 -> {
                        pickPackFor = storage
                        pickPackZip()
                    }
                    else -> storage.packId = matches[which - 1].id
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
        library.addFolder(uri)
        refresh()
    }

    private fun handlePackPicked(resultCode: Int, data: Intent?) {
        val storage = pickPackFor
        pickPackFor = null
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return

        val tempZip = File(cacheDir, "import.zip")
        contentResolver.openInputStream(uri)?.use { input ->
            tempZip.outputStream().use { output -> input.copyTo(output) }
        }
        val result = runCatching { PackInstaller.install(tempZip, packsDir) }
        tempZip.delete()

        result.onSuccess { manifest ->
            storage?.packId = manifest.id
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
        val generation = ++refreshGeneration
        val hasFolders = library.folders.isNotEmpty()
        if (adapter.itemCount == 0) {
            emptyText.setText(if (hasFolders) R.string.scanning else R.string.no_folder_chosen)
            empty.visibility = View.VISIBLE
        }
        background(work = { library.list() }) { result ->
            if (generation != refreshGeneration) return@background // a newer refresh superseded this one
            val entries = result.getOrDefault(emptyList())
            adapter.submit(entries)
            when {
                entries.isNotEmpty() -> empty.visibility = View.GONE
                !hasFolders -> {
                    emptyText.setText(R.string.no_folder_chosen)
                    empty.visibility = View.VISIBLE
                }
                else -> {
                    emptyText.text = resources.getQuantityString(R.plurals.no_roms, library.folders.size, library.folders.size)
                    empty.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun openGame(entry: RomEntry) {
        val uri = entry.uri ?: return
        val intent = Intent(this, PlayerActivity::class.java)
        intent.putExtra(PlayerActivity.EXTRA_ROM_URI, uri.toString())
        intent.putExtra(PlayerActivity.EXTRA_ROM_FILE_NAME, entry.fileName)
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
        val platform: TextView = view.findViewById(R.id.platform)
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
        holder.platform.text = entry.platform.label
        holder.subtitle.text = listOf(entry.fileName, entry.gameCode).filter { it.isNotBlank() }.joinToString(" · ")
        holder.itemView.setOnClickListener { onOpen(entry) }
        holder.overflow.setOnClickListener { onOverflow(entry) }
    }
}
