package com.example.texteditor

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.Spannable
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.view.Menu
import android.view.MenuItem
import android.widget.*
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.GravityCompat
import androidx.core.view.isVisible
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.navigation.NavigationView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    // Drawer
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navView: NavigationView

    // Editor UI
    private lateinit var editor: EditText
    private lateinit var lineNumbers: TextView
    private lateinit var status: TextView
    private lateinit var findBarWrapper: HorizontalScrollView
    private lateinit var findBar: LinearLayout
    private lateinit var findText: EditText
    private lateinit var replaceText: EditText
    private lateinit var caseSensitive: CheckBox
    private lateinit var wholeWord: CheckBox
    private lateinit var btnFindPrev: Button
    private lateinit var btnFindNext: Button
    private lateinit var btnReplace: Button

    // State
    private var currentUri: Uri? = null
    private val history = UndoManager()
    private var highlightJob: Job? = null
    private var searchMatches: List<IntRange> = emptyList()
    private var currentMatchIndex = -1
    private var suppressHistory = false
    private var beforeChangeText: String = ""

    // Generic language config (null = Kotlin built-in)
    private var currentConfig: SyntaxConfig? = null

    // HTTP compiler (works via adb reverse)
    private var compilerUrl: String
        get() = getSharedPreferences("settings", MODE_PRIVATE)
            .getString("compilerUrl", "http://127.0.0.1:8123/compile")!!
        set(value) {
            getSharedPreferences("settings", MODE_PRIVATE)
                .edit().putString("compilerUrl", value).apply()
        }

    // SAF launchers
    private val openDoc =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val uri = r.data?.data ?: return@registerForActivityResult
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) {}
            val text = FileIo.readText(this, uri)
            suppressHistory = true
            editor.setText(text)
            suppressHistory = false
            currentUri = uri
            currentConfig = null // default to Kotlin when opening .kt, otherwise user can load config
            updateTitle()
            updateStatus("Opened")
            updateLineNumbers()
            applyHighlightNow()
        }

    private val createDoc =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val uri = r.data?.data ?: return@registerForActivityResult
            FileIo.writeText(this, uri, editor.text.toString())
            currentUri = uri
            updateTitle()
            updateStatus("Saved")
        }

    private val openConfig =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val uri = r.data?.data ?: return@registerForActivityResult
            val cfg = loadSyntaxConfig(uri)
            if (cfg != null) {
                currentConfig = cfg
                supportActionBar?.subtitle = "Lang: ${cfg.name}"
                updateStatus("Loaded ${cfg.name} rules")
                applyHighlightNow()
            } else {
                toast("Failed to load syntax rules")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Apply saved theme BEFORE inflating
        ThemeUtils.applySaved(this)
        setContentView(R.layout.activity_main)

        // Toolbar + Drawer
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)

        drawerLayout = findViewById(R.id.drawerLayout)
        navView = findViewById(R.id.navView)

        val toggle = ActionBarDrawerToggle(
            this, drawerLayout, toolbar,
            R.string.navigation_drawer_open, R.string.navigation_drawer_close
        )
        drawerLayout.addDrawerListener(toggle)
        toggle.syncState()

        // Drawer items
        navView.setNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_new           -> newFile()
                R.id.nav_open          -> openFile()
                R.id.nav_save          -> saveFile()
                R.id.nav_find          -> toggleFindBar()
                R.id.nav_undo          -> undo()
                R.id.nav_redo          -> redo()
                R.id.nav_compile       -> compileNow()
                R.id.nav_compile_setup -> showCompilerSetup()
                R.id.nav_theme         -> showThemePicker()
                R.id.nav_lang_kotlin   -> { currentConfig = null; supportActionBar?.subtitle = "Lang: Kotlin"; applyHighlightNow() }
                R.id.nav_lang_load     -> pickConfigFile()
            }
            drawerLayout.closeDrawer(GravityCompat.START)
            true
        }

        // Back dispatcher (replaces deprecated onBackPressed)
        onBackPressedDispatcher.addCallback(this) {
            if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                drawerLayout.closeDrawer(GravityCompat.START)
            } else {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        }

        // Bind views
        editor = findViewById(R.id.editor)
        lineNumbers = findViewById(R.id.lineNumbers)
        status = findViewById(R.id.status)
        findBarWrapper = findViewById(R.id.findBarWrapper)
        findBar = findViewById(R.id.findBar)
        findText = findViewById(R.id.findText)
        replaceText = findViewById(R.id.replaceText)
        caseSensitive = findViewById(R.id.caseSensitive)
        wholeWord = findViewById(R.id.wholeWord)
        btnFindPrev = findViewById(R.id.btnFindPrev)
        btnFindNext = findViewById(R.id.btnFindNext)
        btnReplace = findViewById(R.id.btnReplace)

        btnFindNext.setOnClickListener { gotoMatch(+1) }
        btnFindPrev.setOnClickListener { gotoMatch(-1) }
        btnReplace.setOnClickListener { replaceOne() }

        // Live search
        fun refreshSearch() = searchAndHighlightAll(findText.text.toString())
        findText.addTextChangedListener(simpleWatcher { refreshSearch() })
        caseSensitive.setOnCheckedChangeListener { _, _ -> refreshSearch() }
        wholeWord.setOnCheckedChangeListener { _, _ -> refreshSearch() }

        // Editor watcher (undo + status + highlight + line numbers)
        editor.addTextChangedListener(object : TextWatcher {
            private var debouncer: Job? = null
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                if (!suppressHistory) beforeChangeText = s?.toString() ?: ""
            }
            override fun afterTextChanged(s: Editable?) {
                if (!suppressHistory) {
                    val now = s?.toString() ?: ""
                    if (now != beforeChangeText) {
                        history.push(beforeChangeText)
                        history.clearRedo()
                    }
                }
                debouncer?.cancel()
                debouncer = lifecycleScope.launch {
                    delay(150)
                    updateStatus()
                    updateLineNumbers()
                    applyHighlightNow()
                    if (findBarWrapper.isVisible) searchAndHighlightAll(findText.text.toString())
                }
            }
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        // Initial
        updateTitle()
        supportActionBar?.subtitle = "Lang: Kotlin"
        history.reset()
        updateStatus()
        updateLineNumbers()
        applyHighlightNow()
    }

    // Inflate & handle top app bar actions
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_top, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_compile -> { compileNow(); true }
            R.id.action_find    -> { toggleFindBar(); true }
            R.id.action_undo    -> { undo(); true }
            R.id.action_redo    -> { redo(); true }
            R.id.action_theme   -> { showThemePicker(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // ---------- Title ----------
    private fun updateTitle() {
        val name = currentUri?.lastPathSegment ?: "untitled.txt"
        supportActionBar?.title = "Text Editor - $name"
    }

    // ---------- File ops ----------
    private fun newFile() {
        suppressHistory = true
        editor.setText("")
        suppressHistory = false
        history.reset()
        currentUri = null
        updateTitle()
        updateStatus("New file")
        updateLineNumbers()
        applyHighlightNow()
    }

    private fun openFile() = openDoc.launch(FileIo.makeOpenDocumentIntent())

    private fun saveFile() {
        val uri = currentUri
        if (uri == null) {
            createDoc.launch(FileIo.makeCreateDocumentIntent("untitled.txt"))
        } else {
            FileIo.writeText(this, uri, editor.text.toString())
            updateStatus("Saved")
        }
    }

    override fun onPause() {
        super.onPause()
        currentUri?.let { FileIo.writeText(this, it, editor.text.toString()) }
    }

    // ---------- Undo/Redo ----------
    private fun undo() {
        val prev = history.undo(editor.text.toString()) ?: return
        suppressHistory = true
        setTextKeepState(prev)
        suppressHistory = false
        updateStatus("Undo")
        updateLineNumbers()
        applyHighlightNow()
    }

    private fun redo() {
        val next = history.redo(editor.text.toString()) ?: return
        suppressHistory = true
        setTextKeepState(next)
        suppressHistory = false
        updateStatus("Redo")
        updateLineNumbers()
        applyHighlightNow()
    }

    // ---------- Status ----------
    private fun updateStatus(extra: String? = null) {
        val t = editor.text?.toString().orEmpty()
        val chars = t.length
        val words = Regex("""\b\w+\b""").findAll(t).count()
        status.text = "Status: ${extra ?: "Ready"}   |   Words: $words   Chars: $chars"
    }

    // ---------- Line numbers ----------
    private fun updateLineNumbers() {
        val lines = editor.text?.count { it == '\n' }?.plus(1) ?: 1
        val sb = StringBuilder(lines * 3)
        for (i in 1..lines) sb.append(i).append('\n')
        if (sb.isNotEmpty()) sb.setLength(sb.length - 1)
        lineNumbers.text = sb.toString()
    }

    // ---------- Find / Replace ----------
    private fun toggleFindBar() {
        findBarWrapper.isVisible = !findBarWrapper.isVisible
        if (findBarWrapper.isVisible) searchAndHighlightAll(findText.text.toString()) else clearSearchHighlights()
    }

    private fun searchAndHighlightAll(query: String) {
        clearSearchHighlights()
        if (query.isEmpty()) return

        val text = editor.text.toString()
        val pattern = buildString {
            if (wholeWord.isChecked) append("""\b""")
            append(Regex.escape(query))
            if (wholeWord.isChecked) append("""\b""")
        }
        val flags = if (caseSensitive.isChecked) emptySet() else setOf(RegexOption.IGNORE_CASE)

        searchMatches = Regex(pattern, flags).findAll(text).map { it.range }.toList()
        currentMatchIndex = if (searchMatches.isNotEmpty()) 0 else -1

        val sp = editor.text as Spannable
        for (r in searchMatches) {
            try {
                sp.setSpan(
                    BackgroundColorSpan(0x30ffeb3b.toInt()),
                    r.first, r.last + 1,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            } catch (_: Throwable) { }
        }
        if (currentMatchIndex >= 0) focusCurrentMatch()
        updateStatus("${searchMatches.size} match(es)")
    }

    private fun gotoMatch(step: Int) {
        if (searchMatches.isEmpty()) return
        currentMatchIndex = (currentMatchIndex + step + searchMatches.size) % searchMatches.size
        focusCurrentMatch()
    }

    private fun focusCurrentMatch() {
        val r = searchMatches[currentMatchIndex]
        editor.setSelection(r.first, r.last + 1)
    }

    private fun replaceOne() {
        if (searchMatches.isEmpty()) return
        val r = searchMatches[currentMatchIndex]
        editor.text.replace(r.first, r.last + 1, replaceText.text.toString())
        searchAndHighlightAll(findText.text.toString())
    }

    private fun clearSearchHighlights() {
        val sp = editor.text as Spannable
        sp.getSpans(0, sp.length, BackgroundColorSpan::class.java).forEach { sp.removeSpan(it) }
        searchMatches = emptyList()
        currentMatchIndex = -1
    }

    // ---------- Highlighting ----------
    private fun applyHighlightNow() {
        if (currentConfig == null) applyKotlinHighlight() else applyConfigHighlight(currentConfig!!)
    }

    private fun applyKotlinHighlight() {
        highlightJob?.cancel()
        highlightJob = lifecycleScope.launch {
            try {
                val s = editor.text
                // remove only color spans (keep search bg spans)
                s.getSpans(0, s.length, ForegroundColorSpan::class.java).forEach { s.removeSpan(it) }
                val src = s.toString()

                // Strings
                Regex("\"\"\"[\\s\\S]*?\"\"\"|\"(?:\\\\.|[^\"\\\\])*\"").findAll(src).forEach {
                    s.setSpan(ForegroundColorSpan(0xffce9178.toInt()), it.range.first, it.range.last + 1, 0)
                }
                // Comments
                Regex("//.*").findAll(src).forEach {
                    s.setSpan(ForegroundColorSpan(0xff6a9955.toInt()), it.range.first, it.range.last + 1, 0)
                }
                Regex("/\\*[\\s\\S]*?\\*/").findAll(src).forEach {
                    s.setSpan(ForegroundColorSpan(0xff6a9955.toInt()), it.range.first, it.range.last + 1, 0)
                }
                // Keywords
                val keywords = setOf(
                    "val","var","fun","class","object","interface","if","else","when","for","while",
                    "return","break","continue","try","catch","finally","is","in","as","this","super",
                    "package","import","private","public","protected","internal","data","sealed","enum",
                    "companion","null","true","false"
                )
                Regex("""\b[_A-Za-z][_A-Za-z0-9]*\b""").findAll(src).forEach { m ->
                    if (keywords.contains(m.value)) {
                        s.setSpan(ForegroundColorSpan(0xff569cd6.toInt()), m.range.first, m.range.last + 1, 0)
                    }
                }
            } catch (_: Throwable) { }
        }
    }

    private fun applyConfigHighlight(cfg: SyntaxConfig) {
        highlightJob?.cancel()
        highlightJob = lifecycleScope.launch {
            try {
                val s = editor.text
                s.getSpans(0, s.length, ForegroundColorSpan::class.java).forEach { s.removeSpan(it) }
                val src = s.toString()

                // Strings
                cfg.strings.forEach { q ->
                    if (q.isNotEmpty()) {
                        val regex = Regex("${Regex.escape(q)}(?:\\\\.|(?!${Regex.escape(q)}).)*${Regex.escape(q)}",
                            RegexOption.DOT_MATCHES_ALL)
                        regex.findAll(src).forEach {
                            s.setSpan(ForegroundColorSpan(0xffce9178.toInt()), it.range.first, it.range.last + 1, 0)
                        }
                    }
                }
                // Line comments
                cfg.lineComments.forEach { mark ->
                    if (mark.isNotEmpty()) {
                        Regex("${Regex.escape(mark)}.*").findAll(src).forEach {
                            s.setSpan(ForegroundColorSpan(0xff6a9955.toInt()), it.range.first, it.range.last + 1, 0)
                        }
                    }
                }
                // Block comments
                cfg.blockComments.forEach { (start, end) ->
                    if (start.isNotEmpty() && end.isNotEmpty()) {
                        val regex = Regex("${Regex.escape(start)}[\\s\\S]*?${Regex.escape(end)}")
                        regex.findAll(src).forEach {
                            s.setSpan(ForegroundColorSpan(0xff6a9955.toInt()), it.range.first, it.range.last + 1, 0)
                        }
                    }
                }
                // Keywords
                if (cfg.keywords.isNotEmpty()) {
                    val id = Regex("""\b[_A-Za-z][_A-Za-z0-9]*\b""")
                    id.findAll(src).forEach { m ->
                        if (cfg.keywords.contains(m.value)) {
                            s.setSpan(ForegroundColorSpan(0xff569cd6.toInt()), m.range.first, m.range.last + 1, 0)
                        }
                    }
                }
            } catch (_: Throwable) { }
        }
    }

    // ---------- Compile via HTTP (ADB reverse friendly) ----------
    private var errorSpans: MutableList<Any> = mutableListOf()

    private fun compileNow() {
        // Clear previous error decorations
        val sp = editor.text as Spannable
        errorSpans.forEach { runCatching { sp.removeSpan(it) } }
        errorSpans.clear()
        updateStatus("Compiling…")

        val source = editor.text.toString()
        val fileName = currentUri?.lastPathSegment ?: "Main.kt"

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val url = URL(compilerUrl)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10000
                    readTimeout = 20000
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
                val payload = JSONObject().apply {
                    put("filename", fileName)
                    put("language", currentConfig?.name ?: "kotlin")
                    put("source", source)
                }.toString()

                conn.outputStream.use { it.write(payload.toByteArray()) }
                val code = conn.responseCode
                val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.use { BufferedReader(InputStreamReader(it)).readText() }
                    ?: ""

                val json = JSONObject(body)
                val success = json.optBoolean("success", false)
                val output = json.optString("output", "")

                val errors = json.optJSONArray("errors")
                val diags = mutableListOf<Diag>()
                if (errors != null) {
                    for (i in 0 until errors.length()) {
                        val e = errors.getJSONObject(i)
                        diags += Diag(
                            line = e.optInt("line", 1),
                            col = e.optInt("col", 1),
                            message = e.optString("message", "Error")
                        )
                    }
                }

                withContext(Dispatchers.Main) {
                    if (success) {
                        updateStatus("Compilation success")
                        if (output.isNotBlank()) toast(output)
                    } else {
                        updateStatus("Compilation failed")
                        if (output.isNotBlank()) toast(output)
                        showDiagnostics(diags)
                    }
                }
            } catch (ex: Exception) {
                withContext(Dispatchers.Main) {
                    updateStatus("Compile error")
                    toast("Compile error: ${ex.localizedMessage}")
                }
            }
        }
    }

    private fun showDiagnostics(diags: List<Diag>) {
        if (diags.isEmpty()) return
        val sp = editor.text as Spannable
        val text = editor.text.toString()
        val lines = text.split('\n')

        diags.forEach { d ->
            val ln = (d.line - 1).coerceIn(0, lines.lastIndex)
            var start = 0
            for (i in 0 until ln) start += lines[i].length + 1
            val end = start + lines[ln].length
            // pale red background
            val bg = BackgroundColorSpan(0x30ff0000)
            sp.setSpan(bg, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            errorSpans += bg
        }

        val msg = buildString {
            append("Errors:\n")
            diags.take(5).forEach { append("Ln ${it.line}: ${it.message}\n") }
            if (diags.size > 5) append("…+${diags.size - 5} more")
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Compiler Diagnostics")
            .setMessage(msg)
            .setPositiveButton("OK", null)
            .show()
    }

    data class Diag(val line: Int, val col: Int, val message: String)

    // ---------- Pick syntax config ----------
    private fun pickConfigFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/xml", "application/xml", "text/plain"))
        }
        openConfig.launch(intent)
    }

    private fun loadSyntaxConfig(uri: Uri): SyntaxConfig? = runCatching {
        contentResolver.openInputStream(uri)?.use { ins ->
            val head = ins.bufferedReader().readText()
            // Simple sniff: JSON or XML
            if (head.trimStart().startsWith("{")) parseJsonConfig(head)
            else parseXmlConfig(head)
        }
    }.getOrNull()

    private fun parseJsonConfig(raw: String): SyntaxConfig {
        val o = JSONObject(raw)
        val name = o.optString("name", "Custom")
        fun arr(key: String): List<String> =
            o.optJSONArray(key)?.let { a ->
                (0 until a.length()).map { a.getString(it) }
            } ?: emptyList()
        val keywords = arr("keywords").toSet()
        val strings = arr("strings")
        val line = when {
            o.has("lineComment") -> listOf(o.getString("lineComment"))
            o.has("lineComments") -> arr("lineComments")
            else -> emptyList()
        }
        val blocks = mutableListOf<Pair<String, String>>()
        if (o.has("blockComment")) {
            val bc = o.getJSONObject("blockComment")
            blocks += bc.optString("start","") to bc.optString("end","")
        } else if (o.has("blockComments")) {
            val arrB = o.getJSONArray("blockComments")
            for (i in 0 until arrB.length()) {
                val bc = arrB.getJSONObject(i)
                blocks += bc.optString("start","") to bc.optString("end","")
            }
        }
        return SyntaxConfig(name, keywords, strings, line, blocks)
    }

    private fun parseXmlConfig(raw: String): SyntaxConfig {
        val x = XmlPullParserFactory.newInstance().newPullParser()
        x.setInput(raw.reader())
        var event = x.eventType
        var name = "Custom"
        val keywords = mutableSetOf<String>()
        val strings = mutableListOf<String>()
        val line = mutableListOf<String>()
        val blocks = mutableListOf<Pair<String, String>>()

        fun splitCsv(s: String): List<String> = s.split(',').map { it.trim() }.filter { it.isNotEmpty() }

        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (x.name) {
                    "language" -> name = x.getAttributeValue(null, "name") ?: "Custom"
                    "keywords" -> keywords += splitCsv(x.nextText())
                    "strings" -> strings += splitCsv(x.nextText())
                    "lineComment" -> line += x.nextText().trim()
                    "lineComments" -> line += splitCsv(x.nextText())
                    "blockComment" -> {
                        val s = x.getAttributeValue(null, "start") ?: ""
                        val e = x.getAttributeValue(null, "end") ?: ""
                        blocks += s to e
                    }
                }
            }
            event = x.next()
        }
        return SyntaxConfig(name, keywords, strings, line, blocks)
    }

    // ---------- Small utils ----------
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun setTextKeepState(newText: String) {
        val selStart = editor.selectionStart
        val selEnd = editor.selectionEnd
        editor.setText(newText)
        val end = newText.length.coerceAtMost(selEnd)
        editor.setSelection(selStart.coerceAtMost(end), end)
    }

    private fun simpleWatcher(after: (Editable?) -> Unit) = object : TextWatcher {
        override fun afterTextChanged(s: Editable?) = after(s)
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
    }

    // ===== Theme picker =====
    private fun showThemePicker() {
        val opts = arrayOf("System default", "Light", "Dark")
        val current = ThemeUtils.getSavedIndex(this)
        MaterialAlertDialogBuilder(this)
            .setTitle("Theme")
            .setSingleChoiceItems(opts, current) { dlg, which ->
                ThemeUtils.setMode(this, which)
                dlg.dismiss()
            }
            .show()
    }

    private fun showCompilerSetup() {
        val input = EditText(this).apply {
            setText(compilerUrl)
            hint = "http://127.0.0.1:8123/compile"
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Compiler Endpoint")
            .setMessage("Tip: run on your laptop:\n\nadb reverse tcp:8123 tcp:8123\n\nthen keep default URL.")
            .setView(input)
            .setPositiveButton("Save") { _, _ -> compilerUrl = input.text.toString().trim() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    object ThemeUtils {
        private const val PREFS = "settings"
        private const val KEY = "theme_mode" // 0=system, 1=light, 2=dark

        fun applySaved(activity: AppCompatActivity) {
            val i = activity.getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY, 0)
            AppCompatDelegate.setDefaultNightMode(
                when (i) {
                    1 -> AppCompatDelegate.MODE_NIGHT_NO
                    2 -> AppCompatDelegate.MODE_NIGHT_YES
                    else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
            )
        }
        fun getSavedIndex(activity: AppCompatActivity): Int =
            activity.getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY, 0)
        fun setMode(activity: AppCompatActivity, index: Int) {
            activity.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(KEY, index).apply()
            AppCompatDelegate.setDefaultNightMode(
                when (index) {
                    1 -> AppCompatDelegate.MODE_NIGHT_NO
                    2 -> AppCompatDelegate.MODE_NIGHT_YES
                    else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
            )
        }
    }

    // File I/O
    object FileIo {
        fun makeOpenDocumentIntent(): Intent =
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                    "text/plain",
                    "text/x-kotlin",
                    "text/x-java",
                    "application/octet-stream",
                    "application/*"
                ))
            }
        fun makeCreateDocumentIntent(suggestedName: String): Intent =
            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "text/plain"
                putExtra(Intent.EXTRA_TITLE, suggestedName)
            }
        fun readText(activity: AppCompatActivity, uri: Uri): String =
            activity.contentResolver.openInputStream(uri)?.use { it.reader().readText() } ?: ""
        fun writeText(activity: AppCompatActivity, uri: Uri, content: String) {
            activity.contentResolver.openOutputStream(uri)?.use { os ->
                os.write(content.toByteArray()); os.flush()
            }
        }
    }

    // Undo manager
    class UndoManager {
        private val undo = ArrayDeque<String>()
        private val redo = ArrayDeque<String>()
        fun push(previousText: String) {
            if (undo.isEmpty() || undo.last() != previousText) {
                undo.addLast(previousText); if (undo.size > 200) undo.removeFirst()
            }
        }
        fun clearRedo() = redo.clear()
        fun undo(current: String): String? {
            val prev = undo.removeLastOrNull() ?: return null
            redo.addLast(current); return prev
        }
        fun redo(current: String): String? {
            val next = redo.removeLastOrNull() ?: return null
            undo.addLast(current); return next
        }
        fun reset() { undo.clear(); redo.clear() }
    }

    // Config structure
    data class SyntaxConfig(
        val name: String,
        val keywords: Set<String>,
        val strings: List<String>,
        val lineComments: List<String>,
        val blockComments: List<Pair<String, String>>
    )
}
