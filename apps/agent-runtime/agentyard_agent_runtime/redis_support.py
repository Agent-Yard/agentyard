from __future__ import annotations

import os
from dataclasses import dataclass
from urllib.parse import quote

from redis import Redis as SyncRedis
from redis.asyncio import Redis as AsyncRedis


@dataclass(frozen=True)
class RedisSettings:
    host: str
    port: int
    database: int
    username: str
    password: str
    ssl_enabled: bool

    @property
    def url(self) -> str:
        scheme = "rediss" if self.ssl_enabled else "redis"
        credentials = ""
        if self.username:
            credentials = f"{quote(self.username, safe='')}:{quote(self.password, safe='')}@"
        elif self.password:
            credentials = f":{quote(self.password, safe='')}@"
        return f"{scheme}://{credentials}{self.host}:{self.port}/{self.database}"

    @classmethod
    def from_env(cls) -> "RedisSettings":
        return cls(
            host=os.getenv("AGENTYARD_REDIS_HOST", "127.0.0.1").strip() or "127.0.0.1",
            port=int(os.getenv("AGENTYARD_REDIS_PORT", "6379")),
            database=int(os.getenv("AGENTYARD_REDIS_DATABASE", "0")),
            username=os.getenv("AGENTYARD_REDIS_USERNAME", "").strip(),
            password=os.getenv("AGENTYARD_REDIS_PASSWORD", "agentyard"),
            ssl_enabled=os.getenv("AGENTYARD_REDIS_SSL_ENABLED", "false").strip().lower() == "true",
        )


def create_redis_client(settings: RedisSettings) -> AsyncRedis:
    return AsyncRedis.from_url(
        settings.url,
        decode_responses=True,
        encoding="utf-8",
        socket_connect_timeout=3,
        socket_timeout=3,
    )


def create_sync_redis_client(settings: RedisSettings) -> SyncRedis:
    return SyncRedis.from_url(
        settings.url,
        decode_responses=True,
        encoding="utf-8",
        socket_connect_timeout=3,
        socket_timeout=3,
    )


def privacy_session_prefix() -> str:
    return (os.getenv("AGENTYARD_PRIVACY_SESSION_STORE_KEY_PREFIX") or "agentyard:privacy:session").strip() or "agentyard:privacy:session"
