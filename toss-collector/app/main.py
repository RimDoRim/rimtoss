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
