# BeikeSchedule 服务端

贝壳课表的服务端，独立进程、独立部署，提供 `/api/bs/*`（账号 / 云备份 / 班级实验安排 / 应用内更新）与 `/downloads/beike/*`。

线上链路：

```
api.caeamer.com -> Cloudflare Tunnel -> nginx 127.0.0.1:3005
  /api/bs/*, /downloads/beike/* -> 127.0.0.1:3001 (beike.service, /opt/beike)
```

与社区、Reps 共用同一个 MySQL 库与同一个 `JWT_SECRET`，但只访问 `bs_*` 表；token 通过 `scope: 'bs'` 与社区隔离。

## 目录

| 路径 | 作用 |
|---|---|
| `src/routes/beike.js` | 全部 `/api/bs` 接口 |
| `src/services/jwSession.js` | 教务 SESSION 解密与 `/user/me` 回查（与 Reps 各存一份，改动需两边同步） |
| `src/bs-app-version.json` | 应用内更新声明（启动时载入，改完需重启） |
| `src/bs-class-schedules.json` | 班级实验/上机安排（按 mtime 热加载） |
| `src/migrations/2026-09-21-beike-backup.sql` | `bs_users` / `bs_backup` 建表（已有库执行一次） |
| `public/downloads/beike/` | 历史 APK（不入库，部署机维护） |
| `src/bs_*_private.pem` | 服务端私钥（不入库，仅部署机） |

## 本地开发

```bash
cd server
npm install
cp .env.example .env   # 填 JWT_SECRET / MySQL / BS_JW_SESSION_REQUIRED
npm run dev            # 默认 127.0.0.1:3001
```

## 部署

部署机需与 showwe 相同的 workbench CLI 配置。

```bash
bash server/scripts/deploy.sh
```

- 打包 `src + package.json + node_modules`，解压到 `/opt/beike`；`.env`、`public/downloads` 为服务器持久状态，不会被覆盖。
- 重启 `beike.service`，检查 `http://127.0.0.1:3001/health`。
- 首次部署需在服务器上准备好 `.env`、私钥与 APK 目录（见 `deploy/beike.service` 与仓库交接文档）。

## 更新 APK

把新 APK 放到 `/opt/beike/public/downloads/beike/`，更新 `src/bs-app-version.json`（含 `apkSha256` 与 `sig`），再执行一次部署或只替换该 JSON 并重启服务。
