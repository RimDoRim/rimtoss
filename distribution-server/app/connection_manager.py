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
