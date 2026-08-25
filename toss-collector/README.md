# 시세 수집 서비스 (toss-collector)

토스증권 Open API에서 실시간 시세(WS)와 종목/캔들 정보(REST)를 수집해
Redis로 발행하는 FastAPI 서비스입니다.

## 실행

```bash
pip install -r requirements.txt
copy .env.example .env   REM client_id, client_secret 채우기
uvicorn app.main:app --reload
```

Redis가 로컬에 없다면:

```bash
docker run -d -p 6379:6379 redis:7
```
