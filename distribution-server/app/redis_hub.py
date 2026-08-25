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
