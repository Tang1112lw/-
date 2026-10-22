# 中控直播助手

前后端分离版本：

- `backend/`：Spring Boot 4.1.1 + Playwright
- `frontend/`：React 19 + Vite + TypeScript
- `dist/`：打包后的 Windows 程序
- `tools/`：开发期检查、截图、Logo、语料校验脚本

当前默认单会话、默认自动执行。界面只保留“启动会话 / 停止全部 / 刷新状态”，实时显示收到的评论和本次程序自己发出的评论。

**评论内容全部来自本地线程语料库，不调用任何 AI，也不需要 API Key。** 每一行就是一条独立评论，单独发送；同一线程的首、中、尾是三条评论，按顺序隔轮发出，不会拼成一条多行消息。

固定人设：**汉语言专业大四学生**，正在投简历、改论文、做实习，会用 AI 写文案改简历，不会编程，预算有限。

场景权重：**夸主播 0.35、AI 漫剧 0.35、AI 课程 0.175、萌新唠嗑 0.125**。

## 三个对话场景 + 夸主播

| 场景 | 线程 | 条数 | 内容 |
|---|---|---|---|
| AI 漫剧 | 114 | 342 | 小白视角问漫剧怎么做、工具、成本与耗时、角色/口型/分镜的坑、变现与入行 |
| AI 课程 | 100 | 300 | 问课程基础、回放、作业、价格、适不适合自己；提问都是一句话的简单问法 |
| 萌新唠嗑 | 60 | 158 | 分享自己用 AI 的小事、翻车和吐槽，兼作提问线程里的跟评 |
| 夸主播 | — | 200 | 围绕女主播的声音、讲法、课程和 AI 漫剧内容，按 0.35 权重随机插入 |

合计 **1000 条评论 / 274 条线程**（800 条线程语料 + 200 条夸奖）。线程语料在 `backend/src/main/resources/corpus/threads.tsv`，夸奖语料在 `backend/src/main/resources/corpus/praise.txt`。格式：

```text
threadId|scene|intent|text
c54|course|ask|手机上能看吗
c54|chat|follow|我睡前躺床上想看
c54|chat|share|用手机听也行吧
```

同一个 `threadId` 按文件顺序播放，第一行决定线程起始场景（`course` / `comic` / `chat`）；每一行单独发一条评论。

**每条评论都是独立的一句**：不引用上一句，不出现“刚问的”“你说的”“它”这类要结合上下文才看得懂的说法；主播不回看前一条，单独看到这句也能明白。语气按直播间打字的口语来写，不用测评腔和书面语。

每一条评论都按权重抽场景：抽到「夸主播」就单独插一句，不动线程进度；抽到对话场景时，当前线程还没走完就继续走它，走完再按抽到的场景开新线程。所以夸奖会均匀挂在对话之间，不会把一条线程切碎。

## 直接运行

双击：

```text
dist\CenterControl\CenterControl.exe
```

控制台地址：

```text
http://127.0.0.1:18080
```

### 浏览器桥接（唯一模式）

后端只使用 CDP 接管一个由程序管理的持久 Edge 配置，不提供临时配置模式，也不会接管用户日常 Edge 的默认配置。

首次使用：

1. **先在日常 Edge 里登录抖音，然后完全关闭 Edge**（包括后台进程）。
2. 双击 `dist\CenterControl\CenterControl.exe`。程序启动时会检测日常 Edge 状态：
   - 已经关闭：把日常配置里的抖音登录态（Cookie 数据库 + DPAPI 解密密钥）复制进自动化配置，随后打开的自动化浏览器直接就是已登录状态，不需要扫码；
   - 还在运行：不碰它的任何文件，本次跳过同步，继续使用上一次导入的登录态。
   - 日常 Edge 里没有抖音登录态（没有 `sessionid` 等登录 Cookie）：不覆盖自动化配置，避免把“未登录”的 Cookie 顶掉已有登录。
3. 点击“启动会话”。浏览器通过 `9222` 接管，后续会话持续使用这个自动化配置。

自动化配置保存在：

```text
%USERPROFILE%\.centercontrol\edge-profile
```

它和日常 Edge 使用的 `%LOCALAPPDATA%\Microsoft\Edge\User Data` 完全分离：

- 不创建指向日常配置的 junction；
- 启动前校验配置目录的真实路径，一旦落在日常 User Data 内、或目录本身是链接/联接，直接拒绝启动；
- 只关闭明确使用本程序配置的 Edge 进程，日常 Edge 永远不会被结束；
- 日常 Edge 正在运行时连读取都不做（Cookie 数据库被独占），不会造成锁冲突。

同步细节：只复制 Cookie 数据库和 `Local State` 里的 DPAPI 密钥，是同机、同 Windows 用户下的本地复制。自动化浏览器启动后会清理非抖音域名的 Cookie，其余站点（银行、邮箱等）的 Cookie 不会留在自动化窗口里。日常 Edge 下次完全关闭再启动程序时会重新同步，登录态跟着日常浏览器走。

Chromium 136 以后禁止直接对默认 `User Data` 开启远程调试，而已运行的普通 Edge 也不能从外部补开 CDP。因此“直接接管日常 Edge 当前窗口”没有稳定、无侵入的方案。程序采用独立持久配置 + 登录接力：日常登录一次，之后的启动和会话都复用同一份 Cookie、Local Storage 与登录态；停止会话只断开 Playwright/CDP，不会清理 Cookie。

## 开发模式

后端：

```powershell
cd backend
.\gradlew.bat bootRun
```

若 `gradlew.bat` 卡在下载 Gradle，可直接使用本机已解压的版本：

