const path = require('path')
const express = require('express')
const config = require('./config')
const db = require('./db')
const { notFound, errorHandler } = require('./middleware/error')

const app = express()

// Cloudflare Tunnel 与 nginx 在前，req.protocol 要信 X-Forwarded-Proto 才能拼出 https 下载地址
app.set('trust proxy', 1)

app.use((req, res, next) => {
  res.setHeader('X-Service', 'beike')
  next()
})

app.use(express.json({ limit: '1mb' }))

app.get('/health', async (req, res) => {
  try {
    await db.query('SELECT 1')
    res.json({ status: 'ok', service: 'beike', db: true, time: Date.now() })
  } catch (e) {
    console.error('[health] db check failed:', e.message)
    res.status(503).json({ status: 'degraded', service: 'beike', db: false, time: Date.now() })
  }
})

// App 更新包：文件名带版本号，可放心长缓存
app.use('/downloads', express.static(path.join(__dirname, '..', 'public', 'downloads'), { maxAge: '7d' }))

app.use('/api/bs', require('./routes/beike'))

app.use(notFound)
app.use(errorHandler)

app.listen(config.port, config.bindHost, () => {
  console.log(`[beike-server] listening on ${config.bindHost}:${config.port}`)
})
