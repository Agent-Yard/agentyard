import base64
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

temp_root = tempfile.mkdtemp(prefix="lynxus-knowledge-test-")
os.environ["LYNXUS_KNOWLEDGE_DATABASE_URL"] = f"sqlite+pysqlite:///{Path(temp_root) / 'knowledge-test.db'}"
os.environ["LYNXUS_KNOWLEDGE_STORAGE_MODE"] = "filesystem"
os.environ["LYNXUS_KNOWLEDGE_STORAGE_ROOT"] = temp_root
os.environ["LYNXUS_OPENSEARCH_URL"] = "http://opensearch.test"

from app.main import (
    Base,
    CompleteUploadRequest,
    CreateIndexSnapshotRequest,
    CreateUploadSessionRequest,
    CreateUrlImportRequest,
    IndexSnapshotRecord,
    KnowledgeDocumentRecord,
    KnowledgeFileRecord,
    RetrieveRequest,
    UploadSessionRecord,
    SessionLocal,
    app,
    build_index_snapshot,
    complete_upload,
    create_index_snapshot,
    create_upload_session,
    create_url_import,
    engine,
    list_documents,
    opensearch,
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
        Base.metadata.create_all(bind=engine)

    def setUp(self) -> None:
        Base.metadata.drop_all(bind=engine)
        Base.metadata.create_all(bind=engine)
        self.indexed_chunk_ids: dict[str, list[str]] = {}
        self.bulk_patcher = patch.object(opensearch, "bulk_index_chunks", side_effect=self.fake_bulk_index_chunks)
        self.search_patcher = patch.object(opensearch, "search_chunk_ids", side_effect=self.fake_search_chunk_ids)
        self.bulk_patcher.start()
        self.search_patcher.start()

    def tearDown(self) -> None:
        self.search_patcher.stop()
        self.bulk_patcher.stop()

    def fake_bulk_index_chunks(self, snapshot_id: str, knowledge_base_id: str, chunks: list) -> None:
        self.indexed_chunk_ids[snapshot_id] = [chunk.id for chunk in chunks]

    def fake_search_chunk_ids(self, snapshot_id: str, query: str, retrieval_mode: str, size: int) -> list[str]:
        return self.indexed_chunk_ids.get(snapshot_id, [])[:size]

    def create_snapshot(self, knowledge_base_id: str, document_ids: list[str]) -> str:
        with SessionLocal() as db:
            snapshot = create_index_snapshot(
                knowledge_base_id,
                CreateIndexSnapshotRequest(documentIds=document_ids, retrievalMode="HYBRID"),
                db,
            )
        snapshot_id = snapshot.id
        with SessionLocal() as db:
            built = build_index_snapshot(snapshot_id, db)
        self.assertEqual(built.status, "READY")
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
        self.assertEqual(imported.status, "COMPLETED")
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
        self.assertIn("退款", payload["hits"][0]["snippet"])

    def test_should_support_url_import_and_html_heading_parsing(self) -> None:
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
        with SessionLocal() as db:
            imported = run_import_job(import_job_id, db)
        self.assertEqual(imported.status, "COMPLETED")

        with SessionLocal() as db:
            documents = list_documents("resource-kb-web", db)
        self.assertEqual(documents[0].title, "帮助中心 - 支付失败")

        snapshot_id = self.create_snapshot("resource-kb-web", [])
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
        self.assertIn("payment-failed", retrieval.hits[0].sourceUri)

    def test_should_isolate_snapshot_by_selected_documents(self) -> None:
        self.upload_markdown(
            "resource-kb-split",
            "refund.md",
            "# 退款说明\n未发货可退款。",
        )
        self.upload_markdown(
            "resource-kb-split",
            "shipping.md",
            "# 发货说明\n48 小时内出库。",
        )
        with SessionLocal() as db:
            documents = list_documents("resource-kb-split", db)
        refund_doc = next(item for item in documents if item.title == "refund")

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


if __name__ == "__main__":
    unittest.main()
