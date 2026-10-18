require('dotenv').config()

const required = (name) => {
  const value = process.env[name]
  if (!value) {
    console.error(`[config] 缺少环境变量 ${name}，请参考 .env.example 配置 .env`)
    process.exit(1)
  }
  return value
}

const optional = (name, fallback = '') => process.env[name] || fallback

module.exports = {
  port: parseInt(process.env.PORT || '3001', 10),
  // 默认只绑回环：对外一律走 Cloudflare Tunnel + nginx 反代
  bindHost: optional('BIND_HOST', '127.0.0.1'),
  jwtSecret: required('JWT_SECRET'),
  jwtExpiresIn: process.env.JWT_EXPIRES_IN || '30d',
  mysql: {
    host: process.env.DB_HOST || '127.0.0.1',
    port: parseInt(process.env.DB_PORT || '3306', 10),
    user: required('DB_USER'),
    password: required('DB_PASSWORD'),
    database: required('DB_NAME')
  }
}
