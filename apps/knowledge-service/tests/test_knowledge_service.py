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

from fastapi import HTTPException
from fastapi.testclient import TestClient

from lynxus_knowledge_service.main import (
    Base,
    CompleteUploadRequest,
    CreateIndexSnapshotRequest,
    CreateUploadSessionRequest,
    CreateUrlImportRequest,
    IndexSnapshotRecord,
    IndexSnapshotChunkRecord,
    KnowledgeChunkRecord,
    KnowledgeDocumentRecord,
    KnowledgeFileRecord,
    KnowledgeImportJobRecord,
    ReadChunksRequest,
    RetrieveRequest,
    SessionLocal,
    UploadSessionRecord,
    app,
    build_index_snapshot,
    complete_upload,
    create_index_snapshot,
    create_upload_session,
    create_url_import,
    delete_document,
    embedding_client,
    engine,
    ensure_postgres_configuration,
    initialize_postgres_schema,
    list_documents,
    preview_document_deletion,
    retry_import_job,
    retry_index_snapshot,
    retrieval_store,
    read_chunks,
    retrieve,
    run_import_job,
    storage,
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
        with patch("lynxus_knowledge_service.main.urlopen", return_value=FakeUrlResponse(html.encode("utf-8"), "text/html")):
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
        with patch("lynxus_knowledge_service.main.urlopen", return_value=FakeUrlResponse(html.encode("utf-8"), "text/html")):
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

    def test_should_read_chunks_in_request_order_and_deduplicate(self) -> None:
        self.upload_markdown("resource-kb-read-order", "refund.md", "# 退款说明\n未发货可退款。")
        self.upload_markdown("resource-kb-read-order", "shipping.md", "# 发货说明\n48 小时内出库。")
        snapshot_id = self.create_snapshot("resource-kb-read-order", [])

        with SessionLocal() as db:
            mappings = (
                db.query(IndexSnapshotChunkRecord)
                .filter(IndexSnapshotChunkRecord.snapshot_id == snapshot_id)
                .order_by(IndexSnapshotChunkRecord.document_id.asc(), IndexSnapshotChunkRecord.chunk_id.asc())
                .all()
            )
            self.assertGreaterEqual(len(mappings), 2)
            requested_chunk_ids = [mappings[1].chunk_id, mappings[0].chunk_id, mappings[1].chunk_id]
            response = read_chunks(ReadChunksRequest(indexSnapshotId=snapshot_id, chunkIds=requested_chunk_ids), db)

        self.assertEqual([mappings[1].chunk_id, mappings[0].chunk_id], [chunk.chunkId for chunk in response.chunks])

    def test_should_filter_read_chunks_to_current_snapshot_only(self) -> None:
        self.upload_markdown("resource-kb-read-snapshot", "refund.md", "# 退款说明\n未发货可退款。")
        self.upload_markdown("resource-kb-read-snapshot", "shipping.md", "# 发货说明\n48 小时内出库。")
        with SessionLocal() as db:
            documents = list_documents("resource-kb-read-snapshot", db)
        refund_doc = next(item for item in documents if item.title == "退款说明")
        shipping_doc = next(item for item in documents if item.title == "发货说明")

        refund_snapshot_id = self.create_snapshot("resource-kb-read-snapshot", [refund_doc.id])
        shipping_snapshot_id = self.create_snapshot("resource-kb-read-snapshot", [shipping_doc.id])

        with SessionLocal() as db:
            refund_mapping = (
                db.query(IndexSnapshotChunkRecord)
                .filter(IndexSnapshotChunkRecord.snapshot_id == refund_snapshot_id)
                .first()
            )
            shipping_mapping = (
                db.query(IndexSnapshotChunkRecord)
                .filter(IndexSnapshotChunkRecord.snapshot_id == shipping_snapshot_id)
                .first()
            )
            self.assertIsNotNone(refund_mapping)
            self.assertIsNotNone(shipping_mapping)
            response = read_chunks(
                ReadChunksRequest(
                    indexSnapshotId=refund_snapshot_id,
                    chunkIds=[refund_mapping.chunk_id, shipping_mapping.chunk_id],
                ),
                db,
            )

        self.assertEqual([refund_mapping.chunk_id], [chunk.chunkId for chunk in response.chunks])

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

    def test_should_track_state_machine_from_upload_to_import_to_document_to_snapshot(self) -> None:
        markdown = "# 退款规则\n订单未发货时可以直接退款。"
        with SessionLocal() as db:
            upload = create_upload_session(CreateUploadSessionRequest(knowledgeBaseId="resource-kb-state"), db)
            self.assertEqual(upload.status, "OPEN")

            completed = complete_upload(
                CompleteUploadRequest(
                    knowledgeBaseId="resource-kb-state",
                    uploadSessionId=upload.id,
                    fileName="refund.md",
                    contentType="text/markdown",
                    contentBase64=base64.b64encode(markdown.encode("utf-8")).decode("ascii"),
                ),
                db,
            )
            file_id = completed["file"]["id"]
            import_job_id = completed["importJob"]["id"]

            upload_record = db.get(UploadSessionRecord, upload.id)
            file_record = db.get(KnowledgeFileRecord, file_id)
            import_job = db.get(KnowledgeImportJobRecord, import_job_id)
            self.assertEqual(upload_record.status, "COMPLETED")
            self.assertEqual(file_record.status, "UPLOADED")
            self.assertEqual(import_job.status, "QUEUED")
            self.assertEqual(import_job.stage, "QUEUED")
            self.assertEqual(import_job.progress_percent, 0)

        with SessionLocal() as db:
            imported = run_import_job(import_job_id, db)
            self.assertEqual(imported.status, "SUCCEEDED")
            self.assertEqual(imported.stage, "SUCCEEDED")
            self.assertEqual(imported.progressPercent, 100)

            file_record = db.get(KnowledgeFileRecord, file_id)
            self.assertEqual(file_record.status, "IMPORTED")
            self.assertIsNone(file_record.error_message)

            documents = db.query(KnowledgeDocumentRecord).filter(KnowledgeDocumentRecord.knowledge_base_id == "resource-kb-state").all()
            self.assertEqual(len(documents), 1)
            document = documents[0]
            self.assertEqual(document.file_id, file_id)
            self.assertEqual(document.status, "READY")

        with SessionLocal() as db:
            created_snapshot = create_index_snapshot(
                "resource-kb-state",
                CreateIndexSnapshotRequest(documentIds=[document.id], retrievalMode="HYBRID"),
                db,
            )
            snapshot_record = db.get(IndexSnapshotRecord, created_snapshot.id)
            self.assertEqual(snapshot_record.status, "QUEUED")
            self.assertEqual(snapshot_record.stage, "QUEUED")
            self.assertEqual(snapshot_record.progress_percent, 0)

        with SessionLocal() as db:
            built_snapshot = build_index_snapshot(created_snapshot.id, db)
            self.assertEqual(built_snapshot.status, "READY")
            self.assertEqual(built_snapshot.stage, "READY")
            self.assertEqual(built_snapshot.progressPercent, 100)
            self.assertEqual(built_snapshot.documentCount, 1)
            self.assertGreater(built_snapshot.chunkCount, 0)

            snapshot_record = db.get(IndexSnapshotRecord, created_snapshot.id)
            self.assertEqual(snapshot_record.status, "READY")
            self.assertEqual(snapshot_record.document_count, 1)
            self.assertGreater(snapshot_record.chunk_count, 0)

    def test_should_allow_failed_import_retry_to_recover_file_and_document_state(self) -> None:
        with SessionLocal() as db:
            upload = create_upload_session(CreateUploadSessionRequest(knowledgeBaseId="resource-kb-recover"), db)
            completed = complete_upload(
                CompleteUploadRequest(
                    knowledgeBaseId="resource-kb-recover",
                    uploadSessionId=upload.id,
                    fileName="empty.md",
                    contentType="text/markdown",
                    contentBase64=base64.b64encode(b"").decode("ascii"),
                ),
                db,
            )
            file_id = completed["file"]["id"]
            failed_job_id = completed["importJob"]["id"]

        with SessionLocal() as db:
            failed = run_import_job(failed_job_id, db)
            self.assertEqual(failed.status, "FAILED")
            self.assertTrue(failed.retryable)

            file_record = db.get(KnowledgeFileRecord, file_id)
            self.assertEqual(file_record.status, "FAILED")
            self.assertIn("no extractable text", file_record.error_message or "")
            self.assertEqual(
                db.query(KnowledgeDocumentRecord).filter(KnowledgeDocumentRecord.file_id == file_id).count(),
                0,
            )

        with SessionLocal() as db:
            file_record = db.get(KnowledgeFileRecord, file_id)
            file_record.object_key = f"{file_record.knowledge_base_id}/retry-refund.md"
            file_record.size_bytes = len("# 重试退款规则\n补充后可以导入。".encode("utf-8"))
            storage.put_bytes(file_record.object_key, "# 重试退款规则\n补充后可以导入。".encode("utf-8"), "text/markdown")
            db.commit()

            retry_job = retry_import_job(failed_job_id, db)
            self.assertEqual(retry_job.status, "QUEUED")
            self.assertEqual(retry_job.retryCount, 1)
            retry_job_id = retry_job.id

            file_record = db.get(KnowledgeFileRecord, file_id)
            self.assertEqual(file_record.status, "UPLOADED")
            self.assertIsNone(file_record.error_message)

        with SessionLocal() as db:
            retried = run_import_job(retry_job_id, db)
            self.assertEqual(retried.status, "SUCCEEDED")
            self.assertEqual(retried.stage, "SUCCEEDED")

            file_record = db.get(KnowledgeFileRecord, file_id)
            self.assertEqual(file_record.status, "IMPORTED")
            self.assertIsNone(file_record.error_message)

            jobs = (
                db.query(KnowledgeImportJobRecord)
                .filter(KnowledgeImportJobRecord.file_id == file_id)
                .order_by(KnowledgeImportJobRecord.created_at.asc())
                .all()
            )
            self.assertEqual([job.status for job in jobs], ["FAILED", "SUCCEEDED"])
            self.assertEqual(jobs[0].retry_count, 0)
            self.assertEqual(jobs[1].retry_count, 1)

            documents = db.query(KnowledgeDocumentRecord).filter(KnowledgeDocumentRecord.file_id == file_id).all()
            self.assertEqual(len(documents), 1)
            self.assertEqual(documents[0].status, "READY")

    def test_should_delete_imported_document_and_related_source_records_when_no_snapshot_exists(self) -> None:
        file_id = self.upload_markdown(
            "resource-kb-delete",
            "refund.md",
            "# 退款规则\n订单未发货时可以直接退款。",
        )

        with SessionLocal() as db:
            document = db.query(KnowledgeDocumentRecord).filter(KnowledgeDocumentRecord.file_id == file_id).one()
            preview = preview_document_deletion("resource-kb-delete", document.id, db)
            self.assertTrue(preview.canDelete)
            self.assertEqual(preview.chunkCount, 1)

            deleted = delete_document("resource-kb-delete", document.id, db)
            self.assertEqual(deleted.fileId, file_id)
            self.assertEqual(deleted.deletedDocumentCount, 1)
            self.assertEqual(deleted.deletedChunkCount, 1)
            self.assertEqual(deleted.deletedImportJobCount, 1)
            self.assertTrue(deleted.deletedStorageObject)

        with SessionLocal() as db:
            self.assertEqual(db.query(KnowledgeFileRecord).filter(KnowledgeFileRecord.id == file_id).count(), 0)
            self.assertEqual(db.query(KnowledgeDocumentRecord).filter(KnowledgeDocumentRecord.file_id == file_id).count(), 0)
            self.assertEqual(db.query(KnowledgeChunkRecord).count(), 0)
            self.assertEqual(db.query(KnowledgeImportJobRecord).filter(KnowledgeImportJobRecord.file_id == file_id).count(), 0)

    def test_should_block_document_deletion_when_snapshot_exists(self) -> None:
        file_id = self.upload_markdown(
            "resource-kb-delete-blocked",
            "refund.md",
            "# 退款规则\n订单未发货时可以直接退款。",
        )
        snapshot_id = self.create_snapshot("resource-kb-delete-blocked", [])

        with SessionLocal() as db:
            document = db.query(KnowledgeDocumentRecord).filter(KnowledgeDocumentRecord.file_id == file_id).one()
            preview = preview_document_deletion("resource-kb-delete-blocked", document.id, db)
            self.assertFalse(preview.canDelete)
            self.assertEqual([item.snapshotId for item in preview.blockers], [snapshot_id])
            self.assertIn("all ready documents", preview.blockers[0].reason)

            with self.assertRaises(HTTPException) as ctx:
                delete_document("resource-kb-delete-blocked", document.id, db)
            self.assertIn("referenced by snapshots", str(ctx.exception))

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

    def test_healthz_should_report_up_when_dependencies_are_ready(self) -> None:
        with TestClient(app) as client:
            response = client.get("/healthz")

        self.assertEqual(response.status_code, 200)
        payload = response.json()
        self.assertEqual(payload["status"], "UP")
        self.assertEqual(payload["service"], "lynxus-knowledge-service")
        self.assertEqual(payload["dependencies"]["database"]["status"], "UP")
        self.assertEqual(payload["dependencies"]["storage"]["status"], "UP")
        self.assertEqual(payload["embedding"]["status"], "configured")

    def test_healthz_should_return_down_and_503_when_database_probe_fails(self) -> None:
        with TestClient(app) as client:
            with patch("lynxus_knowledge_service.main.engine.connect", side_effect=RuntimeError("postgres unavailable")):
                response = client.get("/healthz")

        self.assertEqual(response.status_code, 503)
        payload = response.json()
        self.assertEqual(payload["status"], "DOWN")
        self.assertEqual(payload["dependencies"]["database"]["status"], "DOWN")
        self.assertEqual(payload["dependencies"]["database"]["detail"], "postgres unavailable")
        self.assertEqual(payload["dependencies"]["storage"]["status"], "UP")

    def test_healthz_should_not_require_internal_authentication(self) -> None:
        with TestClient(app) as client:
            response = client.get("/healthz")

        self.assertEqual(response.status_code, 200)

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
        with patch("lynxus_knowledge_service.main.DATABASE_URL", "sqlite+pysqlite:///tmp/test.db"):
            with self.assertRaises(RuntimeError) as ctx:
                ensure_postgres_configuration()
        self.assertIn("requires PostgreSQL", str(ctx.exception))

    def test_should_fail_startup_when_extension_init_errors(self) -> None:
        fake_context = MagicMock()
        fake_connection = MagicMock()
        fake_context.__enter__.return_value = fake_connection
        fake_context.__exit__.return_value = False
        fake_connection.execute.side_effect = RuntimeError("extension install failed")
        with patch("lynxus_knowledge_service.main.engine.begin", return_value=fake_context):
            with self.assertRaises(RuntimeError) as ctx:
                initialize_postgres_schema()
        self.assertIn("extension install failed", str(ctx.exception))
if __name__ == "__main__":
    unittest.main()
