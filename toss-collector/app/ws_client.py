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
            settings.toss_ws_url, additional_headers=headers, ping_interval=None
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
