# SSE 长连接后端服务

基于 Node.js 原生 HTTP 模块实现，无需安装第三方依赖。服务端通过 Server-Sent Events（SSE）向多个客户端推送事件，支持心跳保活、断线重连后的有限事件回放、健康检查及优雅关闭。

## 运行

要求 Node.js 20 或更高版本：

```bash
npm start
```

默认监听 `0.0.0.0:3000`。开发模式（文件变更时自动重启）：

```bash
npm run dev
```

可通过环境变量配置服务：

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `HOST` | `0.0.0.0` | 监听地址 |
| `PORT` | `3000` | 监听端口 |
| `HEARTBEAT_MS` | `25000` | SSE 注释心跳间隔（毫秒） |
| `HISTORY_SIZE` | `100` | 内存中保留的事件数；设为 `0` 可关闭回放 |

## HTTP 接口

### 建立 SSE 长连接

```http
GET /events
```

响应类型为 `text/event-stream`。服务端每 25 秒发送一次注释心跳，避免代理或负载均衡器因连接空闲而断开。浏览器端可以使用原生 `EventSource`：

```js
const source = new EventSource('/events');
source.onmessage = (event) => {
  console.log('收到消息：', JSON.parse(event.data));
};
source.addEventListener('order.created', (event) => {
  console.log('新订单：', JSON.parse(event.data));
});
source.onerror = (error) => console.error('SSE 连接异常，将自动重连', error);
```

浏览器的 `EventSource` 会在重连时自动发送 `Last-Event-ID`。服务端会回放内存历史中 ID 大于该值的事件。历史记录仅存在当前进程内存中，服务重启后会清空；超出保留窗口的事件不会回放。

### 发布事件

```http
POST /publish
Content-Type: application/json

{
  "event": "order.created",
  "data": { "orderId": 42 }
}
```

`event` 可选，默认是 `message`；名称允许字母、数字、下划线、点和连字符。`data` 必填且可以是任意 JSON 值。发布成功返回 `202`，包括事件 ID 和发布时在线客户端数：

```json
{ "published": true, "id": "1", "clients": 2 }
```

命令行示例：

```bash
curl -N http://localhost:3000/events

curl -X POST http://localhost:3000/publish \
  -H 'Content-Type: application/json' \
  -d '{"event":"notice","data":{"message":"你好"}}'
```

`/publish` 是未鉴权的内部接口，部署到公网前应在网关层增加身份验证/授权，并按需配置 HTTPS 与跨域策略。服务通过有界历史缓存以及慢客户端缓冲区限制，避免无界内存增长。

### 健康检查

```http
GET /health
```

返回服务状态、当前连接数与历史事件数。`GET /` 提供简要服务信息。

## 测试

```bash
npm test
```
