# Agora 更新检查 — Cloudflare Worker

用 Cloudflare Worker 代理 **私人 GitHub 仓库** 的 Releases，让 App 检查更新时 **不嵌入 GitHub Token**。

## 架构

```
App  ──GET /latest──►  Cloudflare Worker  ──Bearer token──►  GitHub API (private repo)
                              │
                              └── Token 只存在 Cloudflare Secret，不进 APK
```

## 你需要准备

1. Cloudflare 账号（免费即可）
2. GitHub **Personal Access Token**，能读私人仓库 Releases  
   - Classic：勾选 `repo`  
   - Fine-grained：对该仓库 `Contents: Read` + `Metadata: Read`
3. 私人仓库地址：`owner/repo`（例如 `myname/Agora-Private`）
4. 该仓库要有至少一个 **Release**（带 `v1.2.3` 这类 tag；可选上传 `.apk`）

## 一键配置步骤

在本目录执行：

```bash
cd server/update-check
npm install
```

### 1. 填仓库名

编辑 `wrangler.toml`：

```toml
[vars]
GITHUB_OWNER = "你的GitHub用户名或组织"
GITHUB_REPO  = "私人仓库名"
```

### 2. 登录 Cloudflare

```bash
npx wrangler login
```

浏览器会打开 Cloudflare 授权页，点允许即可。

### 3. 部署 Worker

```bash
npm run deploy
```

成功后终端会打印类似：

```text
https://agora-update-check.<你的子域>.workers.dev
```

把这个 URL 记下来（后面要写进 App）。

### 4. 写入 GitHub Token（密钥）

```bash
npx wrangler secret put GITHUB_TOKEN
```

粘贴 token 后回车。Token **不会**出现在代码或 git 里。

### 5. 自测

```bash
curl -i https://agora-update-check.<你的子域>.workers.dev/health
curl -i https://agora-update-check.<你的子域>.workers.dev/latest
```

`/latest` 应返回 JSON，包含 `tag_name`、`html_url`、`body`。

### 6. 改 App 端点

编辑：

`app/src/main/java/com/newoether/agora/util/UpdateChecker.kt`

把常量改成你的 Worker 地址：

```kotlin
private const val UPDATE_ENDPOINT =
    "https://agora-update-check.<你的子域>.workers.dev/latest"
```

然后重新编译安装 App。

## 本地调试

```bash
cp .dev.vars.example .dev.vars
# 编辑 .dev.vars，填入 GITHUB_TOKEN
npm run dev
curl http://127.0.0.1:8787/latest
```

## API

| 路径 | 说明 |
|------|------|
| `GET /health` | 健康检查 |
| `GET /` 或 `/latest` | 最新 release（JSON） |
| `GET /download` | 代理下载最新 release 里的 `.apk`（适合私人资产） |

## 安全注意

- **永远不要**把 GitHub Token 写进 App 或提交到 git
- Token 权限尽量只给这一个私人仓库
- 如 token 泄露：GitHub 立刻撤销，再 `wrangler secret put GITHUB_TOKEN` 换新
- Worker URL 是公开的：任何人都能查到「有没有新版本」；只有仓库内容 / APK 资产受 token 保护（经 `/download` 代理）

## 自定义域名（可选）

在 Cloudflare Dashboard → Workers → 该 Worker → Settings → Triggers → Custom Domains  
绑定你自己的域名，再把 `UpdateChecker.kt` 里的 endpoint 常量改成该域名。
