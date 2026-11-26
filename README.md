# 校园二手商品交易平台

校园二手交易平台包含 Vue 前端、Spring Boot 后端及 MySQL。当前主库为 `2024011324`，用于后续测试、开发和实验报告。`campus_market_dev` 保留为迁移前备份与故障回退环境。阶段 5.9 的历史结果见 [验收清单](docs/acceptance-5.9.md)。

## 本地启动（PowerShell）

从项目根目录用两个终端启动。需要 Java 17、Maven 3.9.10、Node.js 22.17.0 和 npm 10.9.2；不需要升级 Node 或执行 Vue 脚手架。

```powershell
mvn.cmd -B -f backend/pom.xml clean verify
java -jar backend/target/campus-market-0.1.0-SNAPSHOT.jar
```

```powershell
Set-Location frontend
$env:CAMPUS_MARKET_VUE_ROUTER_DIR = 'D:\BISTU\交互式\shiyan2\vite-todo\node_modules\vue-router'
npm.cmd run build
npm.cmd run dev
```

阶段 5.1 复用了本机另一项目中已安装的 Vue Router 4.6.3，项目的依赖声明和锁文件保持原样。上述环境变量只对当前 PowerShell 进程生效。换机器时，将路径改为该机器上已安装且兼容 Vue 3.5 的 Vue Router 包目录。登录、注册和身份恢复需要同时运行下面的 `db` 模式后端。

打开 <http://127.0.0.1:5173>。Vite 在开发模式下将 `/api` 请求代理到 `127.0.0.1:8080`。可直接访问 <http://127.0.0.1:8080/api/health>，预期返回 `{"status":"UP"}`。该接口只检查 HTTP 服务，不代表 MySQL 已连接。生产构建不包含 Vite 开发代理。

默认 `standalone` 用于健康检查。完整功能需要 `db` 模式。已验证学号库复制，日常启动直接使用阶段 6.1 JAR 和现有配置。仅在后端未运行时从项目根目录启动。

```powershell
$env:DB_SCHEMA = '2024011324'
$env:DB_URL = 'jdbc:mysql://127.0.0.1:3306/2024011324'
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\start-campus-market-db.ps1
```

凭据只在本机按提示输入。沿用原有 32 字节 Base64 银行账号加密密钥，以及 `D:\BISTU\软件开发\CampusMarket-data\product-images` 和原私有资质目录。`db` 模式禁用自动 DDL。启动应出现 `Database startup check passed: schema=2024011324, readable tables=users, products`。历史建表、迁移和种子 SQL 保留供追溯，当前学号库已具备对应结构和数据。

需要回退时，先评估学号库切换后新增的数据，再由操作者在单实例维护窗口将 `DB_SCHEMA` 改为 `campus_market_dev`，将 `DB_URL` 改为 `jdbc:mysql://127.0.0.1:3306/campus_market_dev`，用同一脚本启动。回退使用原凭据、密钥和存储路径。

## 开发进度

阶段 7.1 已改造商城首页、目录和全局视觉规范，前端构建与本机真实商品浏览通过。修改文件、验证范围和桌面及手机截图见[阶段 7.1 记录](docs/stage7-1-frontend.md)。

