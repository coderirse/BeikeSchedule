package com.caeamer.beikeschedule.import

/**
 * 云登录身份脚本（assets/import/jw_identity.js）与 Kotlin 的桥接。
 * 参数顺序与脚本的 send('onIdentity', [xh, xm]) 对应。
 */
class CloudLoginBridge(
    private val onIdentity: (xh: String, xm: String) -> Unit,
    private val onFailure: (String) -> Unit,
) : JwBridge {

    override fun onError(message: String) = onFailure(message)

    override fun onMessage(fn: String, args: List<String>) {
        if (fn != FN_ON_IDENTITY) return
        onIdentity(
            args.getOrElse(0) { "" },
            args.getOrElse(1) { "" },
        )
    }

    private companion object {
        const val FN_ON_IDENTITY = "onIdentity"
    }
}
