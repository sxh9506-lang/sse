# SSE 长连接演示（Java + Nginx）

一个可本地运行、可镜像化部署到阿里云 SAE 的 Server-Sent Events 示例项目。浏览器访问指定域名后先到前端 Nginx，再由 Nginx 将 `/api/*` 同源转发到 Java 后端；SSE 响应以流式方式透传，不经过浏览器跨域，也不被 Nginx 缓冲。

## 架构

```text
浏览器
  │ https://sse.example.com
  ▼
阿里云 ALB（域名、TLS 证书、监听器）
  │ 转发至前端 SAE 应用
  ▼
前端容器：Nginx + 静态页面（端口 80）
  │ /api/* 内网反向代理，SSE 禁用缓冲
  ▼
后端容器：Spring Boot / Java 17（端口 8080）
  ├── GET /api/events  长连接事件流
  └── GET /api/health  健康检查
```

前端使用浏览器原生 `EventSource`。服务端连接建立后每 5 秒推送一条事件、每 15 秒发送 SSE 注释心跳。关闭页面或断开连接会清理对应任务；浏览器遇到临时断线会自动重连。

## 本地运行

需要 Docker 和 Docker Compose：

```bash
docker compose up --build
```

打开 <http://localhost:8088>，点击“建立连接”。独立检查后端：

```bash
curl http://localhost:8088/api/health
curl -N http://localhost:8088/api/events
```

停止：

```bash
docker compose down
```

后端 API 也可在没有前端时直接运行：在 `backend/` 下执行 `mvn spring-boot:run`，默认监听 8080。

## 构建并推送镜像到阿里云 ACR

两个服务分别构建，前端的后端地址通过 `BACKEND_ORIGIN` 注入。生产环境必须将该值设为 SAE 后端应用可达的**内网**地址（`http://` + 内网域名/IP + `:8080`），不要设置为浏览器可访问的 localhost，也不要让前端容器走公网调用后端。

### 1. 准备 ACR 仓库

在阿里云控制台进入**容器镜像服务 ACR**，创建/选择地域实例，创建命名空间（namespace），并分别创建 `sse-backend` 和 `sse-frontend` 两个镜像仓库。记录该地域/实例的登录地址、命名空间和仓库名。ACR 控制台通常会在实例的访问凭证/访问控制页面显示登录地址；地址因地域、个人版/企业版实例和公网/专有网络访问方式而不同，以下地址仅是杭州地域的示例：

```text
registry.cn-hangzhou.aliyuncs.com/<命名空间>/sse-backend:<版本>
registry.cn-hangzhou.aliyuncs.com/<命名空间>/sse-frontend:<版本>
```

如果构建机在 VPC 内，优先选择构建机可达的专有网络/内网端点；否则使用已开启且可访问的公网端点。不要照抄示例地址，实际端点以 ACR 控制台展示的地址为准。

### 2. 登录 ACR

在本地安装并启动 Docker，然后执行登录。`--username` 使用 ACR 控制台要求的账号（可能是阿里云账号或 RAM 用户）；运行后在提示符中输入 ACR 访问凭证/访问密码。不要把密码写进脚本、Git 或聊天记录。

```bash
REGISTRY="registry.cn-hangzhou.aliyuncs.com"  # 替换成 ACR 控制台给出的登录地址
NAMESPACE="your-namespace"                     # 替换成你创建的命名空间
VERSION="1.0.0"
ACR_USERNAME="your-acr-username"               # 替换成 ACR 要求的登录用户名

docker login --username="$ACR_USERNAME" "$REGISTRY"
```

若仓库使用企业版实例提供的专属端点，`REGISTRY` 和登录用户名格式都按控制台指引填写。下面各代码块默认在同一个终端依次执行；如果重新打开终端，需要重新设置这些变量。

### 3. 构建、打标签并推送

SAE 运行镜像需与所选运行环境架构兼容。建议构建 `linux/amd64` 镜像；较新的 Docker 支持以下 `--platform` 参数：

```bash
docker build --platform linux/amd64 -t "$REGISTRY/$NAMESPACE/sse-backend:$VERSION" ./backend
docker build --platform linux/amd64 -t "$REGISTRY/$NAMESPACE/sse-frontend:$VERSION" ./frontend

docker push "$REGISTRY/$NAMESPACE/sse-backend:$VERSION"
docker push "$REGISTRY/$NAMESPACE/sse-frontend:$VERSION"
```

如果本机 Docker 不支持跨架构 `--platform` 构建，可在 Linux/amd64 构建机执行，或使用 Docker Buildx：

