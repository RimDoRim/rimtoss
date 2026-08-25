# toss-collector — 시세 수집 서비스

아래 코드 블록들을 그대로 로컬에 같은 경로/파일명으로 만들어서 붙여넣으면 됩니다.

## 폴더 구조

```
.env.example
README.md
requirements.txt
  app/
    __init__.py
    auth.py
    config.py
    main.py
    redis_publisher.py
    subscription_manager.py
    ws_client.py
```

## `.env.example`

```bash
TOSS_CLIENT_ID=your_client_id
TOSS_CLIENT_SECRET=your_client_secret
REDIS_URL=redis://localhost:6379/0
```

## `README.md`

```markdown
# 시세 수집 서비스 (toss-collector)

토스증권 Open API에서 실시간 시세(WS)와 종목/캔들 정보(REST)를 수집해
Redis로 발행하는 FastAPI 서비스 스켈레톤입니다.

## 구조

```
app/
  config.py              # 환경변수 설정
  auth.py                # OAuth2 토큰 발급/자동 갱신
  subscription_manager.py# 종목 구독 ref-count + 선언 디바운싱
  ws_client.py           # 토스 WebSocket 연결/재연결/PING
  redis_publisher.py     # Redis Pub/Sub 발행 + 최신 시세 캐싱
  main.py                # FastAPI 앱, 전체 wiring
```

## 실행

```bash
pip install -r requirements.txt --break-system-packages
cp .env.example .env   # client_id, client_secret 채우기
uvicorn app.main:app --reload
```

Redis가 로컬에 없다면:

```bash
docker run -d -p 6379:6379 redis:7
```

## 동작 흐름

1. 앱 시작 시 `SubscriptionManager`, `TossWebSocketClient`가 생성되고
   백그라운드 태스크로 WS 연결이 시작됩니다.
2. 사용자가 `POST /watch/kr/005930`을 호출하면 ref-count가 올라가고,
   150ms 디바운스 후 현재 구독 전체가 토스 WS로 재선언됩니다.
3. 토스에서 `message` 프레임이 오면 `on_toss_message` -> Redis
   `publish()`로 전달되어 `trade:kr:005930` 채널에 발행되고,
   `latest:trade:kr:005930` 키에 최신 스냅샷이 저장됩니다.
4. (다음 단계) 분배 서버가 Redis를 구독해서 프론트엔드 WebSocket
   클라이언트로 릴레이합니다.

## 아직 안 된 것 (다음 단계 후보)

- 분배 서버 (Redis 구독 -> 클라이언트 WS 브로드캐스트)
- REST 기반 캔들 히스토리 수집/저장 (Postgres・TimescaleDB)
- watch/unwatch를 실제 분배 서버의 클라이언트 접속/이탈 이벤트와 연결
- 여러 인스턴스로 수평 확장 시 WS 연결 중복 방지 (leader election 등)
- 에러/재연결 상황 Mattermost Webhook 알림 연동
```

## `requirements.txt`

```text
fastapi>=0.115
uvicorn[standard]>=0.30
httpx>=0.27
websockets>=12.0
redis>=5.0
pydantic-settings>=2.4
```

## `app/__init__.py`

```python

```

## `app/auth.py`

```python
import asyncio
import time

import httpx

from .config import settings


class TokenManager:
    """
    OAuth 2.0 Client Credentials 토큰을 발급/캐싱하고,
    만료 30초 전에 자동으로 재발급하는 싱글톤 매니저.
    """

    def __init__(self):
        self._token: str | None = None
        self._expires_at: float = 0
        self._lock = asyncio.Lock()

    async def get_token(self) -> str:
        async with self._lock:
            if self._token is None or time.time() > self._expires_at - 30:
                await self._refresh()
            return self._token

    async def _refresh(self):
        async with httpx.AsyncClient(timeout=10) as client:
            resp = await client.post(
                settings.toss_oauth_url,
                data={
                    "grant_type": "client_credentials",
                    "client_id": settings.toss_client_id,
                    "client_secret": settings.toss_client_secret,
                },
                headers={"Content-Type": "application/x-www-form-urlencoded"},
            )
            resp.raise_for_status()
            data = resp.json()
            self._token = data["access_token"]
            expires_in = data.get("expires_in", 3600)
            self._expires_at = time.time() + expires_in


# 앱 전역에서 공유하는 싱글톤 인스턴스
token_manager = TokenManager()
```

## `app/config.py`

```python
from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    # 토스증권 WTS > 설정 > Open API 메뉴에서 발급
    toss_client_id: str
    toss_client_secret: str

    toss_oauth_url: str = "https://openapi.tossinvest.com/oauth2/token"
    toss_rest_base: str = "https://openapi.tossinvest.com"
    toss_ws_url: str = "wss://openapi-ws.tossinvest.com/ws/v1"

    redis_url: str = "redis://localhost:6379/0"

    # 문서 권장값: 180초 타임아웃, 60초 권장 간격
    ws_ping_interval_sec: int = 60
    ws_reconnect_max_backoff_sec: int = 30

    # 구독 선언 5회/초 제한 회피용 디바운스
    subscription_declare_debounce_ms: int = 150

    class Config:
        env_file = ".env"


settings = Settings()
```

