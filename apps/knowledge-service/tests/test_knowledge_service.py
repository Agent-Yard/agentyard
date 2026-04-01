import base64
import os
import shutil
import socket
import subprocess
import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import MagicMock, patch

import psycopg


def find_free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.bind(("127.0.0.1", 0))
        return int(sock.getsockname()[1])


POSTGRES_PORT = find_free_port()
POSTGRES_CONTAINER_NAME = f"lynxus-knowledge-test-{os.getpid()}"
temp_root = tempfile.mkdtemp(prefix="lynxus-knowledge-test-")
TEST_DATABASE_URL = os.environ.get(
    "LYNXUS_KNOWLEDGE_TEST_DATABASE_URL",
    f"postgresql+psycopg://lynxus:lynxus@127.0.0.1:{POSTGRES_PORT}/lynxus_knowledge",
)
os.environ["LYNXUS_KNOWLEDGE_DATABASE_URL"] = TEST_DATABASE_URL
os.environ["LYNXUS_KNOWLEDGE_STORAGE_MODE"] = "filesystem"
os.environ["LYNXUS_KNOWLEDGE_STORAGE_ROOT"] = temp_root
os.environ["LYNXUS_KNOWLEDGE_EMBEDDING_BASE_URL"] = "http://embedding.test/v1"
os.environ["LYNXUS_KNOWLEDGE_EMBEDDING_MODEL"] = "test-embedding-model"
os.environ["LYNXUS_KNOWLEDGE_EMBEDDING_API_KEY"] = "test-embedding-key"
os.environ["LYNXUS_KNOWLEDGE_EMBEDDING_DIMENSIONS"] = "4"
os.environ["LYNXUS_KNOWLEDGE_EMBEDDING_BATCH_SIZE"] = "8"
os.environ["LYNXUS_INTERNAL_AUTH_TOKEN"] = "test-internal-token"

from fastapi.testclient import TestClient

from app.main import (
    Base,
    CompleteUploadRequest,
    CreateIndexSnapshotRequest,
    CreateUploadSessionRequest,
    CreateUrlImportRequest,
    IndexSnapshotRecord,
    KnowledgeDocumentRecord,
    KnowledgeFileRecord,
    KnowledgeImportJobRecord,
    RetrieveRequest,
    SessionLocal,
    UploadSessionRecord,
    app,
    build_index_snapshot,
    complete_upload,
    create_index_snapshot,
    create_upload_session,
    create_url_import,
    embedding_client,
    engine,
    ensure_postgres_configuration,
    initialize_postgres_schema,
    list_documents,
    retry_import_job,
    retry_index_snapshot,
    retrieval_store,
    retrieve,
    run_import_job,
    startup,
)


class FakeUrlResponse:
    def __init__(self, payload: bytes, content_type: str) -> None:
        self._payload = payload
        self.headers = {"Content-Type": content_type}

    def read(self) -> bytes:
        return self._payload

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc, tb):
        return False


class KnowledgeServiceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.owns_container = "LYNXUS_KNOWLEDGE_TEST_DATABASE_URL" not in os.environ
        if cls.owns_container:
            if shutil.which("docker") is None:
                raise unittest.SkipTest("docker is required for pgvector integration tests")
            subprocess.run(
                [
                    "docker",
                    "rm",
                    "-f",
                    POSTGRES_CONTAINER_NAME,
                ],
                check=False,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )
            try:
                subprocess.run(
                    [
                        "docker",
                        "run",
                        "--name",
                        POSTGRES_CONTAINER_NAME,
                        "-e",
                        "POSTGRES_DB=lynxus_knowledge",
                        "-e",
                        "POSTGRES_USER=lynxus",
                        "-e",
                        "POSTGRES_PASSWORD=lynxus",
                        "-p",
                        f"{POSTGRES_PORT}:5432",
                        "-d",
                        "pgvector/pgvector:pg17",
                    ],
                    check=True,
                    stdout=subprocess.DEVNULL,
                    stderr=subprocess.DEVNULL,
                )
            except subprocess.CalledProcessError as exc:  # pragma: no cover
                raise unittest.SkipTest(f"unable to start pgvector test database: {exc}") from exc
        deadline = time.time() + 30
        last_error: Exception | None = None
        while time.time() < deadline:
            try:
                with psycopg.connect(TEST_DATABASE_URL.replace("+psycopg", ""), connect_timeout=2) as connection:
                    with connection.cursor() as cursor:
                        cursor.execute("select 1")
                    break
            except Exception as exc:  # pragma: no cover
                last_error = exc
                time.sleep(1)
        else:  # pragma: no cover
            raise RuntimeError(f"postgres did not start in time: {last_error}")
        startup()

    @classmethod
    def tearDownClass(cls) -> None:
        if getattr(cls, "owns_container", False):
            subprocess.run(
                ["docker", "rm", "-f", POSTGRES_CONTAINER_NAME],
                check=False,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )

    def setUp(self) -> None:
        with engine.begin() as connection:
            Base.metadata.drop_all(bind=connection)
        startup()
        self.embedding_patcher = patch.object(embedding_client, "embed_texts", side_effect=self.fake_embed_texts)
        self.embedding_patcher.start()

    def tearDown(self) -> None:
        self.embedding_patcher.stop()

    @staticmethod
    def fake_embed_texts(texts: list[str]) -> list[list[float]]:
        vectors: list[list[float]] = []
        for text in texts:
            normalized = text.lower()
            if "退款" in text or "refund" in normalized:
                vectors.append([1.0, 0.0, 0.0, 0.0])
            elif "发货" in text or "shipping" in normalized:
                vectors.append([0.0, 1.0, 0.0, 0.0])
            elif "支付" in text or "payment" in normalized:
                vectors.append([0.0, 0.0, 1.0, 0.0])
            else:
                vectors.append([0.0, 0.0, 0.0, 1.0])
        return vectors

    def create_snapshot(self, knowledge_base_id: str, document_ids: list[str], retrieval_mode: str = "HYBRID") -> str:
        with SessionLocal() as db:
            snapshot = create_index_snapshot(
                knowledge_base_id,
                CreateIndexSnapshotRequest(documentIds=document_ids, retrievalMode=retrieval_mode),
                db,
            )
        snapshot_id = snapshot.id
        with SessionLocal() as db:
            built = build_index_snapshot(snapshot_id, db)
        self.assertEqual(built.status, "READY")
        self.assertEqual(built.retrievalBackend, "PGVECTOR")
        return snapshot_id

    def upload_markdown(self, knowledge_base_id: str, file_name: str, markdown: str) -> str:
        with SessionLocal() as db:
            upload = create_upload_session(CreateUploadSessionRequest(knowledgeBaseId=knowledge_base_id), db)
            completed = complete_upload(
                CompleteUploadRequest(
                    knowledgeBaseId=knowledge_base_id,
                    uploadSessionId=upload.id,
                    fileName=file_name,
                    contentType="text/markdown",
                    contentBase64=base64.b64encode(markdown.encode("utf-8")).decode("ascii"),
                ),
                db,
            )
            import_job_id = completed["importJob"]["id"]
        with SessionLocal() as db:
            imported = run_import_job(import_job_id, db)
        self.assertEqual(imported.status, "SUCCEEDED")
        self.assertEqual(imported.stage, "SUCCEEDED")
        self.assertEqual(imported.progressPercent, 100)
        return completed["file"]["id"]

    def test_should_upload_import_build_and_hybrid_retrieve_markdown_file(self) -> None:
        self.upload_markdown(
            "resource-kb-demo",
            "policy.md",
            "# 退款规则\n订单未发货时可以直接退款。\n\n# 升级规则\n出现投诉需要人工升级。",
        )

        with SessionLocal() as db:
            documents = list_documents("resource-kb-demo", db)
        self.assertEqual(len(documents), 1)

        snapshot_id = self.create_snapshot("resource-kb-demo", [])
        with SessionLocal() as db:
            payload = retrieve(
                RetrieveRequest(
                    indexSnapshotId=snapshot_id,
                    query="订单未发货可以退款吗",
                    topK=3,
                    minScore=0.1,
                    retrievalMode="HYBRID",
                ),
                db,
            ).model_dump(mode="json")
        self.assertFalse(payload["lowConfidence"])
        self.assertTrue(payload["hits"])
        self.assertEqual(payload["hits"][0]["documentTitle"], "退款规则")
        self.assertIn("退款", payload["hits"][0]["snippet"])

    def test_should_support_url_import_and_vector_retrieve_html_content(self) -> None:
        html = """
        <html>
          <head><title>帮助中心 - 支付失败</title></head>
          <body>
            <h1>支付失败</h1>
            <p>银行卡余额不足时会导致支付失败。</p>
            <h2>处理建议</h2>
            <p>建议用户更换支付方式后重试。</p>
          </body>
        </html>
        """.strip()
        with patch("app.main.urlopen", return_value=FakeUrlResponse(html.encode("utf-8"), "text/html")):
            with SessionLocal() as db:
                created = create_url_import(
                    CreateUrlImportRequest(
                        knowledgeBaseId="resource-kb-web",
                        url="https://help.example.com/payment-failed",
                        title="支付失败专题",
                    ),
                    db,
                )
        import_job_id = created["importJob"]["id"]
        with patch("app.main.urlopen", return_value=FakeUrlResponse(html.encode("utf-8"), "text/html")):
            with SessionLocal() as db:
                imported = run_import_job(import_job_id, db)
        self.assertEqual(imported.status, "SUCCEEDED")
        self.assertEqual(imported.sourceType, "URL")

        snapshot_id = self.create_snapshot("resource-kb-web", [], retrieval_mode="VECTOR")
        with SessionLocal() as db:
            retrieval = retrieve(
                RetrieveRequest(
                    indexSnapshotId=snapshot_id,
                    query="支付失败怎么办",
                    topK=2,
                    minScore=0.1,
                    retrievalMode="VECTOR",
                ),
                db,
            )
        self.assertFalse(retrieval.lowConfidence)
        self.assertEqual(retrieval.hits[0].documentTitle, "帮助中心 - 支付失败")
        self.assertIn("payment-failed", retrieval.hits[0].sourceUri)

    def test_should_use_lexical_mode_for_shipping_query(self) -> None:
        self.upload_markdown("resource-kb-lexical", "shipping.md", "# 发货说明\n48 小时内出库。")
        snapshot_id = self.create_snapshot("resource-kb-lexical", [], retrieval_mode="LEXICAL")
        with SessionLocal() as db:
            retrieval = retrieve(
                RetrieveRequest(
                    indexSnapshotId=snapshot_id,
                    query="什么时候出库",
                    topK=2,
                    minScore=0.1,
                    retrievalMode="LEXICAL",
                ),
                db,
            )
        self.assertFalse(retrieval.lowConfidence)
        self.assertIn("出库", retrieval.hits[0].snippet)

    def test_should_isolate_snapshot_by_selected_documents(self) -> None:
        self.upload_markdown("resource-kb-split", "refund.md", "# 退款说明\n未发货可退款。")
        self.upload_markdown("resource-kb-split", "shipping.md", "# 发货说明\n48 小时内出库。")
        with SessionLocal() as db:
            documents = list_documents("resource-kb-split", db)
        refund_doc = next(item for item in documents if item.title == "退款说明")

        snapshot_id = self.create_snapshot("resource-kb-split", [refund_doc.id])
        with SessionLocal() as db:
            retrieval = retrieve(
                RetrieveRequest(
                    indexSnapshotId=snapshot_id,
                    query="什么时候出库",
                    topK=3,
                    minScore=0.1,
                    retrievalMode="HYBRID",
                ),
                db,
            )
        self.assertTrue(retrieval.lowConfidence)
        self.assertEqual(retrieval.hits, [])

    def test_should_fail_snapshot_build_when_embedding_configuration_changes(self) -> None:
        self.upload_markdown("resource-kb-config", "refund.md", "# 退款说明\n未发货可退款。")
        with SessionLocal() as db:
            snapshot = create_index_snapshot(
                "resource-kb-config",
                CreateIndexSnapshotRequest(documentIds=[], retrievalMode="HYBRID"),
                db,
            )
        original_model = retrieval_store.embedding_model
        retrieval_store.embedding_model = "other-embedding-model"
        try:
            with SessionLocal() as db:
                failed = build_index_snapshot(snapshot.id, db)
        finally:
            retrieval_store.embedding_model = original_model
        self.assertEqual(failed.status, "FAILED")
        self.assertTrue(failed.retryable)
        self.assertIn("different embedding configuration", failed.failureReason or "")

    def test_should_fail_import_for_unsupported_file_type(self) -> None:
        with SessionLocal() as db:
            upload = create_upload_session(CreateUploadSessionRequest(knowledgeBaseId="resource-kb-demo"), db)
            with self.assertRaises(Exception) as ctx:
                complete_upload(
                    CompleteUploadRequest(
                        knowledgeBaseId="resource-kb-demo",
                        uploadSessionId=upload.id,
                        fileName="binary.exe",
                        contentType="application/octet-stream",
                        contentBase64=base64.b64encode(b"noop").decode("ascii"),
                    ),
                    db,
                )
        self.assertIn("unsupported file type", str(ctx.exception))

    def test_should_retry_failed_import_job_with_new_attempt(self) -> None:
        with SessionLocal() as db:
            upload = create_upload_session(CreateUploadSessionRequest(knowledgeBaseId="resource-kb-retry"), db)
            completed = complete_upload(
                CompleteUploadRequest(
                    knowledgeBaseId="resource-kb-retry",
                    uploadSessionId=upload.id,
                    fileName="empty.md",
                    contentType="text/markdown",
                    contentBase64=base64.b64encode(b"").decode("ascii"),
                ),
                db,
            )
        with SessionLocal() as db:
            failed = run_import_job(completed["importJob"]["id"], db)
        self.assertEqual(failed.status, "FAILED")
        self.assertTrue(failed.retryable)
        self.assertEqual(failed.stage, "FAILED")

        with SessionLocal() as db:
            retried = retry_import_job(completed["importJob"]["id"], db)
        self.assertEqual(retried.status, "QUEUED")
        self.assertEqual(retried.retryCount, 1)
        self.assertEqual(retried.progressPercent, 0)

        with SessionLocal() as db:
            job_count = db.query(KnowledgeImportJobRecord).filter(KnowledgeImportJobRecord.knowledge_base_id == "resource-kb-retry").count()
        self.assertEqual(job_count, 2)

    def test_should_require_internal_token_for_http_endpoints(self) -> None:
        with TestClient(app) as client:
            missing = client.post("/internal/upload-sessions", json={"knowledgeBaseId": "resource-kb-auth"})
            invalid = client.post(
                "/internal/upload-sessions",
                json={"knowledgeBaseId": "resource-kb-auth"},
                headers={"Authorization": "Bearer wrong-token"},
            )
            valid = client.post(
                "/internal/upload-sessions",
                json={"knowledgeBaseId": "resource-kb-auth"},
                headers={
                    "Authorization": "Bearer test-internal-token",
                    "traceparent": "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01",
                },
            )

        self.assertEqual(missing.status_code, 401)
        self.assertEqual(invalid.status_code, 401)
        self.assertEqual(valid.status_code, 200)
        self.assertTrue(valid.headers["traceparent"].startswith("00-0123456789abcdef0123456789abcdef-"))

    def test_should_retry_failed_snapshot_with_new_attempt(self) -> None:
        with SessionLocal() as db:
            created = create_index_snapshot(
                "resource-kb-snapshot-retry",
                CreateIndexSnapshotRequest(documentIds=[], retrievalMode="HYBRID"),
                db,
            )
        with SessionLocal() as db:
            failed = build_index_snapshot(created.id, db)
        self.assertEqual(failed.status, "FAILED")
        self.assertTrue(failed.retryable)
        self.assertEqual(failed.stage, "FAILED")

        with SessionLocal() as db:
            retried = retry_index_snapshot(created.id, db)
        self.assertEqual(retried.status, "QUEUED")
        self.assertEqual(retried.retryCount, 1)
        self.assertEqual(retried.progressPercent, 0)

    def test_startup_should_only_initialize_schema(self) -> None:
        startup()
        with SessionLocal() as db:
            session_count = db.query(UploadSessionRecord).count()
            file_count = db.query(KnowledgeFileRecord).count()
            document_count = db.query(KnowledgeDocumentRecord).count()
            snapshot_count = db.query(IndexSnapshotRecord).count()
        self.assertEqual(session_count, 0)
        self.assertEqual(file_count, 0)
        self.assertEqual(document_count, 0)
        self.assertEqual(snapshot_count, 0)

    def test_should_fail_fast_when_database_is_not_postgresql(self) -> None:
        with patch("app.main.DATABASE_URL", "sqlite+pysqlite:///tmp/test.db"):
            with self.assertRaises(RuntimeError) as ctx:
                ensure_postgres_configuration()
        self.assertIn("requires PostgreSQL", str(ctx.exception))

    def test_should_fail_startup_when_extension_init_errors(self) -> None:
        fake_context = MagicMock()
        fake_connection = MagicMock()
        fake_context.__enter__.return_value = fake_connection
        fake_context.__exit__.return_value = False
        fake_connection.execute.side_effect = RuntimeError("extension install failed")
        with patch("app.main.engine.begin", return_value=fake_context):
            with self.assertRaises(RuntimeError) as ctx:
                initialize_postgres_schema()
        self.assertIn("extension install failed", str(ctx.exception))
if __name__ == "__main__":
    unittest.main()
