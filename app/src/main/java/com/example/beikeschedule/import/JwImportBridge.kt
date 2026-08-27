package com.example.beikeschedule.import

import android.webkit.JavascriptInterface

/** WebView 注入脚本与 Kotlin 的桥接。方法签名与 assets/import/jw_import.js 对应。 */
class JwImportBridge(
    private val onSuccess: (semester: String, published: String, courses: String, sections: String, weekDates: String) -> Unit,
    private val onFailure: (String) -> Unit,
) {
    @JavascriptInterface
    fun onResult(semester: String, published: String, courses: String, sections: String, weekDates: String) {
        onSuccess(semester, published, courses, sections, weekDates)
    }

    @JavascriptInterface
    fun onError(message: String) {
        onFailure(message)
    }
}