## `app/main.py`

```python
import asyncio
import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI

from .config import settings
from .redis_publisher import RedisPublisher
from .subscription_manager import SubscriptionManager
from .ws_client import TossWebSocketClient

logging.basicConfig(level=logging.INFO)

redis_publisher = RedisPublisher()
sub_manager: SubscriptionManager | None = None
ws_client: TossWebSocketClient | None = None
_ws_task: asyncio.Task | None = None


async def on_toss_message(topic: str, data: dict):
    await redis_publisher.publish(topic, data)


@asynccontextmanager
async def lifespan(app: FastAPI):
    global sub_manager, ws_client, _ws_task

    async def declare_fn(declaration: list[dict]):
        await ws_client.declare(declaration)

    sub_manager = SubscriptionManager(
        declare_fn, settings.subscription_declare_debounce_ms
    )
    ws_client = TossWebSocketClient(on_toss_message, sub_manager)
    sub_manager.start()
    _ws_task = asyncio.create_task(ws_client.run())

    yield

    ws_client.stop()
    if _ws_task:
        _ws_task.cancel()
    await redis_publisher.close()


app = FastAPI(title="시세 수집 서비스", lifespan=lifespan)


# --- 구독 등록/해제 엔드포인트 --------------------------------------------
# 실제 서비스에서는 분배 서버(사용자 WS 연결)가 클라이언트 접속/이탈 시
# 이 엔드포인트를 내부 호출하거나, 같은 프로세스라면 함수를 직접 호출하는
# 형태로 연결하면 됩니다. 여기서는 최소 스켈레톤으로 REST 엔드포인트만 둡니다.

@app.post("/watch/{market}/{symbol}")
async def watch_symbol(market: str, symbol: str):
    """사용자가 종목 상세 화면을 열 때 호출 (예: market=kr, symbol=005930)"""
    await sub_manager.watch(f"trade:{market}", symbol)
    await sub_manager.watch(f"orderbook:{market}", symbol)
    return {"status": "ok"}


@app.post("/unwatch/{market}/{symbol}")
async def unwatch_symbol(market: str, symbol: str):
    """사용자가 종목 상세 화면을 나갈 때 호출"""
    await sub_manager.unwatch(f"trade:{market}", symbol)
    await sub_manager.unwatch(f"orderbook:{market}", symbol)
    return {"status": "ok"}


@app.get("/health")
async def health():
    return {"status": "ok"}
```

## `app/redis_publisher.py`

```python
import json

import redis.asyncio as redis

from .config import settings


class RedisPublisher:
    """
    수집한 시세를 두 가지 방식으로 저장한다:
      1) Pub/Sub 채널로 발행 -> 분배 서버가 구독해서 프론트로 릴레이
      2) Hash에 최신 스냅샷 캐싱 -> 새로 접속한 클라이언트에게 초기값 제공
         (WS는 델타/이벤트만 오므로 최초 접속 시 스냅샷이 필요)
    """

    def __init__(self):
        self._redis = redis.from_url(settings.redis_url, decode_responses=True)

    async def publish(self, topic: str, data: dict):
        # topic 예시: "trade:kr:005930", "orderbook:kr:005930"
        payload = json.dumps(data, ensure_ascii=False)
        await self._redis.publish(topic, payload)
        await self._redis.set(f"latest:{topic}", payload)

    async def get_latest(self, topic: str) -> dict | None:
        raw = await self._redis.get(f"latest:{topic}")
        return json.loads(raw) if raw else None

    async def close(self):
        await self._redis.aclose()
```

## `app/subscription_manager.py`

