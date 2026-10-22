# SSE 长链接后端服务（Java）

基于 Java 21、JDK 原生 `HttpServer` 和虚拟线程实现 SSE 推送服务。事件 JSON 由 Jackson 处理。支持多客户端广播、心跳保活、断线重连时回放有限历史、健康检查和关闭清理。

## 环境与启动

需要 **JDK 21+** 和 **Maven 3.8+**：

```bash
mvn test
mvn exec:java
```

服务默认监听 `0.0.0.0:3000`。可用环境变量配置：

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `HOST` | `0.0.0.0` | 监听地址 |
| `PORT` | `3000` | 监听端口 |
| `HEARTBEAT_MS` | `25000` | SSE 注释心跳间隔（毫秒） |
| `HISTORY_SIZE` | `100` | 内存保留的历史事件数量；`0` 表示关闭历史回放 |

例如：

```bash
PORT=8080 HISTORY_SIZE=200 HEARTBEAT_MS=15000 mvn exec:java
```

## HTTP 接口

### SSE 订阅

```http
GET /events
```

返回 `text/event-stream`，每隔一段时间发送注释心跳，以保持长连接。浏览器可直接使用原生 `EventSource`：

```js
const source = new EventSource('/events');
source.onmessage = (event) => console.log(JSON.parse(event.data));
source.addEventListener('order.created', (event) => {
  console.log('新订单：', JSON.parse(event.data));
});
source.onerror = (error) => console.error('连接异常，浏览器将自动重连', error);
```

重连时，浏览器会自动携带 `Last-Event-ID`。服务端回放进程内历史中 ID 更大的事件，也支持 `?lastEventId=1`。历史仅保存在内存，服务重启会清空；历史容量之外的事件不会回放。

### 发布事件

```http
POST /publish
Content-Type: application/json

{
  "event": "order.created",
  "data": { "orderId": 42 }
}
```

`event` 可选，默认 `message`，只接受字母、数字、下划线、点和连字符；`data` 必填，可以为任意 JSON 值。发布成功返回 `202`，其中包含事件 ID 和在线客户端数：

```json
{ "published": true, "id": "1", "clients": 2 }
```

命令行验证：

```bash
curl -N http://localhost:3000/events

curl -X POST http://localhost:3000/publish \
  -H 'Content-Type: application/json' \
  -d '{"event":"notice","data":{"message":"你好"}}'
```

### 健康检查

```http
GET /health
```

返回服务状态、在线连接数和历史事件数量。`GET /` 提供服务与接口概览。

## 安全说明

`/publish` 当前未做身份验证，建议仅在可信内部网络调用，或在反向代理/API 网关层添加身份认证、访问控制及 HTTPS。服务限制发布请求体大小为 1 MiB，并对每个 SSE 客户端的待发送队列设定上限，防止慢客户端导致队列无限增长。
