package com.example.binarizer

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.View
import android.widget.AdapterView
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import com.example.binarizer.databinding.ActivityMainBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val pages = mutableListOf<PageItem>()
    private var index = -1

    private var inputTreeUri: Uri? = null
    private var outputTreeUri: Uri? = null

    private var applying = false
    private var busy = false

    private var renderJob: Job? = null

    /** 最多缓存 6 张原始（缩放后）位图 */
    private val bitmapCache = object : LruCache<String, Bitmap>(6) {
        override fun sizeOf(key: String, value: Bitmap): Int = 1
    }

    private val handler = Handler(Looper.getMainLooper())
    private var pendingRender: Runnable? = null

    // ---------------- 文件夹选择 ----------------

    private val pickInput =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) return@registerForActivityResult
            try {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
            }
            inputTreeUri = uri
            loadInputFolder(uri)
        }

    private val pickOutput =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) return@registerForActivityResult
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) {
            }
            outputTreeUri = uri
            toast("输出文件夹已设置")
            updateInfo()
        }

    // ---------------- 生命周期 ----------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnInput.setOnClickListener { pickInput.launch(inputTreeUri) }
        binding.btnOutput.setOnClickListener { pickOutput.launch(outputTreeUri) }
        binding.btnReset.setOnClickListener { resetCurrent() }
        binding.btnSave.setOnClickListener { saveAll() }

        binding.btnPrev.setOnClickListener { goTo(index - 1) }
        binding.btnSkip.setOnClickListener { skip() }
        binding.btnNext.setOnClickListener { goTo(index + 1) }

        binding.spinnerMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?, view: View?, position: Int, id: Long
            ) {
                paramsChanged(false)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        binding.seekThreshold.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                binding.textThreshold.text = progress.toString()
                paramsChanged(true)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                paramsChanged(false)
            }
        })

        binding.switchInvert.setOnCheckedChangeListener { _, _ -> paramsChanged(false) }

        updateInfo()
    }

    override fun onDestroy() {
        super.onDestroy()
        pendingRender?.let { handler.removeCallbacks(it) }
        renderJob?.cancel()
    }

    // ---------------- 输入文件夹 ----------------

    private fun loadInputFolder(uri: Uri) {
        lifecycleScope.launch {
            binding.progress.visibility = View.VISIBLE
            val items = withContext(Dispatchers.IO) {
                try {
                    val root = DocumentFile.fromTreeUri(this@MainActivity, uri)
                    root?.listFiles()
                        ?.filter { it.isFile && (it.type ?: "").startsWith("image/") }
                        ?.sortedBy { it.name ?: "" }
                        ?: emptyList()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    e.printStackTrace()
                    emptyList()
                }
            }
            binding.progress.visibility = View.GONE

            renderJob?.cancel()
            bitmapCache.evictAll()
            pages.clear()
            index = -1
            binding.imagePreview.setImageDrawable(null)

            for (f in items) {
                pages.add(PageItem(f.uri, f.name ?: "image"))
            }

            if (pages.isEmpty()) {
                binding.textInfo.text = "输入文件夹中没有图片"
                toast("未找到图片文件")
            } else {
                showPage(0)
                toast("共加载 ${pages.size} 张图片")
            }
        }
    }

    // ---------------- 页面切换 ----------------

    private fun showPage(i: Int) {
        if (i < 0 || i >= pages.size) return
        index = i
        val page = pages[i]

        applying = true
        binding.spinnerMode.setSelection(page.params.mode.coerceIn(0, 2))
        binding.seekThreshold.progress = page.params.threshold.coerceIn(0, 255)
        binding.textThreshold.text = page.params.threshold.toString()
        binding.switchInvert.isChecked = page.params.invert
        applying = false

        updateInfo()
        renderCurrent()
    }

    private fun goTo(target: Int) {
        if (busy) return
        if (pages.isEmpty()) {
            toast("请先选择输入文件夹")
            return
        }
        if (target < 0) {
            toast("已经是第一页")
            return
        }
        if (target >= pages.size) {
            toast("已经是最后一页")
            return
        }

        val cur = pages.getOrNull(index)
        if (cur != null && cur.dirty) {
            if (outputTreeUri == null) {
                toast("请先选择输出文件夹")
                showPage(target)
                return
            }
            busy = true
            lifecycleScope.launch {
                binding.progress.visibility = View.VISIBLE
                val ok = withContext(Dispatchers.IO) {
                    try {
                        savePage(cur)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        e.printStackTrace()
                        false
                    }
                }
                binding.progress.visibility = View.GONE
                if (ok) cur.dirty = false else toast("保存失败：${cur.name}")
                busy = false
                showPage(target)
            }
        } else {
            showPage(target)
        }
    }

    private fun skip() {
        if (busy) return
        val cur = pages.getOrNull(index) ?: return
        cur.skipped = true
        cur.dirty = false
        if (index >= pages.size - 1) {
            toast("已经是最后一页")
            updateInfo()
        } else {
            showPage(index + 1)
        }
    }

    // ---------------- 参数 ----------------

    private fun paramsChanged(debounce: Boolean) {
        if (applying) return
        val page = pages.getOrNull(index) ?: return

        page.params = Params(
            mode = binding.spinnerMode.selectedItemPosition.coerceIn(0, 2),
            threshold = binding.seekThreshold.progress.coerceIn(0, 255),
            invert = binding.switchInvert.isChecked
        )
        page.dirty = true
        updateInfo()

        pendingRender?.let { handler.removeCallbacks(it) }
        val r = Runnable { renderCurrent() }
        pendingRender = r
        handler.postDelayed(r, if (debounce) 250L else 0L)
    }

    private fun resetCurrent() {
        if (busy) return
        val page = pages.getOrNull(index) ?: return

        page.params = Params()
        page.dirty = false
        page.skipped = false

        applying = true
        binding.spinnerMode.setSelection(0)
        binding.seekThreshold.progress = Params().threshold
        binding.textThreshold.text = Params().threshold.toString()
        binding.switchInvert.isChecked = false
        applying = false

        updateInfo()
        renderCurrent()
        toast("已重置当前页")
    }

    // ---------------- 渲染 ----------------

    private fun renderCurrent() {
        val page = pages.getOrNull(index) ?: return
        renderJob?.cancel()
        renderJob = lifecycleScope.launch {
            binding.progress.visibility = View.VISIBLE
            val result = withContext(Dispatchers.IO) {
                try {
                    val src = getBitmap(page) ?: return@withContext null
                    ImageProcessor.binarize(src, page.params)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    e.printStackTrace()
                    null
                }
            }
            binding.progress.visibility = View.GONE
            if (result != null) {
                binding.imagePreview.setImageBitmap(result)
            }
        }
    }

    private fun getBitmap(page: PageItem): Bitmap? {
        val key = page.uri.toString()
        bitmapCache.get(key)?.let { return it }
        val bmp = decodeSampled(page.uri) ?: return null
        bitmapCache.put(key, bmp)
        return bmp
    }

    private fun decodeSampled(uri: Uri): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            val maxDim = 1600
            var sample = 1
            while (bounds.outWidth / sample > maxDim || bounds.outHeight / sample > maxDim) {
                sample *= 2
            }

            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // ---------------- 保存 ----------------

    private fun savePage(page: PageItem): Boolean {
        val tree = outputTreeUri ?: return false
        return try {
            val src = getBitmap(page) ?: return false
            val out = ImageProcessor.binarize(src, page.params)
            val dir = DocumentFile.fromTreeUri(this, tree) ?: return false

            val baseName = page.name.substringBeforeLast('.', page.name)
            val fileName = "$baseName.png"

            dir.findFile(fileName)?.delete()
            val file = dir.createFile("image/png", fileName) ?: return false

            contentResolver.openOutputStream(file.uri)?.use { os ->
                out.compress(Bitmap.CompressFormat.PNG, 100, os)
                os.flush()
            }
            page.saved = true
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun saveAll() {
        if (busy) return
        if (pages.isEmpty()) {
            toast("请先选择输入文件夹")
            return
        }
        if (outputTreeUri == null) {
            toast("请先选择输出文件夹")
            return
        }

        busy = true
        binding.progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            var ok = 0
            var fail = 0
            withContext(Dispatchers.IO) {
                for (p in pages) {
                    if (p.skipped) continue
                    if (savePage(p)) {
                        p.dirty = false
                        p.saved = true
                        ok++
                    } else {
                        fail++
                    }
                }
            }
            binding.progress.visibility = View.GONE
            busy = false
            updateInfo()
            toast(
                "保存完成：成功 $ok 张" + if (fail > 0) "，失败 $fail 张" else ""
            )
        }
    }

    // ---------------- 信息栏 ----------------

    private fun updateInfo() {
        val sb = StringBuilder()
        if (index in pages.indices) {
            val p = pages[index]
            val st = when {
                p.skipped -> "跳过"
                p.dirty -> "已修改"
                p.saved -> "已保存"
                else -> "未改"
            }
            sb.append("${index + 1}/${pages.size}  ${p.name}  [$st]")
        } else {
            sb.append("未选择输入文件夹")
        }
        if (outputTreeUri == null) {
            sb.append("  (未设置输出)")
        }
        binding.textInfo.text = sb.toString()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
