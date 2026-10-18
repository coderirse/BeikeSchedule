// 教务统一认证会话校验（BeikeSchedule 与 Reps 共用；两边仓库各存一份，改动需同步验证两个 App）。
//
// 自有 App 的「账号 = 学号」必须有真正的身份验证来源：客户端把教务 SESSION cookie 用
// 本模块 RSA 公钥密封成 jwTicket（RSA-OAEP-SHA256 + Base64，见 App 侧 JwSessionTicket.kt），
// 这里解开后拿它去教务系统的 /user/me 核实——能取到 yhdm 说明「请求方确实持有该生的
// 教务会话」。攻击者要冒充某人，必须真的登录该人的教务账号，而不是只知道学号+姓名
// （这两项在班级名单/群文件里总是成对出现）。
//
// 为什么密封而不是明文带 cookie：教务 SESSION 能读写该生全部教务数据（比云 token 更
// 敏感），密封后链路与日志里都只有密文，服务端解开后立即核实并丢弃。
// 私钥文件沿用 bs_ 前缀的历史命名（bs_jw_verify_private.pem），两个模块共用同一对密钥。

const crypto = require('crypto')
const fs = require('fs')
const path = require('path')

const JW_USER_ME_URL = 'https://byyt.ustb.edu.cn/user/me'
const JW_VERIFY_TIMEOUT_MS = 12000
const JW_SESSION_MAX_CHARS = 4096
const JW_TICKET_MAX_CHARS = 1024

const JW_VERIFY_KEY_PATH = path.join(__dirname, '..', 'bs_jw_verify_private.pem')
let jwVerifyKey = null
function getJwVerifyKey() {
  if (jwVerifyKey !== null) return jwVerifyKey
  try {
    jwVerifyKey = crypto.createPrivateKey(fs.readFileSync(JW_VERIFY_KEY_PATH))
  } catch (e) {
    console.warn('[jwSession] 教务会话校验私钥缺失，无法校验 jwTicket：', e.message)
    jwVerifyKey = false
  }
  return jwVerifyKey
}

/** 解开客户端密封的教务 SESSION cookie；密钥缺失/格式错/解不开都返回空串。 */
function unsealJwSession(ticket) {
  const key = getJwVerifyKey()
  if (!key) return ''
  const value = typeof ticket === 'string' ? ticket.trim() : ''
  if (!value || value.length > JW_TICKET_MAX_CHARS) return ''
  try {
    const buf = crypto.privateDecrypt(
      { key, padding: crypto.constants.RSA_PKCS1_OAEP_PADDING, oaepHash: 'sha256' },
      Buffer.from(value, 'base64')
    )
    return buf.toString('utf8').slice(0, JW_SESSION_MAX_CHARS)
  } catch (e) {
    return ''
  }
}

/**
 * 用教务会话 cookie 向教务系统核实身份。
 * 返回 { xh, xm }；会话失效（3xx 跳认证页 / 返回 HTML）返回 null；网络异常抛出。
 */
async function verifyJwSession(cookie) {
  const ctrl = new AbortController()
  const timer = setTimeout(() => ctrl.abort(), JW_VERIFY_TIMEOUT_MS)
  try {
    const resp = await fetch(JW_USER_ME_URL, {
      method: 'POST',
      redirect: 'manual',
      signal: ctrl.signal,
      headers: {
        Cookie: cookie,
        'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8',
        'User-Agent': 'Showwe-Server/1.0'
      },
      body: ''
    })
    if (resp.status >= 300 && resp.status < 400) return null
    if (!resp.ok) throw new Error('教务接口 HTTP ' + resp.status)
    const text = await resp.text()
    let me
    try {
      me = JSON.parse(text)
    } catch (e) {
      return null // 会话过期时返回的是登录页 HTML
    }
    const xh = String(me.yhdm || me.xh || '').trim()
    if (!xh) return null
    return { xh, xm: String(me.xm || '').trim() }
  } finally {
    clearTimeout(timer)
  }
}

module.exports = { unsealJwSession, verifyJwSession }
