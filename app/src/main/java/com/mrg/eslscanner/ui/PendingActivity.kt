package com.mrg.eslscanner.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mrg.eslscanner.data.ConfigStore
import com.mrg.eslscanner.data.OracleClient
import com.mrg.eslscanner.data.OracleResult
import com.mrg.eslscanner.databinding.ActivityPendingBinding
import com.mrg.eslscanner.databinding.ItemPendingBinding
import com.mrg.eslscanner.db.AppDatabase
import com.mrg.eslscanner.db.PendingScan
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

class PendingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPendingBinding
    private lateinit var configStore: ConfigStore
    private val adapter = PendingAdapter { retry(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPendingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        configStore = ConfigStore(this)

        binding.pendingRecycler.layoutManager = LinearLayoutManager(this)
        binding.pendingRecycler.adapter = adapter

        loadData()
    }

    private fun loadData() {
        lifecycleScope.launch {
            val db = AppDatabase.get(this@PendingActivity)
            adapter.submit(db.pendingScanDao().getRecent())
        }
    }

    private fun retry(scan: PendingScan) {
        val settings = configStore.load()
        lifecycleScope.launch {
            val client = OracleClient(settings)
            val db = AppDatabase.get(this@PendingActivity)
            when (val result = client.sendScan(scan.productCode, scan.eslBarcode)) {
                is OracleResult.Success -> db.pendingScanDao().update(scan.copy(status = "SENT"))
                is OracleResult.Failure -> db.pendingScanDao().update(scan.copy(status = "FAILED", lastError = result.error))
            }
            loadData()
        }
    }
}

class PendingAdapter(
    private val onRetry: (PendingScan) -> Unit
) : RecyclerView.Adapter<PendingAdapter.VH>() {

    private var items: List<PendingScan> = emptyList()
    private val dateFormat = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())

    fun submit(newItems: List<PendingScan>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
        val binding = ItemPendingBinding.inflate(
            android.view.LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.binding.productText.text = "Товар: ${item.productCode}"
        holder.binding.eslText.text = "Ценник: ${item.eslBarcode}"
        holder.binding.statusText.text = "${item.status} · ${dateFormat.format(item.scannedAt)}" +
            (item.lastError?.let { " · $it" } ?: "")
        holder.binding.retryButton.isEnabled = item.status != "SENT"
        holder.binding.retryButton.setOnClickListener { onRetry(item) }
    }

    override fun getItemCount() = items.size

    class VH(val binding: ItemPendingBinding) : RecyclerView.ViewHolder(binding.root)
}
