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
