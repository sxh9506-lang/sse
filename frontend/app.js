const connectButton = document.querySelector('#connect-button');
const connectionState = document.querySelector('#connection-state');
const stateLabel = document.querySelector('#state-label');
const metaStatus = document.querySelector('#meta-status');
const eventCount = document.querySelector('#event-count');
const lastEvent = document.querySelector('#last-event');
const eventList = document.querySelector('#event-list');
const emptyState = document.querySelector('#empty-state');
const liveIndicator = document.querySelector('#live-indicator');
let source = null;
let received = 0;

function setState(state, label) {
  connectionState.className = `connection-state ${state}`;
  stateLabel.textContent = label;
  metaStatus.textContent = label;
  liveIndicator.classList.toggle('is-live', state === 'connected');
}

function formatTime(value) {
  const date = value ? new Date(value) : new Date();
  return new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false }).format(date);
}

function addEvent(type, data, id) {
  emptyState.hidden = true;
  received += 1;
  eventCount.innerHTML = `${received} <small>条</small>`;
  lastEvent.textContent = formatTime(data?.time);

  const row = document.createElement('article');
  row.className = 'event-row';
  const label = document.createElement('span');
  label.className = `event-badge ${type === 'connected' ? 'badge-connected' : 'badge-message'}`;
  label.textContent = type.toUpperCase();
  const body = document.createElement('div');
  body.className = 'event-body';
  const title = document.createElement('strong');
  title.textContent = data?.message || '收到服务端事件';
  const detail = document.createElement('span');
  detail.textContent = data?.clientId ? `client ${data.clientId}${data.sequence ? ` · #${data.sequence}` : ''}` : '服务端已建立连接';
  body.append(title, detail);
  const metadata = document.createElement('div');
  metadata.className = 'event-metadata';
  const time = document.createElement('time');
  time.textContent = formatTime(data?.time);
  const eventId = document.createElement('span');
  eventId.textContent = id ? `ID ${id}` : 'LIVE';
  metadata.append(time, eventId);
  row.append(label, body, metadata);
  eventList.prepend(row);

  // Keep the live log bounded for long-running tabs.
  while (eventList.querySelectorAll('.event-row').length > 100) {
    eventList.querySelector('.event-row:last-of-type').remove();
  }
}

function connect() {
  if (source) {
    source.close();
    source = null;
    connectButton.innerHTML = '<span class="button-icon">▶</span> 建立连接';
    connectButton.classList.remove('is-connected');
    setState('', '未连接');
    return;
  }

  setState('connecting', '连接中');
  metaStatus.textContent = '正在建立长连接';
  connectButton.innerHTML = '<span class="button-icon">■</span> 断开连接';
  connectButton.classList.add('is-connected');
  source = new EventSource('/api/events');

  source.onopen = () => setState('connected', '已连接');
  source.addEventListener('connected', (event) => {
    let data = {};
    try { data = JSON.parse(event.data); } catch { data.message = event.data; }
    addEvent('connected', data, event.lastEventId);
  });
  source.addEventListener('message', (event) => {
    let data = {};
    try { data = JSON.parse(event.data); } catch { data.message = event.data; }
    addEvent('message', data, event.lastEventId);
  });
  source.onerror = () => {
    if (source?.readyState === EventSource.CONNECTING) {
      setState('connecting', '重新连接中');
      metaStatus.textContent = '连接中断，浏览器正在重试';
    } else if (source?.readyState === EventSource.CLOSED) {
      source = null;
      connectButton.innerHTML = '<span class="button-icon">▶</span> 建立连接';
      connectButton.classList.remove('is-connected');
      setState('error', '连接关闭');
    }
  };
}

connectButton.addEventListener('click', connect);
document.querySelector('#clear-button').addEventListener('click', () => {
  eventList.querySelectorAll('.event-row').forEach((row) => row.remove());
  emptyState.hidden = false;
  received = 0;
  eventCount.innerHTML = '0 <small>条</small>';
  lastEvent.textContent = '—';
});
