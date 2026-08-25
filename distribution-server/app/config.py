from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    redis_url: str = "redis://localhost:6379/0"
    # 시세 수집 서비스(toss-collector)의 주소 - watch/unwatch 호출용
    collector_base_url: str = "http://localhost:8000"

    class Config:
        env_file = ".env"


settings = Settings()
