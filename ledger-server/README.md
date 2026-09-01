# 本地账本云同步服务

个人账本的幂等事件同步服务。手机始终先写本地；服务端不可用不会阻塞记账。

## 阿里云部署

1. 创建仅连接阿里云的专用 Cloudflare Tunnel，将 `bill.geniusqi.com` 路由到 `http://127.0.0.1:8790`。
2. 在 `ledger-server/deploy/.env` 写入随机同步密钥（不要提交）：
   `LEDGER_SYNC_TOKEN=<至少20位随机字符>`
3. 运行 `docker compose -f ledger-server/deploy/docker-compose.yml up -d --build`。
4. Tunnel 直接回源本机 `8790`；若改用公网 HTTPS，再使用示例 Nginx 配置终止 TLS。
5. 验证 `https://bill.geniusqi.com/healthz`，然后在 App“设置”页填入同一密钥。

`deploy/ledger-tunnel.service.example` 可作为 systemd 模板。Tunnel 令牌应单独保存在
`/etc/cloudflared/ledger-token`，仅允许运行 cloudflared 的低权限用户读取；不要与应用同步密钥混用或提交到 Git。

SQLite 使用 WAL 和原子事务，`event_id` 唯一约束保证重试不会重复；修改和删除按服务端事件顺序同步。每天首次写入前自动生成 SQLite 在线备份并保留最近 30 份。阿里云数据盘应同时开启磁盘加密和快照策略。
