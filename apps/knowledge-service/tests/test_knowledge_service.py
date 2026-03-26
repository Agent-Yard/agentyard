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
os.environ["LYNXUS_KNOWLEDGE_SEED_ENABLED"] = "false"
os.environ["LYNXUS_OPENSEARCH_URL"] = "http://opensearch.test"

from fastapi.testclient import TestClient

from app.main import (
    Base,
    IndexSnapshotRecord,
    KnowledgeDocumentRecord,
    KnowledgeFileRecord,
    SessionLocal,
    app,
    engine,
    opensearch,
    startup,
    seed_demo_snapshot,
    wait_for_opensearch_ready,
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
        self.client = TestClient(app)
        self.indexed_chunk_ids: dict[str, list[str]] = {}
        self.bulk_patcher = patch.object(opensearch, "bulk_index_chunks", side_effect=self.fake_bulk_index_chunks)
        self.search_patcher = patch.object(opensearch, "search_chunk_ids", side_effect=self.fake_search_chunk_ids)
        self.bulk_patcher.start()
        self.search_patcher.start()

    def tearDown(self) -> None:
        self.search_patcher.stop()
        self.bulk_patcher.stop()

    def fake_bulk_index_chunks(self, snapshot_id: str, resource_id: str, chunks: list) -> None:
        self.indexed_chunk_ids[snapshot_id] = [chunk.id for chunk in chunks]

    def fake_search_chunk_ids(self, snapshot_id: str, query: str, retrieval_mode: str, size: int) -> list[str]:
        return self.indexed_chunk_ids.get(snapshot_id, [])[:size]

    def create_snapshot(self, resource_id: str, document_ids: list[str]) -> str:
        snapshot = self.client.post(
            f"/internal/resources/{resource_id}/index-snapshots",
            json={"documentIds": document_ids, "retrievalMode": "HYBRID"},
        )
        self.assertEqual(snapshot.status_code, 200)
        snapshot_id = snapshot.json()["id"]
        built = self.client.post(f"/internal/index-snapshots/{snapshot_id}/build")
        self.assertEqual(built.status_code, 200)
        self.assertEqual(built.json()["status"], "READY")
        return snapshot_id

    def upload_markdown(self, resource_id: str, file_name: str, markdown: str) -> str:
        upload = self.client.post("/internal/upload-sessions", json={"resourceId": resource_id})
        self.assertEqual(upload.status_code, 200)
        completed = self.client.post(
            "/internal/uploads",
            json={
                "resourceId": resource_id,
                "uploadSessionId": upload.json()["id"],
                "fileName": file_name,
                "contentType": "text/markdown",
                "contentBase64": base64.b64encode(markdown.encode("utf-8")).decode("ascii"),
            },
        )
        self.assertEqual(completed.status_code, 200)
        import_job_id = completed.json()["importJob"]["id"]
        imported = self.client.post(f"/internal/import-jobs/{import_job_id}/run")
        self.assertEqual(imported.status_code, 200)
        self.assertEqual(imported.json()["status"], "COMPLETED")
        return completed.json()["file"]["id"]

    def test_should_upload_import_build_and_hybrid_retrieve_markdown_file(self) -> None:
        self.upload_markdown(
            "resource-kb-demo",
            "policy.md",
            "# 退款规则\n订单未发货时可以直接退款。\n\n# 升级规则\n出现投诉需要人工升级。",
        )

        documents = self.client.get("/internal/resources/resource-kb-demo/documents")
        self.assertEqual(documents.status_code, 200)
        self.assertEqual(len(documents.json()), 1)

        snapshot_id = self.create_snapshot("resource-kb-demo", [])
        retrieval = self.client.post(
            "/internal/retrieve",
            json={
                "indexSnapshotId": snapshot_id,
                "query": "订单未发货可以退款吗",
                "topK": 3,
                "minScore": 0.1,
                "retrievalMode": "HYBRID",
            },
        )
        self.assertEqual(retrieval.status_code, 200)
        payload = retrieval.json()
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
            created = self.client.post(
                "/internal/url-imports",
                json={
                    "resourceId": "resource-kb-web",
                    "url": "https://help.example.com/payment-failed",
                    "title": "支付失败专题",
                },
            )
        self.assertEqual(created.status_code, 200)
        import_job_id = created.json()["importJob"]["id"]
        imported = self.client.post(f"/internal/import-jobs/{import_job_id}/run")
        self.assertEqual(imported.status_code, 200)
        self.assertEqual(imported.json()["status"], "COMPLETED")

        documents = self.client.get("/internal/resources/resource-kb-web/documents")
        self.assertEqual(documents.status_code, 200)
        self.assertEqual(documents.json()[0]["title"], "帮助中心 - 支付失败")

        snapshot_id = self.create_snapshot("resource-kb-web", [])
        retrieval = self.client.post(
            "/internal/retrieve",
            json={
                "indexSnapshotId": snapshot_id,
                "query": "支付失败怎么办",
                "topK": 2,
                "minScore": 0.1,
                "retrievalMode": "VECTOR",
            },
        )
        self.assertEqual(retrieval.status_code, 200)
        self.assertFalse(retrieval.json()["lowConfidence"])
        self.assertIn("payment-failed", retrieval.json()["hits"][0]["sourceUri"])

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
        documents = self.client.get("/internal/resources/resource-kb-split/documents").json()
        refund_doc = next(item for item in documents if item["title"] == "refund")

        snapshot_id = self.create_snapshot("resource-kb-split", [refund_doc["id"]])
        retrieval = self.client.post(
            "/internal/retrieve",
            json={
                "indexSnapshotId": snapshot_id,
                "query": "什么时候出库",
                "topK": 3,
                "minScore": 0.1,
                "retrievalMode": "HYBRID",
            },
        )
        self.assertEqual(retrieval.status_code, 200)
        self.assertTrue(retrieval.json()["lowConfidence"])
        self.assertEqual(retrieval.json()["hits"], [])

    def test_should_fail_import_for_unsupported_file_type(self) -> None:
        upload = self.client.post("/internal/upload-sessions", json={"resourceId": "resource-kb-demo"})
        completed = self.client.post(
            "/internal/uploads",
            json={
                "resourceId": "resource-kb-demo",
                "uploadSessionId": upload.json()["id"],
                "fileName": "binary.exe",
                "contentType": "application/octet-stream",
                "contentBase64": base64.b64encode(b"noop").decode("ascii"),
            },
        )
        self.assertEqual(completed.status_code, 400)

    def test_should_wait_for_opensearch_until_ready(self) -> None:
        class FakeOpenSearchClient:
            def __init__(self) -> None:
                self.enabled = True
                self.base_url = "http://opensearch.test"
                self._calls = 0

            def request(self, method: str, path: str) -> dict:
                self._calls += 1
                if self._calls < 3:
                    raise ValueError("opensearch request failed: [Errno 61] Connection refused")
                return {"status": "yellow"}

        client = FakeOpenSearchClient()
        wait_for_opensearch_ready(client, timeout_seconds=1, retry_interval_seconds=0)
        self.assertEqual(client._calls, 3)

    def test_should_raise_clear_error_when_opensearch_is_not_ready_in_time(self) -> None:
        class FakeOpenSearchClient:
            enabled = True
            base_url = "http://opensearch.test"

            def request(self, method: str, path: str) -> dict:
                raise ValueError("opensearch request failed: [Errno 61] Connection refused")

        with self.assertRaises(RuntimeError) as ctx:
            wait_for_opensearch_ready(FakeOpenSearchClient(), timeout_seconds=0, retry_interval_seconds=0)
        self.assertIn("was not ready", str(ctx.exception))
        self.assertIn("Connection refused", str(ctx.exception))

    def test_should_seed_demo_snapshot_after_flushing_file_records(self) -> None:
        with (
            patch("app.main.wait_for_opensearch_ready", return_value=None),
            patch.object(opensearch, "bulk_index_chunks", return_value=None),
        ):
            with SessionLocal() as db:
                seed_demo_snapshot(db)

            with SessionLocal() as db:
                seeded_file = db.get(KnowledgeFileRecord, "kb-file-support-seed")
                seeded_document = db.get(KnowledgeDocumentRecord, "kb-document-support-seed")
                seeded_snapshot = db.get(IndexSnapshotRecord, "snapshot-kb-support-v1")

        self.assertIsNotNone(seeded_file)
        self.assertIsNotNone(seeded_document)
        self.assertIsNotNone(seeded_snapshot)
        self.assertEqual(seeded_document.file_id, seeded_file.id)
        self.assertEqual(seeded_snapshot.status, "READY")

    def test_should_not_fail_startup_when_demo_seed_is_unavailable(self) -> None:
        with patch("app.main.SEED_ENABLED", True), patch("app.main.seed_demo_snapshot", side_effect=RuntimeError("opensearch unavailable")):
            startup()


if __name__ == "__main__":
    unittest.main()