阶段 6.1 已完成学号库迁移并通过启动检查。当前运行包为 `campus-market-stage61.jar`，包含 `POST /api/cart/checkout-and-pay` 原子下单扣款。旧结账与独立支付接口继续兼容历史待支付订单。迁移证据见[阶段 6.1 一致性修正](docs/stage6-1-conformance.md)，正式库实测结果和接续顺序见[阶段 6.2 验收增量记录](docs/stage6-system-acceptance.md#阶段-62-真实业务验收增量记录)。

### 商品图片上传的本机启动方式

使用最终候选 JAR 时，可在项目根目录执行 `powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\start-campus-market-db.ps1`。启动脚本默认把 `PRODUCT_IMAGES_DIR` 指向项目旁的 `CampusMarket-data\product-images`，该目录需要事先存在。已有商品图片时可传 `-ProductImagesDir` 并沿用图片原目录。商品图片和商家私有资质目录应位于项目外，互不包含。数据库凭据和加密密钥只在本机按需输入。

### 历史操作 · 首次创建开发库管理员

在项目根目录的交互式 PowerShell 显式运行以下命令。初始化不占用 8080，可独立于当前后端运行。默认 JDBC 地址直接回车，按提示输入本机开发库账号和密码、新管理员登录名及两次管理员密码。密码输入不回显，管理员密码须为 12–256 字符。

```powershell
java -jar .\backend\target\campus-market-stage59.jar --init-admin
```

工具只允许本机的 `campus_market_dev`。它复用 `PasswordConfig`，以 `ADMIN / APPROVED / ENABLED` 新建第一位管理员。已有管理员或登录名已被占用时不改写账号。管理员资料中的联系方式为开发占位值。成功后通过 `/login` 登录，在「管理后台 → 用户管理」将注册审核条件设为「待审核」，打开该用户，选择「批准注册」。切换账号时可使用独立的浏览器窗口。

管理员已随数据迁移至学号库。当前启动使用本页上方的阶段 6.1 脚本。阶段 5.9 JAR 和初始化方式仅供历史记录。

阶段 3（后端基础架构）：开发库启动检查已由本机执行确认；统一响应、全局异常处理和 Jakarta Validation 已接入。

- 业务接口使用 `ApiResponse<T>`，响应字段为 `code`、`message`、`data`。成功代码为 `OK`；失败时 `data` 为 `null`，并保留对应 HTTP 状态。健康检查仍返回 `{"status":"UP"}`。
- 参数错误返回 400，数据约束冲突返回 409，未预期异常返回 500 和通用消息；业务异常使用显式指定的 4xx 状态或 503，以及可公开的消息。错误响应不返回 SQL、异常详情或堆栈。
- 已完成 MyBatis Plus Mapper 扫描和认证账号查询：`db` 模式注册 Mapper；`LoginAccountQuery` 使用 Jakarta Validation 校验，移除首尾 ASCII 普通空格，并按既定规则将合法登录名转换为小写后查询。查询仅供后端认证使用，不开放 HTTP 接口。
- 在后端源码的临时副本运行 `mvn.cmd -B -ntp clean verify`，15 项测试全通过，覆盖响应格式、HTTP 状态、参数校验、异常脱敏、健康检查及模拟 JDBC 的启动检查。测试不连接 MySQL。原目录的 JAR 若仍被运行中的实例占用，请先在启动窗口按 `Ctrl+C`，再运行项目根目录的构建命令。
- 账号查询任务在隔离目录运行 `mvn.cmd -B -ntp clean verify`，30 项测试全通过；覆盖账号查询参数校验、规范化、无匹配账号及 MyBatis 查询绑定，未连接实际数据库。
- 登录认证任务在隔离目录运行同一命令，43 项测试全通过（新增 13 项），覆盖密码哈希、登录成功、会话更换、失败响应、账号资格和非法请求。登录接口测试使用模拟账号查询，尚未使用 MySQL 账号联调。
- 阶段 3 权限控制已接入。隔离目录执行 `mvn clean verify`，59 项测试通过（新增 16 项）；权限测试使用模拟账号查询，不连接 MySQL。
- 注册及图片验证码已接入。隔离目录执行 `mvn clean verify`，70 项测试通过（新增 11 项），覆盖注册校验、验证码生命周期、重复账号、角色固定和敏感信息处理。使用模拟 Mapper，未写入 MySQL。
- 注销及登录失败限流已接入。隔离目录执行 `mvn clean verify`，76 项测试通过（新增 6 项），覆盖注销、旧会话与其他会话隔离、限流及到期恢复、成功重置和容量上限。未连接 MySQL。
- 阶段 4.1 管理员用户查询、注册审核和账号启停已实现。隔离目录执行 `mvn clean verify`，86 项测试通过（新增 10 项）；使用 MockMvc 和模拟 Mapper、事务管理器，未在 MySQL 执行审核或启停。
- 阶段 4.1.1 Session CSRF 防护已接入。隔离目录执行 `mvn clean verify`，89 项测试通过（新增 3 项，并扩展现有登录、注销和管理员回归用例），未连接 MySQL。
- 阶段 4.2.1 个人卖家和正式商家双路径已实现。隔离目录执行 `mvn clean verify`，103 项测试通过（新增 14 项）。接口及事务回调用模拟数据库组件测试，文件校验使用本地临时目录；迁移和端到端实库验收未执行。
- 后端逐模块以 Maven 自动化测试验收；前端接入后再统一进行 MySQL 联调。
- 正式商家审核与等级调整、公开商品目录、卖家商品草稿与库存调整、商品提审与上下架、商品图片已逐批通过 `mvn clean verify`（累计 109、113、118、120、123 项）。商品编辑与收藏加入后，127 项测试通过，但 C 盘空间不足导致 JAR 打包失败。购物车代码和测试已添加，尚未运行其回归构建。

## 阶段 5.1 前端基础框架与认证

| 页面或操作 | 路由 | 后端调用 |
| --- | --- | --- |
| 首页 | `/` | `GET /api/health` |
| 登录 | `/login` | `GET /api/auth/csrf`、`POST /api/auth/login`，随后恢复身份并刷新 Token |
| 注册 | `/register` | `GET /api/auth/csrf`、`GET /api/auth/captcha`、`POST /api/auth/register` |
| 个人中心 | `/account` | `GET /api/auth/me`，显示当前身份和用户编号 |
| 卖家中心 | `/seller` | 阶段 5.3 已接入卖家档案和经营资格查询 |
| 管理员后台 | `/admin` | 按 `GET /api/auth/me` 返回的管理员身份显示，阶段 5.4 已接入 |
| 商品分类 | `/categories` | 商品目录入口，阶段 5.2 已接入 |
| 退出登录 | 顶部导航 | `POST /api/auth/logout`，成功后清除本地身份和 Token |

- 修改了 `frontend/src/App.vue`、`frontend/src/main.js`、`frontend/src/api.js` 和 `frontend/vite.config.js`。新增 `frontend/src/router.js`、`frontend/src/auth.js`、`frontend/src/styles.css` 及 `frontend/src/views/` 下的首页、登录、注册和通用入口组件。
- 同源 `/api` 请求通过浏览器管理的 Session Cookie 保持会话。Axios 统一提取业务响应，写请求自动携带 `X-CSRF-TOKEN`。登录和注册表单的敏感输入只在当前页面内存中使用。注册失败后获取新验证码，成功后提示等待审核。
- `npm.cmd run build` 已通过，共转换 91 个模块。Vite 开发服务器的 `/login`、`/register` 直达返回 HTTP 200，路由模块成功解析。经 Vite 代理访问真实 standalone 后端的 `/api/health` 返回 `{"status":"UP"}`。Axios 模拟响应检查覆盖匿名恢复、三类身份、验证码 Blob、错误提示数据、Token 更新与重试以及注销。
- 阶段 5.1 验证时缺少 `db` 模式的凭据和注册密钥，真实登录、注册及 Cookie 会话仍待实库联调。

## 阶段 5.2 商品首页、分类、搜索与详情

使用前面的 PowerShell 启动方式运行 Vite，商品数据需要同机 `db` 模式后端提供。

| 页面 | 前端路由 | 后端接口 |
| --- | --- | --- |
| 商品首页 | `/` | `GET /api/categories` 和 `GET /api/products`，分别使用 `NEWEST`、`SALES_DESC` 展示最新与热门 |
| 商品列表 | `/categories`，别名 `/products` | `GET /api/categories`、`GET /api/products` |
| 商品详情 | `/products/:productId` | `GET /api/products/{id}`、`GET /api/products/{id}/images`、`GET /api/products/{id}/images/{position}` |
| 商品评价 | 详情页，页码使用 `reviewPage` | `GET /api/products/{id}/reviews?page=1&size=8` |
| 商品收藏 | 详情页 | `GET /api/favorites` 查询当前状态、`PUT /api/favorites/{id}` 收藏、`DELETE /api/favorites/{id}` 取消 |

- 商品列表把 `keyword`、`categoryId`、`sellerId`、`minPrice`、`maxPrice`、`sort` 和 `page` 保存在地址中。应用筛选后回到第一页，每页 12 件。排序支持 `NEWEST`、`PRICE_ASC`、`PRICE_DESC`、`SALES_DESC`、`POSITIVE_RATE_DESC`。卖家编号可直接输入，也可通过详情页的店铺链接带入。
- 商品卡片和详情直接展示后端 `price`，有价差时详情同时展示 `originalPrice`。图片按接口返回的顺序使用首图，进入详情可切换图片。售罄商品标注零库存，已下架及不存在的商品按后端的 `PRODUCT_NOT_FOUND` 提示。
- 详情使用后端的平均星级、好评率和评分数，纯文字评价按接口原样显示。游客点击收藏后可登录并返回原商品及评价页码，收藏写操作复用 Axios 的 CSRF 处理。
- `npm.cmd run build` 已通过，转换 100 个模块并成功输出生产包。Edge 无界面测试以模拟 API 覆盖了首页的两个商品区、组合筛选和请求参数、商品分页、主图与价格、售罄、评价分页、登录回跳、带 CSRF 的收藏和取消收藏，以及商品不可用。
- 本机 8080 的真实 `/api/categories` 返回 HTTP 404，当前会话未取得可用的 `db` 模式商品数据，真实目录、评价和收藏仍待联调。

## 阶段 5.3 卖家中心、申请与商品管理

| 页面 | 前端路由 | 后端调用 |
| --- | --- | --- |
| 卖家工作台 | `/seller` | `GET /api/sellers/me`；通过 `GET /api/sellers/me/products?size=1` 确认经营资格 |
| 卖家申请 | `/seller/apply` | `POST /api/sellers/personal` 接收 JSON；`POST /api/sellers/business` 接收 multipart，包含 JSON 部分 `application` 和文件部分 `businessLicense`、`identityDocument` |
| 商品管理 | `/seller/products` | `GET /api/sellers/me/products?page=1&size=20`；`PATCH /api/sellers/me/products/{id}/stock`；`POST /api/sellers/me/products/{id}/submit`、`publish`、`unpublish` |
| 新建和编辑商品 | `/seller/products/new`、`/seller/products/:productId/edit` | `GET /api/categories`；`POST /api/sellers/me/products`；`PUT /api/sellers/me/products/{id}`；`POST /api/sellers/me/products/{id}/images`（multipart 字段 `image`） |

- 普通账号进入工作台查看档案或申请，商品管理路由另用后端经营权限接口检查。工作台根据实际响应显示店铺、审核状态、经营状态、适用等级及费率。阶段 5.7 已开放订单与退货入口。
- 草稿可编辑、追加图片并提审。拒绝后可编辑，保存后按后端规则重回草稿。审核通过后可上架，已上架可下架。库存调整按照现有接口提交 `delta` 和 `operationKey`。
- 新建先保存商品资料，再用库存接口设置初始可售库存，逐张上传已选图片。这些是独立后端操作。界面保留已成功的结果并允许继续保存；库存重试复用原操作键。未提交图片可以本地预览和移除，提交时上传文件本体。
- 当前卖家接口只提供分页商品读取及追加图片。编辑页从卖家分页列表回显资料，已上架商品通过公开图片接口显示封面。未上架商品的历史图片缺少卖家读取接口，已上传照片也无删除接口；本轮提供当次上传预览。
- 分类路径已核对，前端和 `CatalogController` 均使用 `GET /api/categories`。该控制器及卖家商品接口只在 `db` profile 注册，因此需连接以 `db` 启动的对应后端验证。阶段 5.2 的 404 没有证据表明是路径错误。
- `npm.cmd run build` 成功，转换了 107 个模块。Edge 无界面测试配合模拟接口验证了游客、管理员和待审核用户的路由限制、两种卖家申请、multipart 字段和 CSRF、新建及编辑草稿、图片预览和上传、库存调整、提审及上下架；阶段 5.2 的目录与收藏回归也通过。
- 实际请求本机 8080 的健康接口返回 200，分类接口仍返回 404。卖家申请、商品写入和文件存储均未在实际 `db` 模式联调。

## 阶段 5.4 管理员后台与平台管理

使用前面的 PowerShell 启动方式运行前端。管理员接口在后端 `db` 模式注册，页面与所有子路由均要求 ADMIN。写操作沿用 Session 和 Axios 的 `X-CSRF-TOKEN`，审核、启停及调级在发送前要求确认。

| 前端路由 | 已接入 API |
| --- | --- |
| `/admin` | `GET /api/admin/users?registrationStatus=PENDING&size=1` 和 `GET /api/admin/sellers?reviewStatus=PENDING&size=1`，使用分页响应的 `total` |
| `/admin/users` | `GET /api/admin/users`、`GET /api/admin/users/{id}`、`PATCH /api/admin/users/{id}/registration`、`PATCH /api/admin/users/{id}/status` |
| `/admin/sellers` | `GET /api/admin/sellers`、`GET /api/admin/sellers/{id}`、`GET /api/admin/sellers/{id}/documents/{kind}`、`GET /api/admin/sellers/levels`、`PATCH /api/admin/sellers/{id}/review` |
| `/admin/seller-levels` | `GET /api/admin/sellers?reviewStatus=APPROVED`、`GET /api/admin/sellers/levels`、`GET /api/admin/sellers/{id}/metrics`、`PATCH /api/admin/sellers/{id}/level` |
| `/admin/products` | `GET /api/admin/products/pending`、`GET /api/categories`、`PATCH /api/admin/products/{id}/review` |
| `/admin/settlements` | `GET /api/admin/settlements/fees`、`GET /api/admin/settlements` |

- 用户注册审核与账号启停使用独立字段。正式商家审核默认显示待审队列，批准须选择已启用的等级。点击文档后通过授权接口载入临时 Blob URL，收起或离开页面时释放。后台商家接口只返回正式商家，个人卖家无需在此提交资质。
- 等级名称、费率和启用状态直接使用后端数据。调级仅适用于已审核的正式商家，提交新等级及必填原因。结算支持按 UTC 时间范围、商家和历史等级筛选，可按商家或快照等级分组，明细展示后端保存的净收入和手续费快照。
- 商品待审接口只返回分页数组，工作台不显示商品待审总数。商品详情使用启用分类名称，停用分类回退为编号。阶段 5.9 为待审详情增加了管理员图片读取、卖家资料和审核意见。
- `npm.cmd run build` 已通过，构建转换 118 个模块。Edge 无界面测试配合模拟 API，验证了匿名和普通用户访问限制、待审数量、用户筛选与分页、注册审核和启停分离、取消确认后不发送请求、两类资质预览、商家批准与调级、商品审核后移出队列、结算筛选和快照展示。写操作验证了 CSRF 请求头。本阶段未访问 MySQL。

## 阶段 5.5 购物车与结账

| 页面 | 前端路由 | 已接入 API |
| --- | --- | --- |
| 商品详情的加入购物车 | `/products/:productId` | `PUT /api/cart/items/{productId}`，发送 `{"quantity":2}`，设置该商品在购物车中的总数量 |
| 购物车 | `/cart` | `GET /api/cart`、`PUT /api/cart/items/{productId}`、`DELETE /api/cart/items/{productId}`，另用公开商品详情和图片接口核对商品并显示封面 |
| 结账确认 | `/checkout` | `GET /api/cart`、`GET /api/products/{productId}` 核对所选商品，`POST /api/cart/checkout` 提交批次 |
| 结账回执 | `/checkout/result/:batchId` | 显示结账 POST 响应的 `batchId`、`quotedAmount`、各订单的 `orderNo`、`orderStatus` 和 `quotedAmount` |

- 购物车与结账要求普通用户身份，登录后回到原页面。数量修改和结账沿用 Session 与 CSRF。购物车支持按商品选择结账，单批最多 100 种。失效或库存不足的商品暂停选中，用户仍可移除。
- 结账按卖家展示明细和预估小计，提交前重查购物车和商品状态。数量或价格发生变化时，用户须再次确认。最终报价、库存预留和订单拆分由服务器决定。
- 结账 POST 只发送 `requestKey` 和 `items`，每项包含 `productId`、`quantity`。结果不明时保留原请求，并在同标签页刷新后按当前买家复用同一请求键与明细。临时会话存储只保留重试所需数据，收到回执或明确业务拒绝后清除。收到库存、商品状态或购物车变动冲突后须重新核对。
- 批次编号、订单编号、状态和金额均来自服务器。阶段 5.6 补充了批次只读查询与支付入口，刷新结果页亦可读取自己的批次。
- `npm.cmd run build` 已通过，转换 124 个模块。Edge 无界面浏览器测试使用模拟 API，覆盖匿名拦截、登录回跳、收藏与注销回归、购物车增删改、库存不足与失效商品、组合选择、多商家拆单、提交前价格变化、业务冲突、重复点击，以及服务器已生成订单但回执丢失时刷新页面复用原请求键。结果页正确展示两笔订单、待支付状态及服务端报价。测试未写入 MySQL。

## 阶段 5.6 钱包、积分与订单支付

| 前端路由 | 实际 API |
| --- | --- |
| `/wallet` | `GET /api/wallet?page=1&size=20`，返回钱包余额和流水 |
| `/points` | `GET /api/points?page=1&size=20`，返回积分余额和流水 |
| `/orders/pending` | `GET /api/checkout-batches?page=1&size=20`，列出当前买家的待支付结账批次 |
| `/checkout/result/:batchId` | `GET /api/checkout-batches/{batchId}`，读取所属批次、商品快照报价、订单现状与已有支付回执 |
| `/checkout/pay/:batchId` | 查询批次、钱包和积分，使用原有的 `POST /api/checkout-batches/{batchId}/pay` 支付 |

- 四个新增 GET 接口只在后端 `db` 模式启用，使用 Session 中的普通用户身份进行账户和批次归属检查，响应注明 `no-store`。钱包与积分各自返回 `balance`、`items`、`total`、`page`、`size`。账户未开立时 `balance=null`；积分数按十进制字符串序列化，保留 BIGINT 精度。
- 流水按 UTC 时间与流水 ID 倒序，展示关联订单、原因及收支方向。积分流水覆盖支付抵扣、消费奖励、退款恢复和奖励冲回。管理员充值在阶段 5.9 接入。
- 纯现金支付省略请求体。积分抵扣可按商家订单提交 `{"redemptions":[{"orderId":401,"points":200}]}`。每 100 积分抵扣 1 元，由卖家承担。每笔订单必须保留正数现金托管，后端拒绝全积分支付。纯现金和混合付款均按每单实付现金每满 1 元奖励 1 积分。退款按原始积分流水处理，平台手续费沿用方案 A。
- 支付页面发送请求时禁用按钮，沿用 `X-CSRF-TOKEN`。支付成功展示接口返回的 `paidAmount`、`pointsUsed`、`pointsDiscountAmount`。响应丢失时可只读查询，或在当前页面按原抵扣明细重试。后端按批次回放已支付结果，避免重复扣款。页面刷新也能用批次 GET 取回已提交的回执。
- Maven 定向测试 33 项通过，覆盖新增查询的权限和归属、积分精度及现有结账、支付和退款积分服务。`npm.cmd run build` 通过，共转换 129 个模块。Edge 模拟 API 验证了登录和 CSRF、钱包和积分流水、余额不足、全积分拒绝、现金及混合付款、重复提交、回执丢失重试和已取消批次。购物车与结账浏览器回归也通过。本阶段未做 MySQL 实库联调。
- 后端 JAR 已离线重新打包并校验，使用原启动说明的 `backend/target/campus-market-0.1.0-SNAPSHOT.jar` 即可加载新增的只读接口。

## 阶段 5.7 订单管理、履约与退货退款

| 页面 | 前端路由 | 接入 API |
| --- | --- | --- |
| 买家订单 | `/orders`、`/orders/:orderId` | 新增 `GET /api/orders`、`GET /api/orders/{orderId}`；复用整批取消 `POST /api/checkout-batches/{batchId}/cancel` 及单单确认 `POST /api/orders/{orderId}/confirm-receipt` |
| 卖家订单 | `/seller/orders`、`/seller/orders/:orderId` | 新增 `GET /api/sellers/me/orders`、`GET /api/sellers/me/orders/{orderId}`；复用 `POST /api/sellers/me/orders/{orderId}/deliver` |
| 买家退货 | `/orders/:orderId/return` | 查询所选订单及申请历史，使用 `POST /api/orders/{orderId}/return-requests` 提交 `requestKey` 和 `reason` |
| 卖家退货 | `/seller/returns`、`/seller/returns/:requestId` | 复用 `GET /api/sellers/me/return-requests` 和其详情；`PATCH .../{requestId}/decision`、`POST .../{requestId}/refund`、`POST .../{requestId}/receive` |

- 新增订单 GET 在 `db` 模式下读取当前会话的买家订单或具备经营资格的卖家自己的订单。请求不接收买家或卖家身份参数。列表支持 `page`、`size` 和 `status`，`RETURNS` 筛选退货或退款仍在处理的订单。详情包含成交快照、事件、交易时间和后端记录的奖励、退款及入库信息。
- 商品按商家订单展示。取消以结账批次为单位，支付继续通过 5.6 的入口，交付与收货以单个商家订单为单位。界面显示预约、实际交付、人工收货和自动收货时间。自动确认仍由现有后端任务执行。
- 退货期限由服务器按收货时间判断，写接口再次检查。结果不明时退货申请沿用原请求键和原因。标签页会话存储只保留请求键及原因摘要。卖家审核使用 `APPROVE` 或 `REJECT` 和可选的 `reviewReason`，拒绝时必填。审核后重新读取详情，返回列表时重新查询。
- 退款和验收独立。卖家余额不足时读取已保存的 `PENDING_CASH`，可使用已有退款入口重试。等待积分和现金已退仍未结束时只提供刷新查询。验收发送 `{"resellable":true}`，库存恢复明细从回执或已有库存事件读取。所有写操作沿用 Session、CSRF 与后端业务校验。
- 前端生产构建通过，共转换 137 个模块。Maven 全量离线测试 288 项全部通过，后端启动 JAR 已重新打包并核验包含订单查询 Controller。Edge 使用模拟 API 验证了买卖双方分页筛选和归属隔离、批次取消、交付回执丢失、收货、退货幂等重试、审核、现金及积分退款待处理、库存恢复和刷新后的进度。5.1 至 5.6 的浏览器回归通过，覆盖注册验证码、登录、目录及收藏、卖家、管理员、结账与支付。本轮未做 MySQL 实库联调或数据库迁移。

## 阶段 5.8 评价、信誉与站内消息

| 页面 | 路由 | 实际接口 |
| --- | --- | --- |
| 买家订单评价 | `/orders/:orderId` | `POST /api/order-items/{orderItemId}/review`、`POST /api/orders/{orderId}/seller-review` |
| 卖家订单评价 | `/seller/orders/:orderId` | `POST /api/sellers/me/orders/{orderId}/buyer-review`、`POST /api/sellers/me/return-requests/{requestId}/buyer-review` |
| 本单交易评价 | 买家及卖家订单详情 | `GET /api/orders/{orderId}/trade-reviews` |
| 信誉中心 | `/reputation` | `GET /api/users/me/reputation`、新增 `GET /api/users/me/trade-reviews?page=1&size=20` |
| 消息列表和未读数 | `/notifications`、顶部导航 | 新增 `GET /api/notifications?page=1&size=20&unreadOnly=false`、`GET /api/notifications/unread-count` |
| 消息详情和已读 | `/notifications/:notificationId` | 新增 `GET /api/notifications/{id}`、`PATCH /api/notifications/{id}/read`、`PATCH /api/notifications/read-all` |

- 写评价提交 `rating` 和 `content`，要求 1–5 整星及非空文字。同一商品明细只评价一次。交易评价按评价方向区分，卖家的退货评价逐申请判断，仅在已验收的记录中开放。后端保留参与人、自评、状态与唯一性检查。重复或结果不明时页面重新查询，已提交内容以服务端记录为准。
- 买家订单详情增加了 `itemReviews` 和 `canReview`。公开商品详情继续使用原评价接口。信誉中心显示已发出和收到的交易评价，使用原买家信誉统计。分页历史返回有卖家档案的本人满意度，沿用原统计逻辑。4–5 星计为好评，分母为有星级的评价，纯文字评价仍计入总数。
- 通知由现有业务事务写入。结账创建订单、支付完成、人工或自动确认收货时通知卖家；卖家交付时通知买家；买家申请退货时通知卖家；卖家批准或拒绝退货、退款全部完成时通知买家。仅现金到账、积分待处理、失败及业务重放不会触发完成通知。
- 通知复用 `notifications` 表和收件人、类型、事件键唯一约束，重复事件保留原消息及已读时间。通知的读写只按 Session 收件人授权，已读操作使用 CSRF。消息只为启用后发生的事件生成，已有历史交易未补发。
- 商品审核通过或拒绝时，在同一事务内通知所属卖家。消息包含商品编号、名称、审核时间，拒绝时附审核意见。消息详情可进入商品管理。通过后由卖家手动上架，拒绝后可修改并重新提交。
- 前端生产构建通过，共转换 143 个模块。Maven 离线全量测试 294 项通过，启动 JAR 已更新并核验包含评价和通知 Controller。Edge 模拟 API 验证了商品评价、双向交易和验收评价、重复提交、归属隔离、信誉统计、消息详情和分页、未读数量及单条和全部已读。阶段 5.1 至 5.7 的主要页面浏览器回归通过。本阶段未做 MySQL 实库联调，也未执行迁移。

## 阶段 5.9 验收补齐

- 成交记录页 `/products/sold` 支持全站和按店铺分页。使用已完成订单的成交数量扣除实际退货入库量，不显示买家资料。
- 订单双方在已支付、尚未交付期间可提出线下交易时间和地点，由对方接受当前提议后写入原预约字段。状态变更使用订单行锁，提议与确认记录在 `audit_events`。预约不改变库存和支付状态，也不替代实际交付。
- 管理员可编辑普通用户的姓名、手机号、邮箱、城市及性别代码。逻辑删除仅用于已停用且尚无卖家档案、结账记录或非零余额的普通账号；已有交易的账号可停用，记录继续保留。后台不能停用管理员。

- 管理员充值入口在用户详情。`POST /api/admin/users/{id}/wallet/recharges` 使用正数金额、原因和请求键，在一个事务中写入钱包、`ADMIN_RECHARGE` 资金流水及审计。相同请求键和金额复用原流水。买家与卖家的测试资金通过这个入口初始化。
- `GET /api/admin/products/{id}` 返回待审核资料、卖家名称和类型、授权图片位置；`GET /api/admin/products/{id}/images/{position}` 通过管理员 Session 预览图片。商品和商家审核支持 `reviewReason`，拒绝时必须填写，意见与审核同事务写入审计。
- 原始 DDL 与迁移文件保留。现场使用 [5.9 验收清单](docs/acceptance-5.9.md) 中的用例、测试账号和截图建议。
- 在隔离副本离线运行一次完整 `mvn clean verify`，306 项通过，0 失败、0 错误、0 跳过，保留一个阶段 5.9 最终候选 JAR。现有 API 基础测试仍有过时 API 编译提示。Vue 隔离生产构建通过，转换 146 个模块。
- Edge 模拟 API 验证了用户编辑与逻辑删除、预约双方提议与确认、已售商品分页，以及既有充值、商品审核与履约流程。项目负责人已确认开发库六类、五档费率和网页普通用户注册。此轮验收遵循范围限制，未连接数据库或切换运行服务，当前迁移状态和其余端到端交易仍待验收。

## 登录 API

- `POST /api/auth/login` 只在 `db` 模式启用，请求为 JSON，字段为 `loginName` 和 `password`。登录名沿用账号查询规则，密码不做去空格或大小写转换。
- 成功返回 HTTP 200 和统一响应，`data` 仅含 `userId`、`roleCode`。服务端创建新会话，已有会话失效；客户端通过 `JSESSIONID` Cookie 保持会话。Cookie 仅用作会话跟踪，设为 HttpOnly、SameSite=Strict；会话空闲 30 分钟后失效。部署到 HTTPS 时启用 Secure Cookie（`SERVER_SERVLET_SESSION_COOKIE_SECURE=true`）。
- 账号不存在或密码错误返回相同的 401 响应；密码正确但账号未通过审核或不可用返回 403；非法请求返回 400。失败不创建认证会话。
- 登录与注册共用 `PasswordEncoder`，使用带随机盐的 PBKDF2-HMAC-SHA256（600,000 次迭代），哈希以 `{pbkdf2-sha256-v1}` 开头。明文或未知格式不能登录。
- `POST /api/auth/logout` 凭当前 Session 的有效 CSRF Token 使该 Session 失效并返回过期的会话 Cookie，不影响其他会话；注销后的旧 Token 不再可用，直接重发旧请求返回 403。
- 登录限流按规范化登录名与服务端看到的客户端 IP 组合计数。默认从首次凭据失败起算 5 分钟，连续失败 5 次后，后续请求返回 429（`LOGIN_RATE_LIMITED`）；并发尝试也占用同一额度。成功只清除当前组合的失败计数。表满时新组合暂返回 429，过期记录在后续登录请求中按间隔清理。
- 可通过 `app.auth.login-limit.max-failures`、`app.auth.login-limit.window`、`app.auth.login-limit.max-entries` 调整阈值、窗口和记录上限。首版状态仅在单实例内存中，重启会重置；不信任客户端直接提供的转发头。代理部署时需由可信入口处理来源 IP，分布式部署不能依赖此内存限制。

## 会话与权限

- `db` 模式的 REST Controller 默认要求登录。登录和健康检查显式公开；`GET /api/auth/me` 从服务端 Session 恢复当前身份，只返回 `userId` 和当前 `roleCode`，不缓存响应。
- 每个受保护请求按 Session 中的用户 ID 读取当前账号资格与角色，不信任 Session 中的旧角色。无有效身份返回 401；账号不可用或权限不足返回 403，沿用统一错误响应。
- 后续接口用 `@ApiAccess` 声明 `USER`、`ADMIN` 或 `MERCHANT`。前两种按角色精确匹配；商家必须是有效普通账号，且有审核通过、正常经营、等级启用的档案。已有登录和健康检查仍可匿名访问。
- 管理员用户接口已接入 `ADMIN` 权限；商家规则仍通过测试专用路由验证。管理员写操作在事务内复核当前管理员资格，写请求还须通过 CSRF 校验。

### CSRF Token

- `db` 模式下，在第一次写请求前先调用公开的 `GET /api/auth/csrf`，读取统一响应中的 `data.token`。响应不缓存、不返回 Session ID；同一 Session 重复读取返回同一个 Token。
- 保持同一会话 Cookie，并在 `POST`、`PATCH`、`PUT`、`DELETE` 等写请求中设置 `X-CSRF-TOKEN`，包括登录、注册和注销。缺少、重复或不匹配的头返回 `403 INVALID_CSRF_TOKEN`，不会执行对应业务。GET、HEAD、OPTIONS 等只读请求不验 Token，仍遵守原有角色权限。
- 登录成功会更换 Session 和 Token。阶段 5.1 的前端会重新请求 `GET /api/auth/csrf`，注销后清除旧 Token，后续写请求会获取匿名会话 Token。

## 注册 API

- `GET /api/auth/captcha` 返回不缓存的 PNG，验证码有效期为 2 分钟，每次获取替换同一会话中的旧码。提交注册时保留相同的 `JSESSIONID`；通过请求格式校验后，任何一次验证码校验都会消耗验证码，之后重试须重新获取。
- `POST /api/auth/register` 接收 JSON：`loginName`、`password`、`realName`、`phone`、`email`、`city`、`genderCode`、`bankAccount`、`captcha`。密码长度为 8–256，手机用带国家码的国际格式（例如 `+8613800000000`），银行账号必须是包含 16 位 ASCII 数字的字符串。性别代码只校验大写代码格式，未预设字典。
- 这两个接口仅在 `db` 模式公开。注册成功返回 HTTP 201，账号固定为 `USER`、`PENDING`，不会建立认证会话。重复登录名返回 409，验证码错误或过期返回 400。
- 启动前通过本机安全配置提供 `BANK_ACCOUNT_ENCRYPTION_KEY`：32 字节随机密钥的 Base64 编码，独立于数据库密码且不得提交或打印。银行账号使用 AES-256-GCM、随机 nonce 和带版本的密文封装；密钥缺失或不合法时注册返回 503，不写库。持久化数据必须保留对应密钥；轮换与解密读取尚未接入，不要在重启时随意生成替代密钥。

## 管理员用户 API（阶段 4.1）

仅在 `db` 模式启用，使用已经登录的管理员 Session。写请求先校验 CSRF；通过后，无认证身份返回 401，非管理员返回 403。接口不查询或返回密码哈希、银行账号及其密文。

| 接口 | 行为 |
|---|---|
| `GET /api/admin/users` | `page` 默认 1，`size` 默认 20、最大 100。可按 `loginName` 片段、`registrationStatus`、`accountStatus` 组合筛选；使用 `registrationStatus=PENDING` 查看待审核账号。返回 `items`、`total`、`page`、`size`。 |
| `GET /api/admin/users/{id}` | 返回基本资料、账号角色、注册审核和账号启停状态；不存在返回 404。 |
| `PATCH /api/admin/users/{id}/registration` | JSON `{"decision":"APPROVED"}` 或 `{"decision":"REJECTED"}`。只能审核 `PENDING` 的 `USER`，重复审核返回 409；不改变账号启停状态。 |
| `PATCH /api/admin/users/{id}/status` | JSON `{"status":"ENABLED"}` 或 `{"status":"DISABLED"}`。不改变注册审核或角色，不提供删除；已逻辑删除的账号不能恢复。重复提交当前状态不重复记审计。 |

成功的状态变更与 `audit_events` 写入共用一个事务，记录 Session 对应的管理员 ID、动作及目标用户 ID，审计写入失败则回滚。Spring 回滚调用已用模拟事务管理器验证；MySQL 实际写入、行锁及回滚待实库验收。

## 卖家档案 API（阶段 4.2.1）

在 Workbench 中打开 `database/sql/4.2.1_seller_types_migration.sql`。先只执行 `USE`、两条 `SHOW CREATE TABLE` 和计数查询，核对目标库及旧结构。若发现缺失资质或结构与前四批不符，停止，不填占位文件。备份并暂停后端写入后再执行两条增量 `ALTER TABLE`；MySQL 的两条 DDL 不组成一个整体事务，若中途失败先核对已执行部分，不整批重跑。不要重跑旧建表脚本。

更新后的后端使用新增的 `merchants.seller_type`，需先迁移再启动。旧档案按 `BUSINESS` 保留，既有附件和商品、订单外键不变；无等级的个人卖家使用零手续费，结算结构允许零费率、零手续费及空等级快照。实际计费和结算业务不在此阶段实现。

| 接口 | 请求与结果 |
|---|---|
| `POST /api/sellers/personal` | JSON `shopName`、可选 `shopDescription`；创建可经营的 `PERSONAL` 档案，无附件、无等级。 |
| `POST /api/sellers/business` | multipart：`application`（JSON，店铺字段同上）、`businessLicense`、`identityDocument`；复用账号中的姓名、手机号、性别和加密银行账号，创建待审核、未开通、无等级的 `BUSINESS` 档案。 |
| `GET /api/sellers/me` | 返回当前用户的卖家档案、店铺、审核及经营状态，适用时返回等级信息；不返回资质引用或路径。 |

仅审核通过且启用的普通用户可申请；一个用户最多一份档案。写请求使用当前 Session 的 `X-CSRF-TOKEN`。已有经营权限检查同时支持活跃个人卖家和已审核、正常经营、等级启用的正式商家；商品发布与审核将在商品模块接入。

正式商家上传前，通过本机环境变量 `SELLER_PRIVATE_DOCUMENTS_DIR` 指定已创建的私有绝对目录：必须位于项目外、不对外提供静态访问，并限制给后端运行账号。接受实际可解码的 PNG/JPEG（每份最大 5 MiB），重新编码后以服务端随机文件名保存。确认回滚时补偿删除本次文件；提交结果不明或进程异常退出时保留文件，待核对数据库引用后清理。没有公开读取入口，孤儿巡检尚未实现。

## 商家审核、商品与购物车 API

- `GET /api/admin/sellers` 支持分页和 `reviewStatus` 筛选；`GET /api/admin/sellers/{id}/documents/{kind}` 仅管理员可读取资质 PNG（`BUSINESS_LICENSE` 或 `IDENTITY_DOCUMENT`），响应不缓存且记录审计。批准时 `PATCH /api/admin/sellers/{id}/review` 提交 `decision=APPROVED` 和启用的 `levelCode`；拒绝时不提交等级。等级查询、调级和种子数据核验见阶段 4.6.3。
- `GET /api/categories`、`GET /api/products`、`GET /api/products/{id}` 提供公开目录。列表支持名称、分类、卖家、价格区间、分页及五种排序，详见阶段 4.6.2。图片用 `GET /api/products/{id}/images` 查询位置，再读取 `GET /api/products/{id}/images/{position}`。
- `/api/sellers/me/products` 支持分页、创建草稿、编辑草稿或被拒绝的商品、库存调整及上传图片。通过 `POST /{id}/submit` 提审；管理员用 `GET /api/admin/products/pending` 查队列、`PATCH /api/admin/products/{id}/review` 审核。获批商品可用 `POST /{id}/publish` 上架、用 `POST /{id}/unpublish` 下架。库存调整提交 `delta` 和 `operationKey`。商品图片目录用 `PRODUCT_IMAGES_DIR` 配置，须与私有资质目录分离。
- `/api/favorites` 支持列表、收藏和取消收藏。`GET /api/cart`、`PUT /api/cart/items/{productId}`（JSON `quantity`）和 `DELETE /api/cart/items/{productId}` 提供购物车基础操作；购物车不会预留库存或扣款。写接口沿用 Session 和 CSRF。

## 购物车结账与订单创建

- `POST /api/cart/checkout` 需要普通用户 Session 和 `X-CSRF-TOKEN`。请求示例：`{"requestKey":"checkout-001","items":[{"productId":7,"quantity":2},{"productId":8,"quantity":1}]}`。只结算选中的商品；提交数量须与购物车当前数量一致。
- 返回 `batchId`、`quotedAmount` 和各卖家订单。后端按当前售价写入成交明细快照，按卖家拆单，写入 `PENDING_PAYMENT` 初始事件及库存预留事件，并在同一事务清除已结账的购物车条目。商品和库存以行锁复核；个人卖家无等级要求，正式商家须有启用等级。
- 买家行锁串行化同一账号的结账，数据库唯一键继续兜底。同一请求键重试须提交原商品和数量（顺序不限），返回原批次；同键不同内容返回 409。库存不足或任一写入失败时整批回滚。
- 创建订单时只预留库存。待支付时资金分摊字段为零，`quotedAmount` 来自订单明细快照。预留超时释放尚未接入。

## 批次钱包支付与资金托管

- `POST /api/checkout-batches/{batchId}/pay` 需要普通用户 Session 和 `X-CSRF-TOKEN`。纯现金支付无需请求体。响应包含 `batchId`、现金实付 `paidAmount`、`orderIds`、实际抵扣积分 `pointsUsed` 和抵扣金额 `pointsDiscountAmount`。仅能支付自己的批次。
- 服务端锁定买家、批次、订单和钱包，按订单明细快照中的成交单价及数量重新计算金额。余额充足时一次扣除批次总额，为每笔商家订单写入一笔付款流水及关联的托管记录，同步更新金额汇总并写入 `PENDING_PAYMENT` 到 `PAID` 的事件。支付操作在同一事务内完成。余额不足或尚未支付的批次中存在不可支付订单时，整批拒绝。
- 批次 ID 用于重试同一次支付。已成功的批次根据付款事件及批次汇总返回原结果；钱包流水、托管及付款事件各有数据库唯一键兜底。现有流水和托管约束要求每笔金额大于零，包含零金额订单的批次返回 409。
- 支付时会复核卖家当前经营资格。个人卖家无需资质附件和等级；正式商家需要审核通过、有启用等级且正常经营。支付只建立托管，手续费与卖家入账由独立结算任务处理。

## 待支付订单取消与库存释放

- `POST /api/checkout-batches/{batchId}/cancel` 需要普通用户 Session 和 `X-CSRF-TOKEN`，无需请求体。成功响应包含 `batchId`、`orderStatus` 和 `orderIds`。批次不存在或不属于当前用户时返回 404。
- 取消与支付使用相同的买家、批次和订单行锁。批次中的待支付订单从 `PENDING_PAYMENT` 更新为终态 `CANCELLED`，事件的 `to_status` 同步写入 `CANCELLED`。已取消订单无法支付。批次含已支付或其他不可取消订单时返回 409，整批拒绝。
- 库存按订单明细尚未释放的预留数量回补，商品库存、累计释放量、库存事件和订单状态事件在同一事务提交。重复取消返回原批次和订单信息，库存只释放一次。
- 已补充测试用例，本轮未运行 Maven 或测试。已支付退款及自动超时取消尚未实现。

## 线下交付与手动确认收货

- `POST /api/sellers/me/orders/{orderId}/deliver` 允许具有经营资格的订单所属卖家确认线下交付，将 `PAID` 转为 `DELIVERED`，用数据库 UTC 时间记录 `delivered_at`。
- `POST /api/orders/{orderId}/confirm-receipt` 允许订单所属买家确认收货。订单须处于 `DELIVERED` 且已有 `delivered_at`。成功后转为 `COMPLETED`，记录独立的 `buyer_confirmed_at`。
- 两个接口都需要 Session 和 `X-CSRF-TOKEN`，无需请求体。响应包含 `orderId`、`orderStatus`、`deliveredAt`、`buyerConfirmedAt` 和 `autoConfirmedAt`，时间使用 UTC。预约时间仍由 `scheduled_trade_at` 单独表示。
- 状态和事件同一事务提交。订单行锁串行化并发操作，手动和自动确认使用同一行锁。重复交付或重复手动确认保留原时间且不写新事件。无归属的订单返回 404，状态不允许时返回 409。
- 首次确认交付时，按商品 ID 升序处理明细，消耗量为累计预留减去已消耗和已释放量。结账时已扣减可售量，交付只减少商品预留量，增加明细累计消耗量，并以 `ORDER_CONSUME:<orderItemId>` 写入 `CONSUME` 库存事件。交付时间、状态和事件与库存写入同一事务，任一环节失败则回滚，预留不足返回 409。
- 重复交付不补扣库存。已在旧版本标为 `DELIVERED` 或 `COMPLETED` 的订单如仍有未消耗预留，需要单独对账。
- `OrderFulfillmentTest` 9 项定向测试全部通过，覆盖正常消耗、多商品、重复交付、预留不足及库存事件失败时触发事务回滚。尚未运行全量测试或 MySQL 联调。

## 7 天自动确认收货

- 教师模板规定售卖后超过 7 天且买家未确认时自动确认，未指定用于计算的数据库时间字段。首版以实际交付时间为起点。
- `db` 模式启动定时任务，默认启动 1 分钟后首次扫描，前一次扫描结束 1 分钟后再次执行。可通过 `app.orders.auto-confirm-delay` 调整扫描间隔，格式示例为 `PT1M`。到期规则固定为自 UTC 实际交付时间 `delivered_at` 起满 168 小时。
- 每轮取一次数据库 UTC 截止时间，以订单 ID 分页扫描，每批最多 100 条。候选订单须处于 `DELIVERED` 且两个确认时间均为空。每笔订单单独开启事务并取得与手动确认相同的订单行锁，再检查状态、时间及两个确认字段。
- 满足条件时转为 `COMPLETED`，写入 `auto_confirmed_at` 和 `AUTO_CONFIRM` 状态事件，`actor_type=SYSTEM`、`actor_user_id=NULL`、`system_source=AUTO_CONFIRM`。`buyer_confirmed_at` 保持为空。先成功提交的确认生效。单笔异常回滚后继续处理其他订单，失败订单进入后续扫描。
- 自动确认只处理收货状态。库存消耗发生在线下交付时，资金结算由独立任务处理。
- `OrderFulfillmentTest` 与 `OrderAutoConfirmationJobTest` 合计 15 项定向测试全部通过，覆盖到期边界、确认顺序、重复扫描、事务回滚、分批继续处理和定时任务注册。未运行全量测试或 MySQL 联调。

## 阶段 4.4 商家结算与平台手续费

- 启动新版 `db` 后端前，在已应用 4.2.1 卖家类型迁移的开发库中执行一次 `database/sql/4.4_settlement_level_id_snapshot.sql`。该迁移补充 `settlements.level_id_snapshot` 和限制删除的外键，已有行的未知等级 ID 保持为空。此脚本尚未执行。
- `SettlementJob` 默认启动 1 分钟后首次扫描，随后每次扫描结束 1 分钟后再次运行。可用 `app.orders.settlement-delay` 调整间隔，格式示例为 `PT1M`。按订单 ID 分页，每批最多 100 条，仅调用内部 `SettlementService.settle`。
- 订单须为 `COMPLETED`，且实际手动或自动确认时间恰有一个，距数据库 UTC 时间满 24 小时。存在 `return_requests.is_active = 1` 的退货申请，或存在 `refunds.completed_at IS NULL` 的退款时跳过。后续退货和退款写入须先锁定同一订单行，且退款未终结期间须保持 `completed_at` 为空。
- 仅释放与订单及原买家扣款一致、从未释放或退回的现金托管。个人卖家费率为 0；正式商家采用结算时启用等级的费率。手续费以托管本金乘费率，使用 `HALF_UP` 保留两位小数；卖家到账为本金减手续费。结算行保存等级 ID、等级编号、费率、取值时间、计费基数、本金、手续费、净收入及完成时间。
- 每单独立事务，依次锁定订单、托管、商家、适用的等级及卖家钱包。净收入计入卖家余额，以 `SETTLEMENT:<orderId>` 写入收入流水，保存结算并释放托管本金，手续费只记录在结算行。原始结算由托管唯一键保护，重复调用返回原快照。钱包缺失、等级停用、资金对账失败或任一写入失败时保留待处理，任务记录错误并继续其他订单。
- `SettlementServiceTest` 17 项定向测试全部通过，包括五档费率、舍入、24 小时边界、退货退款拦截、重复执行、事务回滚和模拟行锁下的并发入账。未运行全量测试或 MySQL 联调。

## 任务 4.5.1 退货申请与卖家审核

部署本任务前执行一次 `database/sql/4.5.1_return_request_idempotency.sql`。迁移增加订单内唯一的申请请求键，历史申请保留空键，原有效申请唯一约束继续生效。本轮未执行迁移。

| 接口 | 请求与行为 |
| --- | --- |
| `POST /api/orders/{orderId}/return-requests` | 订单买家提交 JSON `{"requestKey":"return-001","reason":"商品无法使用"}`。同一订单使用同键和相同原因重试时返回原申请，原因不同返回 409。 |
| `GET /api/sellers/me/return-requests` | 当前具有经营资格的卖家查询自己的申请，可传 `status=PENDING`、`APPROVED`、`REJECTED` 或 `RETURNED`，分页参数 `page`、`size` 分别默认 1 和 20，单页最多 100 条。 |
| `GET /api/sellers/me/return-requests/{requestId}` | 查询本卖家的单条申请，其他卖家的申请返回 404。 |
| `PATCH /api/sellers/me/return-requests/{requestId}/decision` | 提交 JSON `{"decision":"APPROVE"}` 或 `{"decision":"REJECT","reviewReason":"具体原因"}`。拒绝须填写审核意见，意见最多 500 字符。 |

- 所有接口沿用 Session，写请求需要 `X-CSRF-TOKEN`。带经营资格的个人和正式卖家均可处理自己的订单。查询只返回订单和申请相关字段。
- 申请针对整笔商家订单，仅接受 `COMPLETED` 订单，退货原因必填且最多 500 字符。以实际手动或系统确认收货时间为起点，UTC 满 24 小时即关闭申请窗口，同一边界开始允许结算。拒绝后若仍在申请窗口内，买家可用新键重新提交。
- 新申请为 `PENDING` 且 `is_active=1`。批准后为 `APPROVED`，继续占用有效申请并阻止放款。拒绝后为 `REJECTED`，释放占用，订单可按原规则重新参与结算。已处理申请的再次审核返回 409。审核人、时间、意见与审核审计同一事务提交。
- 申请、审核和结算先锁定同一商家订单，再以开发库主连接的 `FOR UPDATE` 当前读检查结算及有效申请。批准只记录卖家同意退货，现金退款须调用独立接口。
- `ReturnRequestServiceTest` 14 项与 `SettlementServiceTest` 17 项定向测试全部通过，覆盖归属、24 小时边界、幂等、审核、审计回滚和模拟订单锁下的结算竞争。未运行全量测试或 MySQL 联调。

## 任务 4.5.2 现金退款与资金冲回

- `POST /api/sellers/me/return-requests/{requestId}/refund` 需要所属卖家的 Session、经营权限与 `X-CSRF-TOKEN`。无需请求体。仅处理已批准退货，首次执行还要求申请有效且订单为 `COMPLETED`。返回 `refundId`、`refundStatus`、`cashRefundAmount` 和 `fundingPath`。
- `ESCROW`：核对订单现金金额、买家原扣款与未释放且未退回的托管本金，向买家钱包退回全部现金，更新 `escrows.returned_amount`，并关联 `refunds.escrow_id`。
- `SELLER_WALLET`：核对原结算、托管和卖家原到账流水，扣回全部原始现金金额，再给买家入账。原托管维持已释放，退款关联 `refunds.original_settlement_id`。余额不足时保存 `PENDING_CASH`，返回 409 和 `SELLER_REFUND_BALANCE_INSUFFICIENT`；实际退回金额为 0，资金路径表示待执行来源。余额补足后可用同一申请重试。现有 24 小时申请、审核与结算规则继续适用，已结算退款分支只接受已有的合格退货申请。
- `POINTS_ONLY`：以积分全额支付且没有现金证据的订单登记为 `PENDING_POINTS`，返回 HTTP 202，现金金额为 0，等待积分处理。
- 退款先锁定商家订单，再锁定申请、托管、原结算和退款行，最后按用户 ID 升序锁钱包。资金变化和退款记录在同一事务提交，退款行及钱包流水唯一约束配合 `REFUND:<orderId>` 防止重复入账。退款流水通过 `original_entry_id` 关联原买家扣款或原卖家入账。重复请求返回已保存的退款结果。
- 现金到账后标记 `CASH_REFUNDED`。`refunds.completed_at` 由积分处理任务在资金流程完成后设置。按方案 A 保留原平台手续费，已结算订单仍由卖家承担全额现金退款。本任务无需新增数据库迁移。
- Maven 定向执行退款 17 项、退货 14 项、结算 17 项测试，合计 48 项全部通过。资金回滚和并发采用事务管理器及订单锁模拟，未运行全量测试或 MySQL 联调。

## 任务 4.5.3 积分恢复与奖励冲回

- 积分退款只使用原订单的真实积分流水。支付抵扣与奖励发放使用买家的同一积分账户，流水分别为 `ORDER_POINTS_REDEMPTION/DEBIT` 和 `ORDER_POINTS_REWARD/CREDIT`，以 `order_id` 关联商家订单，原流水的 `refund_id` 和 `original_entry_id` 均为空。抵扣流水合计用于核对订单 `points_used`，历史奖励按实际流水冲回。写入原流水需先锁订单再锁积分账户，退货退款处理中不应再发放该订单的奖励。
- `db` 模式默认每分钟扫描 `CASH_REFUNDED` 和 `PENDING_POINTS` 的未完成退款，每批最多 100 笔，可通过 `app.orders.refund-points-delay` 配置间隔。每笔独立事务，按订单、退货申请、退款、积分账户顺序锁定。单笔异常记录日志，后续继续重试。处于 `PENDING_CASH` 的退款不进入积分处理。
- 按原始流水逐笔记录 `REFUND_POINTS_RESTORE` 或 `REFUND_POINTS_REVERSE`，用 `original_entry_id` 和 `refund_id` 追溯，以 `REFUND_POINTS:<originalEntryId>` 去重。在同一事务更新余额、积分流水和 `refunds.points_restored`、`refunds.points_reversed`。来源不一致或缺失时暂停该笔。
- 先恢复实际抵扣积分，再判断可用余额是否足以冲回全部尚未处理的奖励积分。余额不足时保留已恢复积分与原待处理状态，在 `failure_reason` 记录尚待冲回数量，后续重试不会重复恢复。没有积分流水且订单积分抵扣为零时可以直接完成。
- 该恢复与冲回的积分全部处理后，退款转为 `COMPLETED` 并写入 UTC `completed_at`。全积分退款完成后重复调用退款接口返回原结果。退货申请仍保留有效占用，库存入库由卖家另行确认。
- Maven 定向测试通过，积分退款及调度 13 项、现金退款回归 18 项。并发与回滚采用模拟订单行锁及事务管理器，未运行全量测试或 MySQL 联调。

### 任务 4.5.3 上游积分接入

- 教师模板规定每 100 积分抵扣 1 元、每消费 1 元奖励 1 积分。支付接口支持可选的 `redemptions` 请求体，例如 `{"redemptions":[{"orderId":7,"points":100}]}`。买家按批次内商家订单指定抵扣，数量以 100 积分为一档，服务器用订单明细快照计算金额。
- 卖家承担抵扣，首版支持纯现金与现金加积分混合支付。每笔商家订单须保留正数现金托管金额，全积分支付返回 409。实际现金部分继续进入现有托管和结算。
- 买家积分账户以行锁扣减，积分或钱包余额不足则整批拒绝。逐单写入原始抵扣流水及资金分摊，并汇总至结账批次。已支付批次重复调用返回已保存的结果。
- 支付成功即发放奖励积分，每笔商家订单按实际现金支付金额每满 1 元奖励 1 积分，分别向下取整。纯现金也按此规则发放，首次有奖励时自动建立积分账户。同一笔支付先校验和扣减已有积分，再发放奖励，支付、托管、积分余额与流水在同一事务提交。退款按原奖励流水冲回，手续费仍按方案 A 处理。
- 结账、取消、支付、现金退款及积分退款共 47 项 Maven 定向测试全部通过，覆盖逐单奖励取整、重复支付、全积分支付拒绝及退款流水识别。未运行全量测试或 MySQL 联调。

## 任务 4.5.4 退货验收与库存恢复

- `POST /api/sellers/me/return-requests/{requestId}/receive` 需要订单所属卖家的 Session、经营资格与 `X-CSRF-TOKEN`。提交 JSON `{"resellable":true}`，表示已收到整笔订单的退回商品，且所有商品均符合重新销售条件。响应返回 `request`（含订单信息、状态和 UTC `returnedAt`）及本次入库明细 `items`。
- 申请从 `APPROVED` 转为 `RETURNED`，记录 `return_requests.returned_at`，保留原有效占用。该状态保留既有退款资格。验收可在资金退款前后独立执行，现金和积分退款仍由各自入口处理。审批、支付、取消、确认收货及结算规则沿用现有实现。
- 与退款、结算共用同一订单行锁。按商品 ID 升序锁定和更新，恢复数量为实际消耗减累计退货入库。仅增加 `products.available_quantity` 与 `order_items.restocked_quantity`，保持原有预留量和上架状态。无实际消耗记录、商品已逻辑删除或数量异常时整笔拒绝。
- 每条入库流水使用 `RESTOCK` 和 `RETURN_RESTOCK:<requestId>:<orderItemId>`，并与申请状态及 `RETURN_REQUEST_RECEIVE` 审计同一事务提交。重复验收从原流水返回相同结果。现有表字段和约束满足本任务，无需迁移。
- Maven 定向测试 72 项全部通过，其中包含 8 项验收测试及现金、积分退款兼容用例。并发与回滚使用模拟订单锁和事务管理器验证，实际 MySQL 联调待进行。

## 阶段 4.6.1 商品评价与双向交易评价

教师模板要求商品文字与五星评价、买家评价卖家服务、卖家评价购买或退货的买家。启用本模块前应用 `database/sql/4.6.1_trade_review_rating.sql`，为交易评价补充星级。已有文字评价保留，星级为空。本轮未执行迁移。

| 接口 | 用途 |
| --- | --- |
| `POST /api/order-items/{orderItemId}/review` | 已完成订单的买家评价对应商品，每条明细一次。 |
| `POST /api/orders/{orderId}/seller-review` | 买家评价所属订单卖家的服务，每单一次。 |
| `POST /api/sellers/me/orders/{orderId}/buyer-review` | 订单所属卖家评价已完成购买交易的买家。 |
| `POST /api/sellers/me/return-requests/{requestId}/buyer-review` | 订单所属卖家评价已批准且已验收退货的买家，退货验收以 `RETURNED` 和 `returned_at` 为准。 |
| `GET /api/products/{productId}/reviews` | 公开查询可浏览商品的评价和好评统计，支持 `page`、`size`，默认 1、20，单页最多 100 条。 |
| `GET /api/orders/{orderId}/trade-reviews` | 订单买卖双方查询本单交易评价。 |
| `GET /api/sellers/me/satisfaction`、`GET /api/users/me/reputation` | 分别查询自己的商家满意度和买家信誉。 |

- 写接口提交 `{"rating":5,"content":"评价内容"}`，要求 1–5 整星和非空文字，最多 2000 字符。沿用 Session 和 CSRF。评价目标从订单推导，禁止自评。数据库唯一约束防止重复评价，重复提交返回 409。购买评价使用 `PURCHASE`，退货评价使用 `RETURN:<requestId>`。
- 商品好评率只读取商品评价；商家满意度只统计买家对卖家的交易评价；买家信誉只统计卖家对买家的购买及退货评价。统计返回评价总数、已评分数、好评数、平均星级及好评比例。首版将 4–5 星计为好评，比例为好评数除以已评分数，范围 0–1，保留四位小数。无评分时平均星级和比例为 `null`。
- 商品公开评价只返回评分、文字和时间等评价字段。商品好评率已用于目录排序。商家满意度也用于管理员调级时查看的经营指标。
- Maven 定向测试 21 项通过，包括评价服务 12 项、接口及权限 5 项、商品目录回归 4 项。本轮未执行数据库迁移或 MySQL 联调。

## 阶段 4.6.2 商品搜索与综合排序

沿用公开接口 `GET /api/products`，`keyword`、`categoryId`、`sellerId`、`minPrice`、`maxPrice`、`page` 和 `size` 可组合使用。关键词按字面做名称包含匹配，省略 `sellerId` 时可查多个商家的商品。目录保持审核通过、已上架、未删除及原分类和经营资格校验。

| `sort` | 排序口径 |
| --- | --- |
| `NEWEST` | 默认，按发布时间降序。 |
| `PRICE_ASC`、`PRICE_DESC` | 用有效折扣价，未设折扣价时用原价。价格区间使用同一实际价格。 |
| `SALES_DESC` | 只统计 `COMPLETED` 订单，逐明细汇总 `quantity - restocked_quantity`。申请退货但尚未验收入库时，销量保持原值。无销量按零处理。 |
| `POSITIVE_RATE_DESC` | 复用商品评价口径，4–5 星评价数除以有星级的评价数。纯文字评价不计入分母，无星级评价按零排序。 |

同一指标按发布时间、商品 ID 依次降序。MyBatis 按所选排序加入产品级聚合，计数和分页使用同一套目录条件。接口返回结构沿用原目录，无需数据库迁移。

Maven 定向测试 39 项全部通过。使用本地 H2 内存样本额外执行 MyBatis 生成的五种排序和组合分页查询，均符合预期。未进行 MySQL 实库联调。

## 阶段 4.6.3 商家等级与平台手续费

以下均为管理员接口。调级沿用现有 Session 和写请求 CSRF 校验。

| 接口 | 行为 |
| --- | --- |
| `GET /api/admin/sellers/levels` | 返回全部已配置的等级，包含 `enabled`。 |
| `GET /api/admin/sellers/{id}` | 查询正式商家及其当前等级。 |
| `GET /api/admin/sellers/{sellerId}/metrics` | 查看正式商家的当前等级、累计已结算现金总额、结算笔数及满意度。 |
| `PATCH /api/admin/sellers/{id}/level` | 提交 `{"levelCode":3,"reason":"经营数据复核"}`。仅可选择存在且启用的等级，同级请求无变更。 |
| `GET /api/admin/settlements/fees` | 查询累计手续费及结算笔数。可用 `groupBy=SELLER` 或 `groupBy=LEVEL` 查看分组。 |
| `GET /api/admin/settlements` | 分页查询结算和手续费快照，`page`、`size` 默认 1、20，单页最多 100 条。 |

- 初次分配沿用商家批准接口。调级审计记录管理员、原等级、新等级、原因和 UTC 时间，与商家等级变更同一事务提交。调级由管理员根据数据手动决定。五档费率由数据库约束固定。
- 手续费统计和明细均支持 `from`、`to`、`sellerId`、`levelCode`，可组合。时间传 ISO 8601 含时区偏移的值，转为 UTC 后按左闭右开区间查询。按等级筛选和分组使用结算时快照；个人卖家的等级为 `null`。
- 成交金额口径为手续费扣除前已释放的现金托管本金 `settlements.released_amount`。手续费收入直接汇总 `settlements.fee_amount`，方案 A 下退款不影响已有收入。没有结算时金额和笔数均返回零。

### 开发库等级初始化

阶段 4.6.3 的 Workbench 查询曾显示 `campus_market_dev.merchant_levels` 为空。当时使用原有种子脚本初始化。迁移已验证学号库包含五档费率，此处保留旧库核对语句作为历史记录。

```sql
SELECT level_code, fee_rate, is_enabled
FROM campus_market_dev.merchant_levels
ORDER BY level_code;
```

空表首次执行后应有 5 行，费率依次为 `0.001000`、`0.002000`、`0.005000`、`0.007500`、`0.010000`，`is_enabled` 均为 1。此任务无需迁移，开发库种子脚本尚待执行。

Maven 定向测试 72 项通过。首次全量测试有 3 项图片存储测试受沙盒临时目录权限影响，离线重跑后全量 277 项通过。H2 样本验证了种子脚本重复执行、按历史等级聚合、调级后费率快照稳定和退款后手续费保持。新接口尚未进行 MySQL 实库联调。
