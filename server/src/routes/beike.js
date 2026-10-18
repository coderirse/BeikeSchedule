// 贝壳课表（BeikeSchedule）独立模块：独立 Node 进程（beike.service），与社区、Reps 共用 MySQL
// 但账号/数据完全隔离；对外路径保持 /api/bs/*，由 nginx 分流器按前缀转给本服务。
//
// 账号模型 = 学号即账号、无密码：客户端内嵌教务 WebView 完成统一身份认证
// （扫码登录或账密均可），登录成功后由客户端回传学号换 token。
//
// **身份验证来源**：客户端把教务统一认证得到的 SESSION cookie 用本模块 RSA 公钥密封
// （OAEP-SHA256，见 bs_jw_verify_private.pem 配对的公钥）后随登录请求带来，服务端解开
// 并**直接向教务系统 /user/me 核实**，学号必须与请求一致（见 verifyJwSession）。
// 旧的"只校验学号+姓名"挡不住任何拿到班级名单的人（两者在名单/群文件里总是成对出现），
// 因此只作为未升级客户端的过渡路径，且**新账号一律要求票据**；BS_JW_SESSION_REQUIRED=1
// 可让所有登录都强制校验通过。
// 防滥用另有：学号/IP/写库三层限流 + 不返回学号是否存在之外的档案信息（快照必须持 token）。
//
// 与社区 token 的隔离：社区 JWT 载荷是 { uid, ver }，本模块是 { scope: 'bs', xh, ver }。
// 社区 middleware/auth 按 uid 查 users 表，本模块 token 没有 uid，拿去调社区接口必然 401；
// 反之社区 token 没有 scope === 'bs'，也进不了本模块接口。

const express = require('express')
const crypto = require('crypto')
const fs = require('fs')
const path = require('path')
const jwt = require('jsonwebtoken')
const config = require('../config')
const db = require('../db')
const { asyncHandler, ApiError } = require('../middleware/error')
const { unsealJwSession, verifyJwSession } = require('../services/jwSession')

const router = express.Router()

// 整包快照含全部成绩与课表，正常在几十 KB 量级；app 级 express.json 限 1mb，
// 这里再给一个更友好的业务上限，超限报明确错误而不是裸 413。
const SNAPSHOT_MAX_BYTES = 900 * 1024

const XH_RE = /^[A-Za-z0-9]{4,20}$/
const NAME_MAX = 64

// 登录限流（单实例内存 Map，与 auth.js 的登录限流同一套思路）：按学号计数，
// 防止拿到的学号列表被脚本枚举轰炸，也压住"伪造学号刷库"的量级。
const LOGIN_MAX = 20
const LOGIN_WINDOW = 15 * 60 * 1000
const loginAttempts = new Map() // xh -> { count, firstAt }
const loginByIp = new Map() // ip -> { count, firstAt }
const backupWrites = new Map() // xh -> { count, firstAt }（覆盖/删除是破坏性操作，单独限流）

function checkLoginRate(xh) {
  const record = loginAttempts.get(xh)
  if (!record) return
  if (Date.now() - record.firstAt > LOGIN_WINDOW) {
    loginAttempts.delete(xh)
    return
  }
  if (record.count >= LOGIN_MAX) {
    throw new ApiError(429, '操作过于频繁，请15分钟后再试')
  }
}

function recordLoginAttempt(xh) {
  const record = loginAttempts.get(xh)
  if (!record || Date.now() - record.firstAt > LOGIN_WINDOW) {
    loginAttempts.set(xh, { count: 1, firstAt: Date.now() })
    return
  }
  record.count += 1
}

function checkIpRate(map, max, label) {
  return (req, res, next) => {
    const key = req.ip || req.socket?.remoteAddress || 'unknown'
    const record = map.get(key)
    if (record && Date.now() - record.firstAt <= LOGIN_WINDOW && record.count >= max) {
      return next(new ApiError(429, `${label}过于频繁，请15分钟后再试`))
    }
    if (!record || Date.now() - record.firstAt > LOGIN_WINDOW) {
      map.set(key, { count: 1, firstAt: Date.now() })
    } else {
      record.count += 1
    }
    next()
  }
}

