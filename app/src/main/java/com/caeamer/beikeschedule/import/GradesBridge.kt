package com.caeamer.beikeschedule.import

import android.webkit.JavascriptInterface

/**
 * 成绩抓取脚本（assets/import/jw_grades.js）与 Kotlin 的桥接。
 * 一次会话并发抓：当前学期/GPA/成绩单/学籍/考试/学分类别要求/毕业总进度。
 */
class GradesBridge(
    private val onResult: (
        gpaJson: String,
        gradesJson: String,
        userJson: String,
        xsxxJson: String,
        semJson: String,
        examsJson: String,
        xflbyqJson: String,
        bxkqkJson: String,
    ) -> Unit,
    private val onFailure: (String) -> Unit,
) {
    @JavascriptInterface
    fun onGradesResult(
        gpaJson: String,
        gradesJson: String,
        userJson: String,
        xsxxJson: String,
        semJson: String,
        examsJson: String,
        xflbyqJson: String,
        bxkqkJson: String,
    ) {
        onResult(gpaJson, gradesJson, userJson, xsxxJson, semJson, examsJson, xflbyqJson, bxkqkJson)
    }

    @JavascriptInterface
    fun onError(message: String) {
        onFailure(message)
    }
}
