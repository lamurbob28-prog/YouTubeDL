package dev.lamurbob.youtubedl

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal enum class DownloadStatus {
    QUEUED, PREPARING, DOWNLOADING, SAVING, COMPLETE, FAILED, CANCELLED, INTERRUPTED;
    val active get() = this in listOf(QUEUED, PREPARING, DOWNLOADING, SAVING)
}

internal data class DownloadItem(
    val id: String = UUID.randomUUID().toString(),
    val url: String,
    val height: Int,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val progress: Int = -1,
    val message: String = "Waiting in queue",
    val details: String = "",
    val name: String = "",
    val uri: String = ""
)

internal object DownloadStore {
    private lateinit var preferences: SharedPreferences
    private val mutableItems = MutableStateFlow<List<DownloadItem>>(emptyList())
    val items = mutableItems.asStateFlow()
    val engineMessage = MutableStateFlow("Engine ready · automatic update checks")
    val engineBusy = MutableStateFlow(false)

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences("download_history", Context.MODE_PRIVATE)
        val stored = runCatching { JSONArray(preferences.getString("items", "[]")) }
            .getOrDefault(JSONArray())
        mutableItems.value = (0 until stored.length()).mapNotNull { index ->
            runCatching {
                val json = stored.getJSONObject(index)
                val status = DownloadStatus.valueOf(json.getString("status"))
                DownloadItem(
                    id = json.getString("id"), url = json.getString("url"),
                    height = json.getInt("height"),
                    status = if (status.active) DownloadStatus.INTERRUPTED else status,
                    message = if (status.active) "Interrupted. Tap Retry to start again." else json.optString("message"),
                    name = json.optString("name"), uri = json.optString("uri"),
                    details = json.optString("details")
                )
            }.getOrNull()
        }
        persist()
    }

    @Synchronized fun add(url: String, height: Int): DownloadItem? {
        if (mutableItems.value.count { it.status.active } >= 20) return null
        val item = DownloadItem(url = url, height = height)
        mutableItems.value = (mutableItems.value.filter { it.status.active } +
            mutableItems.value.filterNot { it.status.active }.takeLast(49) + item)
        persist()
        return item
    }

    @Synchronized fun update(id: String, persist: Boolean = true, transform: (DownloadItem) -> DownloadItem) {
        mutableItems.value = mutableItems.value.map { if (it.id == id) transform(it) else it }
        if (persist) persist()
    }

    @Synchronized fun clearFinished() {
        mutableItems.value = mutableItems.value.filter { it.status.active }
        persist()
    }

    private fun persist() {
        val array = JSONArray()
        mutableItems.value.forEach { item ->
            array.put(JSONObject().apply {
                put("id", item.id); put("url", item.url); put("height", item.height)
                put("status", item.status.name); put("message", item.message)
                put("name", item.name); put("uri", item.uri); put("details", item.details)
            })
        }
        preferences.edit().putString("items", array.toString()).apply()
    }
}
