# CampusMarket

校园二手交易平台，软件开发课程的个人项目。前端使用 Vue 3，后端使用 Spring Boot，数据存储在 MySQL 中。

项目围绕校园内的二手商品交易展开，包括商品发布和审核、搜索与收藏、购物车、订单支付、线下交付、退货退款，以及相应的卖家和管理员功能。

## 技术栈

- 后端：Java 17、Spring Boot 3.5、MyBatis-Plus、Maven
- 前端：Vue 3、Vue Router、Axios、Vite
- 数据库：MySQL 8
- 身份认证：Session、CSRF Token、角色权限检查

## 主要功能

- **用户端**：注册登录、验证码、商品搜索与筛选、收藏、购物车、钱包及积分、订单与评价。
- **卖家端**：个人卖家或正式商家申请、商品及图片管理、订单交付、退货处理。
- **管理员端**：用户与商家审核、商品审核、商家等级及结算记录管理。
- **交易流程**：按商家拆分订单，支付时预留库存并扣除模拟钱包余额，确认收货后按规则结算托管资金。

钱包、积分和资金托管均为项目内部模拟业务，没有接入真实银行或第三方支付平台。

## 项目结构

```text
CampusMarket/
├─ backend/                  Spring Boot 后端
├─ frontend/                 Vue 前端
├─ database/sql/             建表、迁移及初始化脚本
├─ start-campus-market-db.ps1  数据库模式启动脚本
└─ README.md
```

## 本地运行

以下命令以 **Windows PowerShell** 为例。从项目根目录执行，需提前安装 Java 17、Maven、Node.js、npm 和 MySQL 8。

### 1. 准备数据库

项目使用的本地数据库名为 `2024011324`，历史开发库为 `campus_market_dev`。数据库结构脚本放在 `database/sql/` 中。

如果是第一次部署，需要先创建数据库并按照脚本的适用顺序初始化表结构和数据。部分历史 SQL 面向 `campus_market_dev`，**不要直接将整个目录的 SQL 不加检查地批量执行**。仓库不包含本地数据库内容、用户资料或上传文件。

### 2. 构建并启动后端

```powershell
mvn.cmd -B -f backend/pom.xml clean verify
```

完整交易功能需要使用 `db` profile，默认的 `standalone` 模式主要用于基础健康检查。

商品图片保存在项目目录之外。初次运行时可以先创建一个空目录；已有数据时，应继续使用原有图片目录。

```powershell
$images = Join-Path (Split-Path $PWD.Path -Parent) 'CampusMarket-data\product-images'
New-Item -ItemType Directory -Force -Path $images | Out-Null

$env:DB_SCHEMA = '2024011324'
$env:DB_URL = 'jdbc:mysql://127.0.0.1:3306/2024011324'

powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\start-campus-market-db.ps1 `
  -BackendJar 'backend\target\campus-market-0.1.0-SNAPSHOT.jar' `
  -ProductImagesDir $images
```

脚本会在需要时提示输入 MySQL 用户名、密码及银行账号加密密钥。**不要将这些信息写入仓库**。如果使用已有数据库，必须沿用与历史加密数据相匹配的密钥。

后端默认运行于 `http://127.0.0.1:8080`，可通过 `/api/health` 检查服务是否启动。健康检查通过不代表数据库及全部业务功能已经就绪。

> 注：启动脚本的默认 JAR 名称来自较早的阶段版本。上面的命令通过 `-BackendJar` 明确指定 Maven 构建产物，避免误启动旧版本。

### 3. 启动前端

在另一个 PowerShell 窗口执行：

```powershell
cd frontend
npm.cmd ci
npm.cmd run dev
```

打开 `http://127.0.0.1:5173`。开发环境下，Vite 将 `/api` 请求代理至本机 8080 端口。

## 测试与构建

后端测试：

```powershell
mvn.cmd -B -f backend/pom.xml test
```

前端生产构建：

```powershell
cd frontend
npm.cmd run build
```

## 其他说明

- 订单采用内部模拟钱包扣款和资金托管；当前结算规则是在确认收货满 24 小时、且不存在未完成售后事项时执行结算。
- 商品图片和商家私有资质文件存储在项目外的不同目录，不纳入 Git 版本管理。
- 本仓库保存源代码和数据库脚本，不包含实际运行所需的本地账号、数据和加密密钥。
