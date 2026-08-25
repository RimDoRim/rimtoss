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
