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
import android.widget.Button
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
    private var scriptMode = true
    private lateinit var adapter: ArrayAdapter<String>
    private lateinit var status: TextView
    private lateinit var toggle: Button

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

    private fun q(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(32, 64, 32, 32)

        toggle = Button(this)
        toggle.text = "Paketlere gec"
        toggle.setOnClickListener {
            scriptMode = !scriptMode
            toggle.text = if (scriptMode) "Paketlere gec" else "Scriptlere gec"
            items.clear()
            adapter.notifyDataSetChanged()
            status.text = "Yukleniyor..."
            refresh()
        }

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
        root.addView(toggle)
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
        val wasScript = scriptMode
        val cmd = if (wasScript) {
            """cd ~ && find . -maxdepth 3 -type f \( -name '*.py' -o -name '*.sh' -o -name '*.js' \) -not -path '*/.*' -not -path '*/node_modules/*' -not -path '*/site-packages/*' | sort"""
        } else {
            "apt-mark showmanual 2>/dev/null | sort"
        }
        termux(cmd, true) { out ->
            if (wasScript == scriptMode) {
                val names = out.lines()
                    .map { it.trim().removePrefix("./") }
                    .filter { it.isNotEmpty() && (wasScript || !it.contains(" ")) }
                items.clear()
                items.addAll(names)
                adapter.notifyDataSetChanged()
                status.text = items.size.toString() + " ogе | tikla: calistir | uzun bas: sil"
            }
        }
    }

    private fun runTool(name: String) {
        if (scriptMode) {
            val dir = name.substringBeforeLast('/', "")
            val file = name.substringAfterLast('/')
            val interp = when (file.substringAfterLast('.', "")) {
                "py" -> "python"
                "js" -> "node"
                else -> "bash"
            }
            val cd = if (dir.isEmpty()) "cd ~" else "cd ~/" + q(dir)
            termux(cd + " && " + interp + " " + q(file) + "; echo; exec bash", false)
        } else {
            val script = "if command -v $name >/dev/null 2>&1; then $name; " +
                "else echo 'Calistirilabilir dosya yok'; fi; echo; exec bash"
            termux(script, false)
        }
    }

    private fun confirmDelete(name: String) {
        AlertDialog.Builder(this)
            .setTitle("Sil")
            .setMessage(name + " silinsin mi?")
            .setPositiveButton("Sil") { _, _ ->
                status.text = name + " siliniyor..."
                val cmd = if (scriptMode) {
                    "cd ~ && rm -f " + q(name)
                } else {
                    "apt remove -y $name 2>&1"
                }
                termux(cmd, true) { refresh() }
            }
            .setNegativeButton("Iptal", null)
            .show()
    }
}