function checkBackupWriteRate(req, res, next) {
  try {
    const xh = req.bsUser.xh
    const record = backupWrites.get(xh)
    if (record && Date.now() - record.firstAt <= LOGIN_WINDOW && record.count >= 30) {
      throw new ApiError(429, '操作过于频繁，请15分钟后再试')
    }
    if (!record || Date.now() - record.firstAt > LOGIN_WINDOW) {
      backupWrites.set(xh, { count: 1, firstAt: Date.now() })
    } else {
      record.count += 1
    }
    next()
  } catch (err) {
    next(err)
  }
}

function validateXh(xh) {
  const value = typeof xh === 'string' ? xh.trim() : ''
  if (!XH_RE.test(value)) throw new ApiError(400, '学号格式不正确')
  return value
}

function signBsToken(xh, tokenVersion) {
  return jwt.sign({ scope: 'bs', xh, ver: Number(tokenVersion) || 0 }, config.jwtSecret, {
    expiresIn: config.jwtExpiresIn
  })
}

/** 贝壳课表专用鉴权：要求 scope === 'bs'，并校验 token_version。 */
async function bsAuth(req, res, next) {
  try {
    const header = req.headers.authorization || ''
    const token = header.startsWith('Bearer ') ? header.slice(7) : ''
    if (!token) throw new ApiError(401, '请先登录')
    let payload
    try {
      payload = jwt.verify(token, config.jwtSecret)
    } catch (e) {
      throw new ApiError(401, '登录已过期，请重新登录')
    }
    if (payload.scope !== 'bs' || !payload.xh) {
      throw new ApiError(401, '登录已过期，请重新登录')
    }
    const [rows] = await db.query('SELECT token_version AS tokenVersion FROM bs_users WHERE xh = ?', [payload.xh])
    if (rows.length === 0) throw new ApiError(401, '账号不存在，请重新登录')
    if ((Number(payload.ver) || 0) !== (Number(rows[0].tokenVersion) || 0)) {
      throw new ApiError(401, '登录已过期，请重新登录')
    }
    req.bsUser = { xh: payload.xh }
    next()
  } catch (err) {
    next(err)
  }
}

// ---------- 备份完整性签名（防明文 HTTP 上的快照投毒，见客户端 R4 审查 R3） ----------
//
// GET /backup 返回的快照本体由服务端 Ed25519 签名（私钥 bs_backup_signing_private.pem，
// 已 gitignore；公钥编译进 App）。客户端恢复前先验签，篡改/损坏的快照直接拒绝——
// 私钥缺失时返回 sig=''，客户端会明确报"备份校验失败"而不是静默接受未签名数据。
const BACKUP_KEY_PATH = path.join(__dirname, '..', 'bs_backup_signing_private.pem')
let backupPrivateKey = null
function getBackupPrivateKey() {
  if (backupPrivateKey !== null) return backupPrivateKey
  try {
    backupPrivateKey = crypto.createPrivateKey(fs.readFileSync(BACKUP_KEY_PATH))
  } catch (e) {
    console.warn('[beike] 备份签名私钥缺失，GET /backup 将不带 sig：', e.message)
    backupPrivateKey = false
  }
  return backupPrivateKey
}
function signBackupSnapshot(text) {
  const key = getBackupPrivateKey()
  if (!key) return ''
  return crypto.sign(null, Buffer.from(text, 'utf8'), key).toString('base64')
}

// ---------- 教务会话校验（身份验证的真正来源） ----------
//
// 密文解开与「回查教务 /user/me 核对学号」的实现见 services/jwSession.js
// （Reps 模块复用同一个模块与同一对 RSA 密钥，见 App 侧 JwSessionTicket.kt）。

// 灰度开关：1 = 所有登录都必须通过教务会话校验（老客户端会被拦下并提示升级）；
// 默认 0 = 未升级客户端仍可走姓名校验，但新账号必须有票据。
const JW_REQUIRE_SESSION = process.env.BS_JW_SESSION_REQUIRED === '1'
console.log('[beike] 教务会话强制校验 BS_JW_SESSION_REQUIRED =', JW_REQUIRE_SESSION)

// ---------- 登录（学号即账号，登录即注册；教务会话校验 + 姓名校验 + 限流） ----------

