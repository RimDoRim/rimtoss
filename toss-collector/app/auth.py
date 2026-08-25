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
