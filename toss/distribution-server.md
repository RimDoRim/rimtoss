# distribution-server — 분배 서버

아래 코드 블록들을 그대로 로컬에 같은 경로/파일명으로 만들어서 붙여넣으면 됩니다.

## 폴더 구조

```
.env.example
README.md
requirements.txt
  app/
    __init__.py
    collector_client.py
    config.py
    connection_manager.py
    main.py
    redis_hub.py
```

## `.env.example`

```bash
REDIS_URL=redis://localhost:6379/0
COLLECTOR_BASE_URL=http://localhost:8000
```

## `README.md`

```markdown
# 분배 서버 (distribution-server)

Redis에 쌓인 시세를 구독해서, 브라우저(Vue 3) 클라이언트의 WebSocket
연결로 실시간 릴레이하는 FastAPI 서비스입니다.
`toss-collector`가 수집한 데이터를 실제로 화면까지 전달하는 마지막 구간.

## 구조

```
app/
  config.py             # 환경변수 설정
  redis_hub.py          # Redis pub/sub 최소 구독 + 로컬 콜백 팬아웃
  collector_client.py   # toss-collector의 watch/unwatch 호출
  connection_manager.py # 서버 전역 ref-count (최초/마지막 구독자만 collector 호출)
  main.py               # FastAPI WebSocket 엔드포인트
```

## 실행

```bash
pip install -r requirements.txt --break-system-packages
cp .env.example .env   # collector 주소, redis 주소 확인
uvicorn app.main:app --reload --port 8100
```

`toss-collector`(포트 8000)와 Redis가 먼저 떠 있어야 합니다.

## 클라이언트 프로토콜

```js
const ws = new WebSocket("ws://localhost:8100/ws");

ws.onopen = () => {
  ws.send(JSON.stringify({ action: "subscribe", market: "kr", symbol: "005930" }));
};

ws.onmessage = (event) => {
  const msg = JSON.parse(event.data);
  // msg.topic: "trade:kr:005930" 또는 "orderbook:kr:005930"
  // msg.snapshot === true 면 최초 접속 시 내려주는 스냅샷
  console.log(msg.topic, msg.data);
};

// 화면 이탈 시
ws.send(JSON.stringify({ action: "unsubscribe", market: "kr", symbol: "005930" }));
```

## 동작 흐름

1. 클라이언트가 `subscribe`를 보내면, 이 커넥션이 아직 구독하지 않은
   topic(`trade:kr:005930`, `orderbook:kr:005930`)에 대해 `RedisHub`에
   콜백을 등록합니다. `RedisHub`는 서버 전체에서 topic당 Redis 구독을
   1개만 유지합니다(다른 클라이언트가 이미 보고 있으면 재사용).
2. 등록 직후 `latest:{topic}` 캐시에서 스냅샷을 즉시 내려줘서, 다음
   실시간 이벤트가 오기 전까지 빈 화면이 뜨지 않게 합니다.
3. `GlobalRefCounter`가 이 (market, symbol)을 보는 클라이언트가
   이번이 처음인지 확인해서, 처음이면 `toss-collector`에 `/watch`를
   호출 -> 토스 WS 구독이 실제로 추가됩니다.
4. 클라이언트 연결이 끊기면 `finally` 블록에서 구독하던 종목을 전부
   `unsubscribe` 처리하고, 마지막 시청자였다면 collector에 `/unwatch`
   를 호출해 상위 구독도 정리합니다.

## 아직 안 된 것 (다음 단계 후보)

- 여러 분배 서버 인스턴스로 수평 확장 시 Redis 구독 중복 자체는
  문제 없지만(각 인스턴스가 독립적으로 최소 구독 유지), collector에
  대한 watch/unwatch 호출도 ref-count가 필요하다면 collector 쪽에서
  이미 처리 중이므로 별도 조치 불필요
- 인증 (현재는 누구나 /ws 연결 가능 - 실제 서비스 전 JWT 등 붙여야 함)
- 캔들 히스토리 조회 API (수집 서비스가 저장한 뒤 REST로 노출)
```

## `requirements.txt`