```powershell
$env:JAVA_HOME = 'C:\Users\11589\.jdks\temurin-17'
& 'C:\Users\11589\.gradle\wrapper\dists\gradle-9.8.1-bin\41i18951l6m6st25rtrwj0neh\gradle-9.8.1\bin\gradle.bat' bootRun
```

前端：

```powershell
cd frontend
npm install
npm run dev
```

前端开发地址：

```text
http://127.0.0.1:5173
```

Vite 已配置 `/api` 代理到 `http://127.0.0.1:18080`，前端不需要写死后端地址。

## 生产构建

```powershell
cd frontend
npm install
npm run build

cd ..\backend
.\gradlew.bat build -x test
```

后端 `processResources` 会把 `frontend/dist` 打进 Spring Boot 静态资源，所以最终只需要启动后端 jar / exe。

一键构建 exe：

```powershell
.\backend\build-exe.ps1
```

## 界面与交互

- 圆形品牌 Logo + 极简白底后台风格
- 顶部只保留三个主操作：启动会话、停止全部、刷新状态
- 右上角显示本地语料总条数
- 直播间地址输入框：可填写/切换抖音直播间地址，切换后本场评论与计数重置
- 状态条分开显示：收到评论、本次已发评论、运行状态、登录状态、自动模式、下一轮
- 「下次评论」面板显示：预计时间、倒计时、本次间隔、场景、即将发送的内容，以及最近一次动作 / 最近错误
- 评论间隔可在页面直接修改（1–86400 秒，即 1 秒到 24 小时；默认 15–60 秒），保存后写入 `%USERPROFILE%\.centercontrol\interval.txt`，重启仍然生效
- 每轮只发一条评论；一条线程走完再开下一条，线程之间不会交错
- 同一线程的后续评论会等一个轮间隔再发，是独立的一条消息，不是多行拼接

## 关键配置

`backend/src/main/resources/application.yml`

| 配置 | 默认 | 说明 |
|---|---|---|
| `centercontrol.max-sessions` | 1 | 默认单会话桥接 |
| `centercontrol.room-url` | 直播间直链 | 用 `live.douyin.com/{rid}`；粘贴 `www.douyin.com/search/...&live_web_rid=xxx` 会自动转成直链 |
| `centercontrol.browser-mode` | cdp | 只能使用真实浏览器桥接；其他值会拒绝启动 |
| `centercontrol.cdp-url` | http://127.0.0.1:9222 | 会话 1 调试端口 |
| `centercontrol.auto-actions` | true | 默认自动执行 |
| `centercontrol.loop-min-interval-ms` | 15000 | 最小轮间隔 |
| `centercontrol.loop-max-interval-ms` | 60000 | 最大轮间隔 |
| `centercontrol.action-min-gap-ms` | 8000 | 动作层二次保护 |
| `centercontrol.action-max-gap-ms` | 18000 | 动作层二次保护上限 |

## 主要接口

```text
GET  /api/status                       # 含 loginSync：是否已同步、来源配置、日常 Edge 是否在运行
GET  /api/sessions
GET  /api/sessions/{id}/comments?afterSeq=0&limit=100
POST /api/sessions/{id}/start
POST /api/sessions/{id}/stop
POST /api/sessions/start-all
POST /api/sessions/stop-all
POST /api/sessions/{id}/room
POST /api/settings/interval
```

最近一次使用的直播间地址会保存到：

```text
%USERPROFILE%\.centercontrol\room.txt
```

## 工程结构

```text
backend/
├── src/main/java/com/example/centercontro/
│   ├── assistant/     # 循环、语料库、策略、会话
│   ├── browser/       # CDP/Edge、DOM 评论、发评论
│   ├── config/        # 配置
│   └── web/           # REST + 启动控制台
└── src/main/resources/
    ├── application.yml
    └── corpus/        # threads.tsv（线程式语料）

frontend/
├── public/brand-logo.png
└── src/
    ├── App.tsx
    ├── api.ts
    ├── types.ts
    └── styles.css
```

## 已知限制

- 抖音 DOM 会改版，选择器集中在 `CommentWatcher` / `LiveActions`。
- 评论区只用于前端展示，不参与评论生成。
- 语料按线程顺序播放，关闭程序后线程进度会重置。
- 桥接只使用本机 Edge；如果系统没有安装 Edge，程序会拒绝启动浏览器桥接。
- 自动化配置与日常 Edge **不是实时共享**：只有日常 Edge 完全关闭时程序才会同步一次登录态；Edge 运行时沿用上一次同步的结果。
- 登录同步会把日常 Edge 的 Cookie 数据库复制进自动化配置，并替换自动化配置的 Cookie 主密钥，因此自动化配置里原有的 Cookie / 保存的密码会失效——它只作为程序的工作目录使用。
- 同一份抖音会话在两个浏览器里同时使用时，平台可能按自身风控策略处理；如果遇到异常，请只保留一个窗口在线。
- 历史打包已清理（2026-10-21）：包括带 junction 逻辑、会把日常 Edge 配置和自动化配置混在一起的旧版本。`dist\` 下只保留当前可用版本 `dist\CenterControl\CenterControl.exe`；重新打包时 `build-exe.ps1` 只保留最近一份备份。
- 没有任何浏览器参数能保证不被平台风控识别；请控制频率并遵守平台规则。

## 安全提醒

评论全部来自本地文件，不会上传任何数据，也不需要任何第三方 Key。

登录接力只在本机完成：日常 Edge 完全关闭时读取它的 Cookie 数据库和 `Local State` 里的 DPAPI 密钥，复制到 `%USERPROFILE%\.centercontrol\edge-profile`；不联网、不外传。日常 Edge 运行时不会读取它的任何文件。
