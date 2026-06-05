from __future__ import annotations

from threading import Lock
from urllib.parse import urlsplit

import httpx


class SharedHttpClientRegistry:
    def __init__(self) -> None:
        self._clients: dict[tuple[str, str, int], httpx.Client] = {}
        self._lock = Lock()

    def client_for_url(self, url: str) -> httpx.Client:
        key = _origin_key(url)
        with self._lock:
            client = self._clients.get(key)
            if client is None:
                client = httpx.Client()
                self._clients[key] = client
            return client

    def close_all(self) -> None:
        with self._lock:
            clients = list(self._clients.values())
            self._clients.clear()
        for client in clients:
            client.close()


_SHARED_CLIENT_REGISTRY = SharedHttpClientRegistry()


def shared_http_client_for_url(url: str) -> httpx.Client:
    return _SHARED_CLIENT_REGISTRY.client_for_url(url)


def reset_shared_http_client_registry() -> None:
    _SHARED_CLIENT_REGISTRY.close_all()


def _origin_key(url: str) -> tuple[str, str, int]:
    parsed = urlsplit(url)
    scheme = parsed.scheme.lower()
    host = (parsed.hostname or "").lower()
    port = parsed.port or _default_port_for_scheme(scheme)
    if not scheme or not host or port <= 0:
        raise ValueError(f"invalid http client url: {url}")
    return scheme, host, port


def _default_port_for_scheme(scheme: str) -> int:
    if scheme == "https":
        return 443
    if scheme == "http":
        return 80
    return -1
