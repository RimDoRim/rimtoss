# 분배 서버 (distribution-server)

Redis에 쌓인 시세를 구독해서, 브라우저 클라이언트의 WebSocket 연결로
실시간 릴레이하는 FastAPI 서비스입니다. `toss-collector`가 수집한
데이터를 실제로 화면까지 전달하는 마지막 구간.

## 실행

```bash
pip install -r requirements.txt
copy .env.example .env
uvicorn app.main:app --reload --port 8100
```

`toss-collector`(포트 8000)와 Redis가 먼저 떠 있어야 합니다.
