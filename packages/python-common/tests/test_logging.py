import io
import json
import logging
import os

from lynxus_common.logging import configure_structured_logging


def test_structured_logging_includes_stdlib_extra_fields() -> None:
    os.environ["LYNXUS_TEST_LOG_LEVEL"] = "DEBUG"
    os.environ["LYNXUS_LOG_FORMAT"] = "json"
    stream = io.StringIO()

    logger_name = "lynxus-test-extra-logger"
    logger = configure_structured_logging("test-service", "LYNXUS_TEST_LOG_LEVEL", logger_name)
    logger.handlers[0].setStream(stream)

    logging.getLogger(logger_name).debug(
        "llm 请求",
        extra={
            "modelId": "gpt-test",
            "llmRequest": {
                "url": "https://runtime.example/chat/completions",
                "payload": {"model": "gpt-test", "messages": [{"role": "user", "content": "查询订单状态"}]},
            },
        },
    )

    output = stream.getvalue()
    assert "\\u67e5\\u8be2" not in output
    assert "查询订单状态" in output
    event = json.loads(output)
    assert event["message"] == "llm 请求"
    assert event["level"] == "debug"
    assert event["service"] == "test-service"
    assert event["modelId"] == "gpt-test"
    assert event["llmRequest"] == {
        "url": "https://runtime.example/chat/completions",
        "payload": {"model": "gpt-test", "messages": [{"role": "user", "content": "查询订单状态"}]},
    }
