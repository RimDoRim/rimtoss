from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    # 토스증권 WTS > 설정 > Open API 메뉴에서 발급
    toss_client_id: str
    toss_client_secret: str

    toss_oauth_url: str = "https://openapi.tossinvest.com/oauth2/token"
    toss_rest_base: str = "https://openapi.tossinvest.com"
    toss_ws_url: str = "wss://openapi-ws.tossinvest.com/ws/v1"

    redis_url: str = "redis://localhost:6379/0"

    # 문서 권장값: 180초 타임아웃, 60초 권장 간격
    ws_ping_interval_sec: int = 60
    ws_reconnect_max_backoff_sec: int = 30

    # 구독 선언 5회/초 제한 회피용 디바운스
    subscription_declare_debounce_ms: int = 150

    class Config:
        env_file = ".env"


settings = Settings()
