package app.carask.ask

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** 통화 다리(bridge/server.py)와 이야기한다. 에뮬레이터에서 맥은 10.0.2.2. */
class BridgeClient(private val base: String = "http://10.0.2.2:8790") {

    suspend fun start(body: JSONObject): String = withContext(Dispatchers.IO) {
        request("POST", "/ask", body).getString("id")
    }

    suspend fun status(id: String): JSONObject = withContext(Dispatchers.IO) { request("GET", "/ask/$id", null) }

    suspend fun cancel(id: String) = withContext(Dispatchers.IO) { runCatching { request("POST", "/ask/$id/cancel", JSONObject()) } }

    private fun request(method: String, path: String, body: JSONObject?): JSONObject {
        val c = URL(base + path).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 5000
        c.readTimeout = 10000
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("content-type", "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
        }
        val text = (if (c.responseCode < 400) c.inputStream else c.errorStream).bufferedReader().use { it.readText() }
        if (c.responseCode >= 400) error("bridge ${c.responseCode}: $text")
        return JSONObject(text)
    }
}