```text
fastapi>=0.115
uvicorn[standard]>=0.30
httpx>=0.27
redis>=5.0
pydantic-settings>=2.4
websockets>=12.0
```

## `app/__init__.py`

```python

```

## `app/collector_client.py`

```python
import logging

import httpx

from .config import settings

logger = logging.getLogger("collector_client")


class CollectorClient:
    """
    시세 수집 서비스(toss-collector)의 /watch, /unwatch 엔드포인트를 호출해
    상위(토스 WS) 구독 갱신을 요청한다.
    """

    def __init__(self):
        self._client = httpx.AsyncClient(
            base_url=settings.collector_base_url, timeout=5
        )

    async def watch(self, market: str, symbol: str):
        try:
            resp = await self._client.post(f"/watch/{market}/{symbol}")
            resp.raise_for_status()
        except httpx.HTTPError:
            logger.exception("collector watch 호출 실패: %s/%s", market, symbol)

    async def unwatch(self, market: str, symbol: str):
        try:
            resp = await self._client.post(f"/unwatch/{market}/{symbol}")
            resp.raise_for_status()
        except httpx.HTTPError:
            logger.exception("collector unwatch 호출 실패: %s/%s", market, symbol)

    async def close(self):
        await self._client.aclose()
```

## `app/config.py`

```python
from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    redis_url: str = "redis://localhost:6379/0"
    # 시세 수집 서비스(toss-collector)의 주소 - watch/unwatch 호출용
    collector_base_url: str = "http://localhost:8000"

    class Config:
        env_file = ".env"


settings = Settings()
```

## `app/connection_manager.py`

```python
import asyncio
from collections import defaultdict


class GlobalRefCounter:
    """
    분배 서버 전체에서 (market, symbol) 조합을 보고 있는 브라우저 클라이언트
    수를 추적한다. 0 -> 1이 될 때만 상위 수집 서비스에 watch를,
    1 -> 0이 될 때만 unwatch를 호출해서 불필요한 API 호출을 없앤다.
    """

    def __init__(self):
        self._counts: dict[str, int] = defaultdict(int)
        self._lock = asyncio.Lock()

    async def incr(self, key: str) -> bool:
        """True면 이번이 최초 등록 (collector.watch 호출 필요)."""
        async with self._lock:
            self._counts[key] += 1
            return self._counts[key] == 1

    async def decr(self, key: str) -> bool:
        """True면 완전히 제거됨 (collector.unwatch 호출 필요)."""
        async with self._lock:
            if key not in self._counts:
                return False
            self._counts[key] -= 1
            if self._counts[key] <= 0:
                del self._counts[key]
                return True
            return False
```

## `app/main.py`

