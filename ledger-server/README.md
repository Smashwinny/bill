# 本地账本云同步服务

个人账本的幂等事件同步服务。手机始终先写本地；服务端不可用不会阻塞记账。

## 阿里云部署

1. 为 `ledger.geniusqi.com` 配置 DNS，指向阿里云服务器或现有 Cloudflare Tunnel。
2. 在 `ledger-server/deploy/.env` 写入随机同步密钥（不要提交）：
   `LEDGER_SYNC_TOKEN=<至少20位随机字符>`
3. 运行 `docker compose -f ledger-server/deploy/docker-compose.yml up -d --build`。
4. 使用示例 Nginx 配置终止 HTTPS；服务本身只绑定 `127.0.0.1:8790`。
5. 验证 `https://ledger.geniusqi.com/healthz`，然后在 App“设置”页填入同一密钥。

SQLite 使用 WAL 和原子事务，`event_id` 唯一约束保证重试不会重复；修改和删除按服务端事件顺序同步。每天首次写入前自动生成 SQLite 在线备份并保留最近 30 份。阿里云数据盘应同时开启磁盘加密和快照策略。