```python
import asyncio
from collections import defaultdict
from typing import Awaitable, Callable


class SubscriptionManager:
    """
    토스 WS 구독은 '선언형 full-replace' 방식이라, 배열 하나가 곧 전체 구독이다.
    이 매니저는:
      1) 여러 사용자가 같은 종목을 볼 때 ref-count로 관리해서 마지막 사용자가
         나갈 때만 실제로 구독을 해제하고,
      2) 사용자가 화면을 빠르게 넘겨도(초당 5회 선언 제한을 넘지 않도록)
         변경사항을 모아서 한 번만 재선언한다.
    """

    def __init__(
        self,
        declare_fn: Callable[[list[dict]], Awaitable[None]],
        debounce_ms: int = 150,
    ):
        # key 예시: "trade:kr:005930", "personal:order:3"
        self._refcounts: dict[str, int] = defaultdict(int)
        self._declare_fn = declare_fn
        self._debounce_ms = debounce_ms
        self._dirty = asyncio.Event()
        self._lock = asyncio.Lock()
        self._task: asyncio.Task | None = None

    def start(self):
        self._task = asyncio.create_task(self._debounce_loop())

    async def watch(self, channel_type: str, code: str):
        key = f"{channel_type}:{code}"
        async with self._lock:
            self._refcounts[key] += 1
            self._dirty.set()

    async def unwatch(self, channel_type: str, code: str):
        key = f"{channel_type}:{code}"
        async with self._lock:
            if key in self._refcounts:
                self._refcounts[key] -= 1
                if self._refcounts[key] <= 0:
                    del self._refcounts[key]
            self._dirty.set()

    def current_declaration(self) -> list[dict]:
        """현재 살아있는 구독 전체를 토스 WS 선언 포맷으로 변환."""
        grouped: dict[str, list[str]] = defaultdict(list)
        for key in self._refcounts:
            # "trade:kr:005930" -> type="trade:kr", code="005930"
            # "personal:order:3" -> type="personal:order", code="3"
            channel_type, code = key.rsplit(":", 1)
            grouped[channel_type].append(code)
        return [{"type": t, "codes": codes} for t, codes in grouped.items() if codes]

    async def _debounce_loop(self):
        while True:
            await self._dirty.wait()
            self._dirty.clear()
            await asyncio.sleep(self._debounce_ms / 1000)
            # 대기 중 추가로 들어온 변경사항까지 한 번에 반영
            self._dirty.clear()
            await self._declare_fn(self.current_declaration())
```

## `app/ws_client.py`

```python
import asyncio
import json
import logging
import random
from typing import Awaitable, Callable

import websockets
from websockets.exceptions import ConnectionClosed

from .auth import token_manager
from .config import settings
from .subscription_manager import SubscriptionManager

logger = logging.getLogger("toss_ws")


class TossWebSocketClient:
    """
    wss://openapi-ws.tossinvest.com/ws/v1 단일 연결을 유지하며:
      - 연결/재연결 시 현재 구독 상태를 재선언
      - 60초 간격 PING (180초 타임아웃 방지)
      - message / subscriptions / error / pong 프레임 분기 처리
      - 지수 백오프 재연결 (1s -> 2s -> 4s ... 최대 30s)
    """

    def __init__(
        self,
        on_message: Callable[[str, dict], Awaitable[None]],
        subscription_manager: SubscriptionManager,
    ):
        self._on_message = on_message
        self._sub_manager = subscription_manager
        self._ws = None
        self._running = False
        self._req_id_counter = 0

    async def run(self):
        self._running = True
        backoff = 1
        while self._running:
            try:
                await self._connect_and_listen()
                backoff = 1
            except (ConnectionClosed, OSError, asyncio.TimeoutError) as e:
                logger.warning("Toss WS 연결 끊김: %s, %s초 후 재연결", e, backoff)
                await asyncio.sleep(backoff + random.random())
                backoff = min(backoff * 2, settings.ws_reconnect_max_backoff_sec)

    def stop(self):
        self._running = False

    async def _connect_and_listen(self):
        token = await token_manager.get_token()
        headers = {"Authorization": f"Bearer {token}"}

        async with websockets.connect(
            settings.toss_ws_url, extra_headers=headers, ping_interval=None
        ) as ws:
            self._ws = ws
            logger.info("Toss WS connected")

            # 재연결 시 기존 구독 상태를 그대로 재선언
            declaration = self._sub_manager.current_declaration()
            if declaration:
                await self.declare(declaration)

            ping_task = asyncio.create_task(self._ping_loop())
            try:
                async for raw in ws:
                    await self._handle_frame(json.loads(raw))
            finally:
                ping_task.cancel()
                self._ws = None

    async def declare(self, declaration: list[dict]):
        """구독 선언(full-replace)을 전송. 빈 리스트를 보내면 전체 해제."""
        if self._ws is None:
            return
        self._req_id_counter += 1
        payload = [{"id": f"req-{self._req_id_counter}"}] + declaration
        await self._ws.send(json.dumps(payload))

    async def _ping_loop(self):
        while True:
            await asyncio.sleep(settings.ws_ping_interval_sec)
            if self._ws is not None:
                await self._ws.send("PING")

    async def _handle_frame(self, frame: dict):
        frame_type = frame.get("type")

        if frame_type == "message":
            await self._on_message(frame["topic"], frame["data"])

        elif frame_type == "subscriptions":
            rejected = frame.get("rejected")
            if rejected:
                logger.warning("구독 거부: %s", rejected)
            logger.info("구독 확정: %s", frame.get("subscribed"))

        elif frame_type == "error":
            # 선언 전체 실패 또는 server-shutdown
            logger.error("WS error 프레임: %s", frame.get("error"))

        elif frame_type == "pong":
            pass
```
