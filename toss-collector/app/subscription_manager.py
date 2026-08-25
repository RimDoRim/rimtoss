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
