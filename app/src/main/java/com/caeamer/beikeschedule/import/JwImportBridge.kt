package com.caeamer.beikeschedule.import

/** 导入脚本（assets/import/jw_import.js）与 Kotlin 的桥接。参数顺序与脚本的 send('onResult', [...]) 对应。 */
class JwImportBridge(
    private val onSuccess: (
        semester: String,
        published: String,
        courses: String,
        sections: String,
        weekDates: String,
        calendar: String,
    ) -> Unit,
    private val onFailure: (String) -> Unit,
) : JwBridge {

    override fun onError(message: String) = onFailure(message)

    override fun onMessage(fn: String, args: List<String>) {
        if (fn != FN_ON_RESULT) return
        onSuccess(
            args.getOrElse(0) { "" },
            args.getOrElse(1) { "" },
            args.getOrElse(2) { "" },
            args.getOrElse(3) { "" },
            args.getOrElse(4) { "" },
            args.getOrElse(5) { "" },
        )
    }

    private companion object {
        const val FN_ON_RESULT = "onResult"
    }
}
