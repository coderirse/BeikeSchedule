package com.example.beikeschedule.import

import android.webkit.JavascriptInterface

/** 成绩抓取脚本（assets/import/jw_grades.js）与 Kotlin 的桥接。 */
class GradesBridge(
    private val onResult: (gpaJson: String, gradesJson: String, userJson: String) -> Unit,
    private val onFailure: (String) -> Unit,
) {
    @JavascriptInterface
    fun onGradesResult(gpaJson: String, gradesJson: String, userJson: String) {
        onResult(gpaJson, gradesJson, userJson)
    }

    @JavascriptInterface
    fun onError(message: String) {
        onFailure(message)
    }
}
