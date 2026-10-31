package com.example.termuxpanel

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.core.content.ContextCompat

class MainActivity : Activity() {
    private val ACTION = "com.example.termuxpanel.RESULT"
    private val PERM = "com.termux.permission.RUN_COMMAND"
    private val handler = Handler(Looper.getMainLooper())
    private val callbacks = HashMap<Int, (String) -> Unit>()
    private var nextId = 1
    private val items = ArrayList<String>()
    private lateinit var adapter: ArrayAdapter<String>
    private lateinit var status: TextView

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val id = i.getIntExtra("id", -1)
            val out = i.getBundleExtra("result")?.getString("stdout") ?: ""
            callbacks.remove(id)?.invoke(out)
        }
    }

    private val poller = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 5000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(32, 64, 32, 32)
        status = TextView(this)
        status.textSize = 16f
        status.text = "Yukleniyor..."
        val list = ListView(this)
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, items)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ -> runTool(items[pos]) }
        list.setOnItemLongClickListener { _, _, pos, _ ->
            confirmDelete(items[pos])
            true
        }
        root.addView(status)
        root.addView(list)
        setContentView(root)

        ContextCompat.registerReceiver(
            this, receiver, IntentFilter(ACTION), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        if (checkSelfPermission(PERM) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(PERM), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(poller)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(poller)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(receiver)
    }

    private fun termux(script: String, background: Boolean, cb: ((String) -> Unit)? = null) {
        val intent = Intent("com.termux.RUN_COMMAND")
        intent.setClassName("com.termux", "com.termux.app.RunCommandService")
        intent.putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
        intent.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", script))
        intent.putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
        intent.putExtra("com.termux.RUN_COMMAND_BACKGROUND", background)
        if (!background) intent.putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
        if (cb != null) {
            val id = nextId++
            callbacks[id] = cb
            val ri = Intent(ACTION).setPackage(packageName).putExtra("id", id)
            var flags = PendingIntent.FLAG_UPDATE_CURRENT
            if (Build.VERSION.SDK_INT >= 31) flags = flags or PendingIntent.FLAG_MUTABLE
            val pi = PendingIntent.getBroadcast(this, id, ri, flags)
            intent.putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pi)
        }
        try {
            ContextCompat.startForegroundService(this, intent)
        } catch (e: Exception) {
            status.text = "Hata: " + e.message
        }
    }

    private fun refresh() {
        termux("apt-mark showmanual 2>/dev/null | sort", true) { out ->
            val names = out.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.contains(" ") }
            items.clear()
            items.addAll(names)
            adapter.notifyDataSetChanged()
            status.text = items.size.toString() + " arac | tikla: calistir | uzun bas: sil"
        }
    }

    private fun runTool(name: String) {
        val script = "if command -v $name >/dev/null 2>&1; then $name; " +
            "else b=\$(dpkg -L $name 2>/dev/null | grep '/usr/bin/' | head -1); " +
            "if [ -n \"\$b\" ]; then \"\$b\"; else echo 'Calistirilabilir dosya yok'; fi; fi; " +
            "echo; exec bash"
        termux(script, false)
    }

    private fun confirmDelete(name: String) {
        AlertDialog.Builder(this)
            .setTitle("Sil")
            .setMessage(name + " Termux'tan silinsin mi?")
            .setPositiveButton("Sil") { _, _ ->
                status.text = name + " siliniyor..."
                termux("apt remove -y $name 2>&1", true) { refresh() }
            }
            .setNegativeButton("Iptal", null)
            .show()
    }
}
