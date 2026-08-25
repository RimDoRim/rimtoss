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
