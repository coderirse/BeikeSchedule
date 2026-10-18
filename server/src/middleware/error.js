function asyncHandler(fn) {
  return (req, res, next) => Promise.resolve(fn(req, res, next)).catch(next)
}

function notFound(req, res) {
  res.status(404).json({ message: '接口不存在' })
}

function errorHandler(err, req, res, next) {
  console.error(`[error] ${req.method} ${req.path}:`, err)
  if (res.headersSent) return next(err)
  const status = err.status || 500
  res.status(status).json({ message: err.expose ? err.message : (status === 500 ? '服务器内部错误' : err.message) })
}

class ApiError extends Error {
  constructor(status, message) {
    super(message)
    this.status = status
    this.expose = true
  }
}

module.exports = { asyncHandler, notFound, errorHandler, ApiError }
