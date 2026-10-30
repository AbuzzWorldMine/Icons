package com.wallicons.app

import android.app.Activity
import android.app.WallpaperManager
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.content.res.Configuration
import android.graphics.*
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.*
import android.widget.*
import java.io.File
import java.io.FileOutputStream

class MainActivity : Activity() {
    data class App(val pkg: String, val label: String)
    data class Cfg(val accent: Int, val keep: Boolean, val mode: Int, val blur: Int, val dim: Int)

    private var all = listOf<App>()
    private var shown = listOf<App>()
    private val checked = HashSet<String>()
    private val icons = HashMap<String, Drawable>()
    private var bg: Bitmap? = null
    private lateinit var adapter: BaseAdapter
    private lateinit var status: TextView
    private lateinit var preview: ImageView
    private lateinit var previewBox: FrameLayout
    private lateinit var keep: CheckBox
    private lateinit var modes: RadioGroup
    private lateinit var blurBar: SeekBar
    private lateinit var dimBar: SeekBar
    private val ui = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("wi", MODE_PRIVATE) }
    private val listener = WallpaperManager.OnColorsChangedListener { _, _ -> runOnUiThread { autoRefresh() } }
    private val seek = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { updatePreview() }
        override fun onStartTrackingTouch(s: SeekBar?) {}
        override fun onStopTrackingTouch(s: SeekBar?) {}
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val pm = packageManager
        all = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { App(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .filter { it.pkg != packageName }.distinctBy { it.pkg }.sortedBy { it.label.lowercase() }
        shown = all
        checked.addAll(prefs.getStringSet("pkgs", emptySet())!!)
        File(filesDir, "bg.jpg").takeIf { it.exists() }?.let { bg = BitmapFactory.decodeFile(it.path) }

        val head = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(40), dp(16), dp(8)) }
        head.addView(TextView(this).apply { text = "WallIcons"; textSize = 24f; setTypeface(null, Typeface.BOLD) })

        previewBox = FrameLayout(this).apply { setPadding(dp(16), dp(16), dp(16), dp(16)) }
        preview = ImageView(this).apply {
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, v.width * 0.28f) }
            }
            clipToOutline = true
        }
        previewBox.addView(preview, FrameLayout.LayoutParams(dp(96), dp(96), Gravity.CENTER))
        head.addView(previewBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        modes = RadioGroup(this)
        listOf("Без фона: видны обои", "Цвет из обоев", "Своя картинка").forEachIndexed { i, t ->
            modes.addView(RadioButton(this).apply { id = 100 + i; text = t })
        }
        modes.check(100 + prefs.getInt("mode", 0))
        head.addView(modes)

        head.addView(Button(this).apply {
            text = "Выбрать картинку из галереи"
            setOnClickListener { startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*"), 1) }
        })
        head.addView(TextView(this).apply { text = "Размытие картинки" })
        blurBar = SeekBar(this).apply { max = 100; progress = prefs.getInt("blur", 30) }
        head.addView(blurBar)
        head.addView(TextView(this).apply { text = "Затемнение картинки" })
        dimBar = SeekBar(this).apply { max = 80; progress = prefs.getInt("dim", 25) }
        head.addView(dimBar)
        keep = CheckBox(this).apply { text = "Сохранять цвета иконок"; isChecked = prefs.getBoolean("keep", false) }
        head.addView(keep)

        val search = EditText(this).apply { hint = "Поиск приложения"; setSingleLine() }
        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString()?.trim().orEmpty()
                shown = all.filter { it.label.contains(q, ignoreCase = true) }
                adapter.notifyDataSetChanged()
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        head.addView(search)
        head.addView(Button(this).apply {
            text = "Выбрать все / снять все"
            setOnClickListener {
                val pk = shown.map { it.pkg }
                if (checked.containsAll(pk)) checked.removeAll(pk.toSet()) else checked.addAll(pk)
                adapter.notifyDataSetChanged(); updatePreview()
            }
        })

        val lv = ListView(this)
        lv.addHeaderView(head, null, false)
        adapter = object : BaseAdapter() {
            override fun getCount() = shown.size
            override fun getItem(p: Int) = shown[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, v: View?, parent: ViewGroup): View {
                val row = (v as? LinearLayout) ?: LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(16), dp(6), dp(16), dp(6))
                    addView(ImageView(context), LinearLayout.LayoutParams(dp(40), dp(40)))
                    addView(TextView(context).apply { textSize = 16f; setPadding(dp(14), 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(CheckBox(context).apply { isClickable = false; isFocusable = false })
                }
                val a = shown[p]
                (row.getChildAt(0) as ImageView).setImageDrawable(icons.getOrPut(a.pkg) { packageManager.getApplicationIcon(a.pkg) })
                (row.getChildAt(1) as TextView).text = a.label
                (row.getChildAt(2) as CheckBox).isChecked = a.pkg in checked
                return row
            }
        }
        lv.adapter = adapter
        lv.setOnItemClickListener { parent, _, pos, _ ->
            val a = parent.getItemAtPosition(pos) as? App ?: return@setOnItemClickListener
            if (!checked.remove(a.pkg)) checked.add(a.pkg)
            adapter.notifyDataSetChanged(); updatePreview()
        }

        val bar = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(6), dp(12), dp(10)) }
        status = TextView(this)
        bar.addView(status)
        val row2 = LinearLayout(this)
        row2.addView(Button(this).apply { text = "Создать значки"; setOnClickListener { pinSelected() } }, LinearLayout.LayoutParams(0, -2, 1f))
        row2.addView(Button(this).apply { text = "Обновить"; setOnClickListener { refresh(true) } }, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(row2)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(lv, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(bar)
        setContentView(root)

        modes.setOnCheckedChangeListener { _, _ -> updatePreview() }
        blurBar.setOnSeekBarChangeListener(seek)
        dimBar.setOnSeekBarChangeListener(seek)
        keep.setOnCheckedChangeListener { _, _ -> updatePreview() }
        updatePreview()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        val uri = data?.data ?: return
        if (req != 1 || res != RESULT_OK) return
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        var s = 1
        while (maxOf(o.outWidth, o.outHeight) / s > 1280) s *= 2
        val o2 = BitmapFactory.Options().apply { inSampleSize = s }
        val b = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o2) } ?: return
        FileOutputStream(File(filesDir, "bg.jpg")).use { b.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        bg = b
        modes.check(102)
        updatePreview()
        status.text = "Картинка загружена. Нажмите «Обновить» или «Создать значки»"
    }

    override fun onStart() {
        super.onStart()
        WallpaperManager.getInstance(this).addOnColorsChangedListener(listener, ui)
        autoRefresh()
    }

    override fun onStop() {
        super.onStop()
        WallpaperManager.getInstance(this).removeOnColorsChangedListener(listener)
    }

    private fun wallColor(): Int = try {
        WallpaperManager.getInstance(this).getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
            ?.primaryColor?.toArgb() ?: 0xFF5B6CFF.toInt()
    } catch (e: Exception) { 0xFF5B6CFF.toInt() }

    private fun cfg() = Cfg(wallColor(), keep.isChecked, modes.checkedRadioButtonId - 100, blurBar.progress, dimBar.progress)

    private fun saveCfg(c: Cfg) {
        prefs.edit().putInt("color", c.accent).putBoolean("keep", c.keep).putInt("mode", c.mode)
            .putInt("blur", c.blur).putInt("dim", c.dim).apply()
    }

    private fun updatePreview() {
        val c = cfg()
        previewBox.setBackgroundColor(c.accent)
        val pkg = checked.firstOrNull() ?: all.firstOrNull()?.pkg ?: return
        val d = icons.getOrPut(pkg) { packageManager.getApplicationIcon(pkg) }
        preview.setImageBitmap(makeIcon(d, pkg, c))
    }

    private fun autoRefresh() {
        if (!::keep.isInitialized) return
        updatePreview()
        if (wallColor() != prefs.getInt("color", 0)) refresh(false)
    }

    private fun pinSelected() {
        val sm = getSystemService(ShortcutManager::class.java)
        if (!sm.isRequestPinShortcutSupported) { status.text = "Лаунчер не поддерживает закрепление ярлыков"; return }
        val chosen = all.filter { it.pkg in checked }
        if (chosen.isEmpty()) { status.text = "Выберите приложения"; return }
        val c = cfg(); saveCfg(c)
        val set = HashSet(prefs.getStringSet("pkgs", emptySet())!!)
        set.addAll(chosen.map { it.pkg })
        prefs.edit().putStringSet("pkgs", set).apply()
        chosen.forEachIndexed { i, a ->
            ui.postDelayed({ info(a.pkg, a.label, c)?.let { sm.requestPinShortcut(it, null) } }, i * 1500L)
        }
        status.text = "Подтверждайте добавление в окнах системы: ${chosen.size}"
    }

    private fun refresh(manual: Boolean) {
        val sm = getSystemService(ShortcutManager::class.java)
        val pkgs = prefs.getStringSet("pkgs", emptySet())!!
        val pinned = sm.pinnedShortcuts.map { it.id }.toSet()
        val c = cfg(); val labels = all.associate { it.pkg to it.label }
        val items = pkgs.filter { "wi_$it" in pinned }.mapNotNull { info(it, labels[it] ?: it, c) }
        if (items.isNotEmpty()) sm.updateShortcuts(items)
        saveCfg(c)
        updatePreview()
        if (manual || items.isNotEmpty()) status.text = "Обновлено значков: ${items.size}"
    }

    private fun info(pkg: String, label: String, c: Cfg): ShortcutInfo? {
        val li = packageManager.getLaunchIntentForPackage(pkg) ?: return null
        val bmp = makeIcon(packageManager.getApplicationIcon(pkg), pkg, c)
        return ShortcutInfo.Builder(this, "wi_$pkg")
            .setShortLabel(label)
            .setIcon(Icon.createWithAdaptiveBitmap(bmp))
            .setIntent(li)
            .build()
    }

    private fun drawPicture(cv: Canvas, size: Int, pkg: String, c: Cfg) {
        val b = bg
        if (b == null) { cv.drawColor(0xFF2A2D3A.toInt()); return }
        val side = (minOf(b.width, b.height) * 0.4f).toInt().coerceAtLeast(1)
        val h = pkg.hashCode()
        val x = ((h and 0x7fffffff) % 1000) / 1000f * (b.width - side)
        val y = (((h ushr 8) and 0x7fffffff) % 1000) / 1000f * (b.height - side)
        var t = Bitmap.createBitmap(b, x.toInt(), y.toInt(), side, side)
        if (c.blur > 0) {
            val w = maxOf(4, size / (2 + c.blur / 4))
            t = Bitmap.createScaledBitmap(t, w, w, true)
        }
        cv.drawBitmap(t, null, Rect(0, 0, size, size), Paint(Paint.FILTER_BITMAP_FLAG))
        cv.drawColor(Color.argb(c.dim * 255 / 100, 0, 0, 0))
    }

    private fun makeIcon(d: Drawable, pkg: String, c: Cfg): Bitmap {
        val size = 432
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val hsv = FloatArray(3); Color.colorToHSV(c.accent, hsv)
        val s = hsv[1]
        val fg: Int
        when (c.mode) {
            1 -> {
                cv.drawColor(Color.HSVToColor(floatArrayOf(hsv[0], if (night) minOf(s * 0.6f, 0.7f) else s * 0.3f, if (night) 0.22f else 0.95f)))
                fg = Color.HSVToColor(floatArrayOf(hsv[0], if (night) 0.25f else s.coerceIn(0.3f, 1f), if (night) 0.95f else 0.35f))
            }
            2 -> { drawPicture(cv, size, pkg, c); fg = Color.WHITE }
            else -> fg = if (Color.luminance(c.accent) > 0.55f) 0xFF1B1B1F.toInt() else Color.WHITE
        }
        var g: Drawable = d
        var tint = false
        val adaptive = d is AdaptiveIconDrawable
        if (d is AdaptiveIconDrawable) {
            val mono = if (Build.VERSION.SDK_INT >= 33) d.monochrome else null
            g = if (!c.keep) (mono ?: d.foreground) else d.foreground
            tint = !c.keep
        }
        g = g.mutate()
        if (tint) g.colorFilter = PorterDuffColorFilter(fg, PorterDuff.Mode.SRC_IN)
        if (adaptive) g.setBounds(0, 0, size, size)
        else { val m = (size * 0.19f).toInt(); g.setBounds(m, m, size - m, size - m) }
        g.draw(cv)
        return bmp
    }
}