```bash
docker buildx build --platform linux/amd64 -t "$REGISTRY/$NAMESPACE/sse-backend:$VERSION" --push ./backend
docker buildx build --platform linux/amd64 -t "$REGISTRY/$NAMESPACE/sse-frontend:$VERSION" --push ./frontend
```

推送成功后，在 ACR 控制台相应仓库的**镜像版本**页面确认 `$VERSION` 已出现。每次发布建议使用新的版本标签（例如 `1.0.1` 或 Git 提交短 SHA），避免覆盖正在运行的标签。

### 4. 在 SAE 中使用已推送的镜像

创建/更新 SAE 应用时，镜像地址填写完整地址，例如 `registry.cn-hangzhou.aliyuncs.com/<命名空间>/sse-backend:1.0.0`。私有仓库需要确保 SAE 有拉取权限：按当前 SAE 控制台提供的 ACR 授权方式配置同账号授权、RAM 权限或镜像仓库凭证；不要假设仅在本地 `docker login` 后 SAE 就能访问。前端镜像同理。

## 阿里云 SAE + ALB 部署

1. **准备网络与镜像**：在 ACR 创建镜像仓库并推送前后端镜像。准备 SAE 使用的 VPC、交换机与安全组；前端容器到后端容器的 TCP/8080 必须在同一 VPC 内可达。
2. **创建后端 SAE 应用**：使用 `sse-backend` 镜像，容器端口 `8080`，监听端口 `8080`，启动探针/健康检查路径设为 `/api/health`。配置合理的 CPU/内存，并按预期长连接数设置实例数和连接容量。记录 SAE 提供的后端应用内网服务发现地址或内网访问地址。
3. **创建前端 SAE 应用**：使用 `sse-frontend` 镜像，容器端口 `80`。添加环境变量 `BACKEND_ORIGIN=http://<后端内网服务地址>:8080`（示例仅供说明，替换为 SAE 控制台提供且从前端 VPC 可达的地址）。前端探针可使用 `/`。确保前后端应用处于可互通的网络中。
4. **配置 ALB**：创建或使用已有 ALB，在监听器中将业务端口（建议 HTTPS/443）转发至前端 SAE 应用的后端服务器组/内网入口，后端端口为 `80`。将指定域名（如 `sse.example.com`）解析到 ALB 地址，并在 ALB 监听器绑定域名对应的 TLS 证书；可另设 HTTP/80 重定向到 HTTPS/443。具体后端服务器组类型按当前地域和 SAE/ALB 控制台支持的 SAE 集成方式选择。
5. **验证链路**：访问 `https://<指定域名>/` 确认页面加载；访问 `https://<指定域名>/styles.css` 应返回 CSS 内容且状态码为 200；访问 `https://<指定域名>/api/health` 确认返回 `status: UP`；在页面点击连接，或运行 `curl -N https://<指定域名>/api/events` 确认数据持续输出。

如果页面显示为无样式的纯文本，先在浏览器开发者工具的 Network 面板检查 `styles.css`：确认请求状态是 200、响应是 CSS，而不是 404 或返回了 `index.html`。从仓库根目录以 `./frontend` 为构建上下文重新构建并推送前端镜像，使用新的镜像标签更新 SAE 应用，并清理/刷新浏览器缓存。本项目前端样式使用系统字体，不依赖 Google Fonts 等外部字体站点。

> **SSE/负载均衡注意事项**
>
> - 前端 Nginx 配置已为 `/api/` 关闭 `proxy_buffering` 和 gzip，`proxy_read_timeout` / `proxy_send_timeout` 设置为 3600 秒。`X-Accel-Buffering: no` 也会随响应发送。
> - 将 ALB/任何中间代理的连接空闲超时设得大于心跳周期（建议至少 60 秒；本项目心跳间隔为 15 秒）。如还有 WAF、CDN 等代理，也要确认其支持并允许长时间流式响应，必要时提高其超时或将 SSE 路径排除缓冲。
> - ALB 后端健康检查不要使用 `/api/events`（它不会结束响应），应使用 `/api/health`。长连接建立后会固定落在某个后端实例；扩缩容或发布时允许客户端自动重连，生产发布建议配置足够的优雅下线时间。
> - 这是演示流：每条连接独立收到示例事件。生产环境的消息广播、用户鉴权、断线续传、跨实例共享状态和容量保护需按业务补充。

## API

- `GET /api/events` — `text/event-stream`；事件名 `connected` 和 `message`，包含 `id`、JSON 数据。心跳使用 SSE 注释，不会触发浏览器 `message` 回调。
- `GET /api/health` — JSON 健康状态及当前活动 SSE 连接数。
