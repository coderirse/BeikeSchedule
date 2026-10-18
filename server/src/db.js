const mysql = require('mysql2/promise')
const config = require('./config')

const pool = mysql.createPool({
  ...config.mysql,
  waitForConnections: true,
  connectionLimit: 10,
  charset: 'utf8mb4',
  dateStrings: false
})

module.exports = pool
