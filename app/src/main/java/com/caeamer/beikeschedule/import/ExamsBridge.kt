package com.caeamer.beikeschedule.import

/** 独立考试脚本仅回传当前学期和考试响应，不触发成绩或课表写入。 */
class ExamsBridge(
    private val onResult: (semester: String, exams: String) -> Unit,
    private val onFailure: (String) -> Unit,
) : JwBridge {
    override fun onError(message: String) = onFailure(message)

    override fun onMessage(fn: String, args: List<String>) {
        if (fn == "onExamsResult") onResult(args.getOrElse(0) { "" }, args.getOrElse(1) { "" })
    }
}