router.post('/auth/login', checkIpRate(loginByIp, 60, '登录'), asyncHandler(async (req, res) => {
  const xh = validateXh((req.body || {}).xh)
  let name = String((req.body || {}).xm || '').trim().slice(0, NAME_MAX)
  checkLoginRate(xh)
  recordLoginAttempt(xh)

  const [existing] = await db.query('SELECT name FROM bs_users WHERE xh = ?', [xh])
  const registered = existing.length > 0 && !!existing[0].name

  // ① 教务会话校验（能过就以教务系统返回的姓名为准）
  const ticket = (req.body || {}).jwTicket
  const session = unsealJwSession(ticket)
  let verified = null
  let verifyNote = ''
  if (session) {
    try {
      verified = await verifyJwSession(session)
      if (!verified) verifyNote = '教务会话已失效'
    } catch (e) {
      verifyNote = e.message || '教务系统不可用'
    }
  } else {
    verifyNote = ticket ? '票据无法解开（密钥不匹配或格式错误）' : '未携带教务会话票据'
  }

  if (verified) {
    if (verified.xh.toUpperCase() !== xh.toUpperCase()) {
      throw new ApiError(401, '教务会话与学号不一致，请确认已登录本人教务账号')
    }
    if (verified.xm) name = verified.xm.slice(0, NAME_MAX)
  } else if (JW_REQUIRE_SESSION || !registered) {
    // 强制校验模式，或该学号还没注册过：没有教务会话票据就不发 token。
    // （否则任何人都能用名单上的学号+姓名抢注/接管别人的云账号）
    throw new ApiError(401, '登录校验未通过：请在教务系统登录后重试，若仍失败请更新 App 到最新版')
  } else {
    // 过渡路径：老客户端（≤1.3.6）不带票据。仅在账号已注册时按姓名放行，并留下告警日志。
    console.warn('[beike] 教务会话校验未通过，降级为姓名校验 xh=%s reason=%s', xh, verifyNote)
    if (!name || name !== existing[0].name) {
      throw new ApiError(401, '学号或姓名不匹配，请确认已登录本人教务账号')
    }
  }

  if (name.length < 2) {
    throw new ApiError(400, '缺少姓名信息，请在教务系统登录后重试')
  }

  const now = Date.now()
  await db.query(
    `INSERT INTO bs_users (xh, name, created_at, last_login_at)
     VALUES (?, ?, ?, ?)
     ON DUPLICATE KEY UPDATE last_login_at = VALUES(last_login_at)${name ? ', name = VALUES(name)' : ''}`,
    [xh, name, now, now]
  )
  const [rows] = await db.query('SELECT token_version AS tokenVersion FROM bs_users WHERE xh = ?', [xh])
  console.log('[beike] login xh=%s jw=%s', xh, verified ? 'verified' : 'legacy')
  res.json({ token: signBsToken(xh, rows[0].tokenVersion), xh, name })
}))

// ---------- 云备份（整包快照） ----------

router.get('/backup', bsAuth, asyncHandler(async (req, res) => {
  const [rows] = await db.query(
    'SELECT data, size_bytes AS sizeBytes, app_version_code AS appVersionCode, updated_at AS updatedAt FROM bs_backup WHERE xh = ?',
    [req.bsUser.xh]
  )
  if (rows.length === 0) {
    return res.json({ exists: false, data: null, updatedAt: 0 })
  }
  // data 原样下发（兼容旧客户端），snapshot 是签名所覆盖的**原始字节串**：客户端验签后
  // 直接解析 snapshot，避免"重新序列化对象"与签名字节不一致的问题
  res.json({
    exists: true,
    data: JSON.parse(rows[0].data),
    snapshot: rows[0].data,
    sig: signBackupSnapshot(rows[0].data),
    sizeBytes: rows[0].sizeBytes,
    appVersionCode: rows[0].appVersionCode,
    updatedAt: rows[0].updatedAt
  })
}))

