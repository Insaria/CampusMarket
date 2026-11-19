# 校园二手商品交易平台

当前只有启动骨架和 HTTP 健康检查，没有业务功能或数据库表。

## 本地启动（PowerShell）

从项目根目录用两个终端启动。需要 Java 17、Maven 3.9.10、Node.js 22.17.0 和 npm 10.9.2；不需要升级 Node 或执行 Vue 脚手架。

```powershell
mvn.cmd -B -f backend/pom.xml clean verify
java -jar backend/target/campus-market-0.1.0-SNAPSHOT.jar
```

```powershell
Set-Location frontend
npm.cmd ci
npm.cmd run build
npm.cmd run dev
```

`package-lock.json` 已生成，`npm.cmd ci` 按锁文件安装依赖。

打开 <http://127.0.0.1:5173>。Vue 使用 Axios 请求 `/api/health`，Vite 在开发模式下将请求代理到 `127.0.0.1:8080`。也可直接访问 <http://127.0.0.1:8080/api/health>，预期返回 `{"status":"UP"}`。该接口只检查 HTTP 服务，不代表 MySQL 已连接。生产构建不包含 Vite 开发代理。

默认使用 `standalone` 配置，不连接 MySQL。后续可在本机设置 `DB_URL`、`DB_USERNAME`、`DB_PASSWORD`，并设置 `SPRING_PROFILES_ACTIVE=db` 启用数据源配置。不要把真实凭据写进仓库或聊天。
