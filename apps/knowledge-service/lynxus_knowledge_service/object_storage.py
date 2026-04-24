from __future__ import annotations

import io
import os
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Protocol
from urllib.parse import urlparse

from minio import Minio


class ObjectStorageClient(Protocol):
    def bucket_exists(self, bucket_name: str) -> bool: ...

    def make_bucket(self, bucket_name: str) -> None: ...

    def put_object(
        self,
        bucket_name: str,
        object_name: str,
        data,
        length: int,
        content_type: str,
    ) -> object: ...

    def get_object(self, bucket_name: str, object_name: str): ...

    def remove_object(self, bucket_name: str, object_name: str) -> None: ...


ObjectStorageClientFactory = Callable[..., ObjectStorageClient]


@dataclass(frozen=True)
class ObjectStorageSettings:
    mode: str
    storage_root: Path
    provider: str
    endpoint: str
    endpoint_host: str
    secure: bool
    region: str
    access_key: str
    secret_key: str
    create_bucket: bool
    bucket: str


def load_storage_settings(default_storage_root: Path) -> ObjectStorageSettings:
    mode = os.getenv("LYNXUS_OBJECT_STORAGE_MODE", "filesystem").strip().lower()
    if mode not in {"filesystem", "object-storage"}:
        raise ValueError(f"unsupported object storage mode: {mode}")

    storage_root = Path(os.getenv("LYNXUS_OBJECT_STORAGE_ROOT", str(default_storage_root)))
    provider = os.getenv("LYNXUS_OBJECT_STORAGE_PROVIDER", "minio").strip().lower()
    if provider not in {"minio", "s3"}:
        raise ValueError(f"unsupported object storage provider: {provider}")

    endpoint = os.getenv("LYNXUS_OBJECT_STORAGE_ENDPOINT", "").strip()
    if not endpoint and provider == "minio":
        endpoint = "http://127.0.0.1:9000"
    if mode == "object-storage" and not endpoint:
        raise ValueError("LYNXUS_OBJECT_STORAGE_ENDPOINT is required when object storage is enabled")
    endpoint_host, secure = _parse_endpoint(endpoint) if endpoint else ("", False)

    region = os.getenv("LYNXUS_OBJECT_STORAGE_REGION", "").strip()
    if mode == "object-storage" and provider == "s3" and not region:
        raise ValueError("LYNXUS_OBJECT_STORAGE_REGION is required when object storage provider is s3")

    access_key = os.getenv("LYNXUS_OBJECT_STORAGE_ACCESS_KEY", "").strip()
    secret_key = os.getenv("LYNXUS_OBJECT_STORAGE_SECRET_KEY", "").strip()
    bucket = os.getenv("LYNXUS_OBJECT_STORAGE_BUCKET", "lynxus-knowledge").strip()
    if mode == "object-storage" and (not access_key or not secret_key):
        raise ValueError(
            "LYNXUS_OBJECT_STORAGE_ACCESS_KEY and LYNXUS_OBJECT_STORAGE_SECRET_KEY are required when object storage is enabled"
        )
    if mode == "object-storage" and not bucket:
        raise ValueError("LYNXUS_OBJECT_STORAGE_BUCKET is required when object storage is enabled")

    default_create_bucket = "true" if provider == "minio" else "false"
    return ObjectStorageSettings(
        mode=mode,
        storage_root=storage_root,
        provider=provider,
        endpoint=endpoint,
        endpoint_host=endpoint_host,
        secure=secure,
        region=region,
        access_key=access_key,
        secret_key=secret_key,
        create_bucket=_env_bool("LYNXUS_OBJECT_STORAGE_CREATE_BUCKET", default_create_bucket),
        bucket=bucket,
    )


class Storage:
    def __init__(
        self,
        settings: ObjectStorageSettings,
        client_factory: ObjectStorageClientFactory = Minio,
    ) -> None:
        self.settings = settings
        self.client: ObjectStorageClient | None = None
        if settings.mode == "filesystem":
            settings.storage_root.mkdir(parents=True, exist_ok=True)
            return

        self.client = client_factory(
            settings.endpoint_host,
            access_key=settings.access_key,
            secret_key=settings.secret_key,
            secure=settings.secure,
            region=settings.region or None,
        )
        if settings.create_bucket:
            self.ensure_bucket()

    def ensure_bucket(self) -> None:
        if self.client is None:
            return
        if not self.client.bucket_exists(self.settings.bucket):
            self.client.make_bucket(self.settings.bucket)

    def check(self) -> dict[str, object]:
        if self.client is None:
            if not self.settings.storage_root.exists() or not self.settings.storage_root.is_dir():
                raise RuntimeError(f"storage root is not available: {self.settings.storage_root}")
            return {"mode": self.settings.mode, "root": str(self.settings.storage_root)}
        if not self.client.bucket_exists(self.settings.bucket):
            raise RuntimeError(f"object storage bucket is not available: {self.settings.bucket}")
        return {
            "mode": self.settings.mode,
            "provider": self.settings.provider,
            "bucket": self.settings.bucket,
            "endpoint": self.settings.endpoint,
        }

    def put_bytes(self, object_key: str, payload: bytes, content_type: str) -> None:
        if self.client is None:
            target = self.settings.storage_root / object_key
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(payload)
            return
        stream = io.BytesIO(payload)
        self.client.put_object(
            self.settings.bucket,
            object_key,
            stream,
            length=len(payload),
            content_type=content_type or "application/octet-stream",
        )

    def get_bytes(self, object_key: str) -> bytes:
        if self.client is None:
            return (self.settings.storage_root / object_key).read_bytes()
        response = self.client.get_object(self.settings.bucket, object_key)
        try:
            return response.read()
        finally:
            response.close()
            response.release_conn()

    def delete_object(self, object_key: str) -> bool:
        if not object_key:
            return False
        if self.client is None:
            target = self.settings.storage_root / object_key
            if not target.exists():
                return False
            target.unlink()
            return True
        try:
            self.client.remove_object(self.settings.bucket, object_key)
            return True
        except Exception:
            return False


def _parse_endpoint(endpoint: str) -> tuple[str, bool]:
    parsed = urlparse(endpoint if "://" in endpoint else f"http://{endpoint}")
    if parsed.scheme not in {"http", "https"} or not parsed.netloc:
        raise ValueError(f"invalid object storage endpoint: {endpoint}")
    if parsed.path not in {"", "/"} or parsed.params or parsed.query or parsed.fragment:
        raise ValueError(f"object storage endpoint must not include path, query, or fragment: {endpoint}")
    return parsed.netloc, parsed.scheme == "https"


def _env_bool(name: str, default: str) -> bool:
    raw = os.getenv(name, default).strip().lower()
    if raw in {"1", "true", "yes", "y", "on"}:
        return True
    if raw in {"0", "false", "no", "n", "off"}:
        return False
    raise ValueError(f"{name} must be a boolean value")