router.put('/backup', bsAuth, checkBackupWriteRate, asyncHandler(async (req, res) => {
  const body = req.body || {}
  if (typeof body.data !== 'object' || body.data === null || Array.isArray(body.data)) {
    throw new ApiError(400, '快照格式不正确')
  }
  const text = JSON.stringify(body.data)
  const size = Buffer.byteLength(text, 'utf8')
  if (size > SNAPSHOT_MAX_BYTES) {
    throw new ApiError(413, '备份数据过大')
  }
  const versionCode = Number(body.appVersionCode) || 0
  const now = Date.now()
  await db.query(
    `INSERT INTO bs_backup (xh, data, size_bytes, app_version_code, updated_at)
     VALUES (?, ?, ?, ?, ?)
     ON DUPLICATE KEY UPDATE data = VALUES(data), size_bytes = VALUES(size_bytes),
       app_version_code = VALUES(app_version_code), updated_at = VALUES(updated_at)`,
    [req.bsUser.xh, text, size, versionCode, now]
  )
  res.json({ updatedAt: now, sizeBytes: size })
}))

// 删除云端备份（用户在客户端显式要求时调用）
router.delete('/backup', bsAuth, checkBackupWriteRate, asyncHandler(async (req, res) => {
  await db.query('DELETE FROM bs_backup WHERE xh = ?', [req.bsUser.xh])
  res.json({ deleted: true })
}))

// ---------- 班级实验/上机安排（教务系统拿不到、任课老师在群里通知的数据） ----------
//
// 数据在 bs-class-schedules.json：按「学年-学期」→「班级」组织，班级键与客户端上报的
// 学籍班级名（App「我的」页显示的那个，如"机243"）做**包含匹配**——教务的 bjmc 可能是
// "…机243班"这类带前缀的写法，配置里只写班级键本身即可。
// 命中→该班场次 + 需要自动隐藏的无固定时间课程名片段；未命中/该学期没配置→空列表
// （客户端据此不下发任何数据，其他班同学看不到本班安排）。改 JSON 后跑 scripts/deploy.sh
// 重启进程即生效，客户端不需要发版。

const CLASS_SCHEDULE_PATH = path.join(__dirname, '..', 'bs-class-schedules.json')
let classSchedules = null
function loadClassSchedules() {
  if (!classSchedules) {
    classSchedules = JSON.parse(fs.readFileSync(CLASS_SCHEDULE_PATH, 'utf8'))
  }
  return classSchedules
}

router.get('/class-schedule', bsAuth, (req, res) => {
  const xn = String(req.query.xn || '').trim()
  const xq = String(req.query.xq || '').trim()
  const cls = String(req.query.cls || '').replace(/\s+/g, '')
  const empty = { xn, xq, class: '', sessions: [], hideUnscheduledContaining: [] }
  if (!xn || !xq || !cls) return res.json(empty)
  const semester = (loadClassSchedules().semesters || {})[`${xn}-${xq}`]
  if (!semester) return res.json(empty)
  const hit = Object.entries(semester).find(([className]) =>
    cls.includes(String(className).replace(/\s+/g, ''))
  )
  if (!hit) {
    console.log('[beike] class-schedule miss xh=%s cls=%s %s-%s', req.bsUser.xh, cls, xn, xq)
    return res.json(empty)
  }
  const entry = hit[1] || {}
  console.log('[beike] class-schedule hit xh=%s cls=%s sessions=%d', req.bsUser.xh, hit[0], (entry.sessions || []).length)
  res.json({
    xn,
    xq,
    class: hit[0],
    sessions: entry.sessions || [],
    hideUnscheduledContaining: entry.hideUnscheduledContaining || []
  })
})

// ---------- 应用内更新（公开接口，结构对齐 routes/app.js） ----------

// require 在启动时读入，deploy.sh 重启进程后生效——与 routes/app.js 同一套约定
let version = null
function loadVersion() {
  if (!version) {
    const file = path.join(__dirname, '..', 'bs-app-version.json')
    version = JSON.parse(fs.readFileSync(file, 'utf8'))
  }
  return version
}

router.get('/app/latest', (req, res) => {
  const v = loadVersion()
  const absolute = /^https?:\/\//.test(v.url)
  res.json({
    versionCode: v.versionCode,
    versionName: v.versionName,
    changelog: v.changelog || '',
    force: !!v.force,
    size: v.size || 0,
    url: absolute ? v.url : `${req.protocol}://${req.get('host')}${v.url}`,
    // Ed25519 元数据 + APK 摘要签名（tools/sign_update.py 生成 bs-app-version.json 时写入）
    sig: v.sig || '',
    apkSha256: v.apkSha256 || ''
  })
})

module.exports = router
