package com.khumam.gbaduo.library

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.khumam.gbaduo.R
import com.khumam.gbaduo.player.PlayerActivity

class LibraryActivity : Activity() {

    private lateinit var library: RomLibrary
    private lateinit var adapter: RomAdapter
    private lateinit var empty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_library)

        library = RomLibrary(this)
        adapter = RomAdapter { entry -> openGame(entry) }

        findViewById<RecyclerView>(R.id.list).apply {
            layoutManager = LinearLayoutManager(this@LibraryActivity)
            adapter = this@LibraryActivity.adapter
        }
        findViewById<Button>(R.id.choose_folder).setOnClickListener { pickFolder() }
        empty = findViewById(R.id.empty)
    }

    private fun pickFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        startActivityForResult(intent, REQUEST_PICK_FOLDER)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PICK_FOLDER || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        library.folderUri = uri
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val entries = library.list()
        adapter.submit(entries)
        empty.visibility = if (library.folderUri != null && entries.isEmpty()) View.VISIBLE else View.GONE
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
    }
}

private class RomAdapter(private val onOpen: (RomEntry) -> Unit) : RecyclerView.Adapter<RomAdapter.Holder>() {
    private var items = emptyList<RomEntry>()

    fun submit(entries: List<RomEntry>) {
        items = entries
        notifyDataSetChanged()
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.title)
        val subtitle: TextView = view.findViewById(R.id.subtitle)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_rom, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val entry = items[position]
        holder.title.text = entry.title
        holder.subtitle.text = "${entry.fileName} · ${entry.gameCode}"
        holder.itemView.setOnClickListener { onOpen(entry) }
    }
}
