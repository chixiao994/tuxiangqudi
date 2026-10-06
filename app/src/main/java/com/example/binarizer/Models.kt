package com.example.binarizer

import android.net.Uri

/**
 * 二值化参数
 * mode: 0 = Otsu 自动, 1 = 固定阈值, 2 = 自适应
 */
data class Params(
    val mode: Int = 0,
    val threshold: Int = 128,
    val invert: Boolean = false
)

/**
 * 单页（单张图片）状态
 */
class PageItem(
    val uri: Uri,
    val name: String,
    var params: Params = Params(),
    var dirty: Boolean = false,     // 是否被修改过（需要保存）
    var skipped: Boolean = false,   // 是否被跳过
    var saved: Boolean = false      // 是否已写出结果
)