```python
import asyncio
import json
import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI, WebSocket, WebSocketDisconnect

from .collector_client import CollectorClient
from .connection_manager import GlobalRefCounter
from .redis_hub import RedisHub

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("distribution")

redis_hub = RedisHub()
collector_client = CollectorClient()
ref_counter = GlobalRefCounter()

# 종목 하나당 함께 구독할 채널들
CHANNELS = ("trade", "orderbook")


@asynccontextmanager
async def lifespan(app: FastAPI):
    redis_hub.start()
    yield
    await redis_hub.stop()
    await collector_client.close()


app = FastAPI(title="분배 서버", lifespan=lifespan)


@app.get("/health")
async def health():
    return {"status": "ok"}


@app.websocket("/ws")
async def ws_endpoint(websocket: WebSocket):
    """
    프론트엔드가 붙는 엔드포인트.

    클라이언트 -> 서버:
      {"action": "subscribe",   "market": "kr", "symbol": "005930"}
      {"action": "unsubscribe", "market": "kr", "symbol": "005930"}

    서버 -> 클라이언트:
      {"topic": "trade:kr:005930", "data": {...}, "snapshot": true}   # 최초 스냅샷
      {"topic": "trade:kr:005930", "data": {...}}                     # 실시간 갱신
    """
    await websocket.accept()

    local_subs: dict[str, callable] = {}  # topic -> callback (이 커넥션 전용)
    watched: set[tuple[str, str]] = set()  # (market, symbol) - 정리용
    send_lock = asyncio.Lock()

    async def send_json(payload: dict):
        async with send_lock:
            await websocket.send_text(json.dumps(payload, ensure_ascii=False))

    async def subscribe(market: str, symbol: str):
        for channel in CHANNELS:
            topic = f"{channel}:{market}:{symbol}"
            if topic in local_subs:
                continue

            async def callback(data: dict, topic=topic):
                await send_json({"topic": topic, "data": data})

            await redis_hub.add_listener(topic, callback)
            local_subs[topic] = callback

            latest = await redis_hub.get_latest(topic)
            if latest is not None:
                await send_json({"topic": topic, "data": latest, "snapshot": True})

        watched.add((market, symbol))
        key = f"{market}:{symbol}"
        if await ref_counter.incr(key):
            await collector_client.watch(market, symbol)

    async def unsubscribe(market: str, symbol: str):
        for channel in CHANNELS:
            topic = f"{channel}:{market}:{symbol}"
            callback = local_subs.pop(topic, None)
            if callback:
                await redis_hub.remove_listener(topic, callback)

        watched.discard((market, symbol))
        key = f"{market}:{symbol}"
        if await ref_counter.decr(key):
            await collector_client.unwatch(market, symbol)

    try:
        while True:
            raw = await websocket.receive_text()
            msg = json.loads(raw)
            action = msg.get("action")
            market = msg.get("market")
            symbol = msg.get("symbol")

            if action == "subscribe" and market and symbol:
                await subscribe(market, symbol)
            elif action == "unsubscribe" and market and symbol:
                await unsubscribe(market, symbol)
            else:
                await send_json({"error": "invalid action"})

    except WebSocketDisconnect:
        logger.info("클라이언트 연결 종료")
    finally:
        # 연결이 끊기면 이 클라이언트가 보고 있던 종목을 전부 정리
        for market, symbol in list(watched):
            await unsubscribe(market, symbol)
```

## `app/redis_hub.py`

```python
import asyncio
import json
import logging
from collections import defaultdict
from typing import Awaitable, Callable

import redis.asyncio as redis

from .config import settings

logger = logging.getLogger("redis_hub")

Callback = Callable[[dict], Awaitable[None]]


class RedisHub:
    """
    분배 서버 인스턴스 안에서 Redis 채널 구독을 최소 개수로만 유지한다.
    여러 브라우저 클라이언트가 같은 종목을 봐도 Redis 구독은 topic당 1개만
    하고, 수신 메시지를 등록된 로컬 콜백들에게 팬아웃한다.
    """

    def __init__(self):
        self._redis = redis.from_url(settings.redis_url, decode_responses=True)
        self._pubsub = self._redis.pubsub()
        self._callbacks: dict[str, set[Callback]] = defaultdict(set)
        self._listen_task: asyncio.Task | None = None

    def start(self):
        self._listen_task = asyncio.create_task(self._listen_loop())

    async def stop(self):
        if self._listen_task:
            self._listen_task.cancel()
        await self._pubsub.aclose()
        await self._redis.aclose()

    async def get_latest(self, topic: str) -> dict | None:
        raw = await self._redis.get(f"latest:{topic}")
        return json.loads(raw) if raw else None

    async def add_listener(self, topic: str, callback: Callback):
        is_new = not self._callbacks[topic]
        self._callbacks[topic].add(callback)
        if is_new:
            await self._pubsub.subscribe(topic)
            logger.info("Redis subscribe: %s", topic)

    async def remove_listener(self, topic: str, callback: Callback):
        callbacks = self._callbacks.get(topic)
        if not callbacks:
            return
        callbacks.discard(callback)
        if not callbacks:
            del self._callbacks[topic]
            await self._pubsub.unsubscribe(topic)
            logger.info("Redis unsubscribe: %s", topic)

    async def _listen_loop(self):
        async for message in self._pubsub.listen():
            if message["type"] != "message":
                continue
            topic = message["channel"]
            data = json.loads(message["data"])
            for callback in list(self._callbacks.get(topic, ())):
                try:
                    await callback(data)
                except Exception:
                    logger.exception("리스너 콜백 처리 실패: %s", topic)
```
