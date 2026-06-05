import unittest

from agentyard_agent_runtime.openai_compatible import (
    OpenAiCompatibleStreamAccumulator,
    OpenAiCompatibleStreamIdleTimeoutError,
    OpenAiCompatibleStreamMalformedError,
    iter_openai_compatible_stream_events,
)


class OpenAiCompatibleStreamingParserTest(unittest.TestCase):
    def test_should_accumulate_text_finish_reason_and_usage(self) -> None:
        events = list(
            iter_openai_compatible_stream_events(
                [
                    'data: {"choices":[{"delta":{"reasoning_content":"内部思考"},"finish_reason":null}]}',
                    'data: {"choices":[{"delta":{"content":"已查到"},"finish_reason":null}]}',
                    'data: {"choices":[{"delta":{"content":"订单。"},"finish_reason":"stop"}]}',
                    'data: {"choices":[],"usage":{"prompt_tokens":7,"completion_tokens":3,"total_tokens":10}}',
                    "data: [DONE]",
                ]
            )
        )

        accumulator = OpenAiCompatibleStreamAccumulator()
        for event in events:
            accumulator.apply(event)
        message = accumulator.build_message()

        self.assertEqual("已查到订单。", message.content)
        self.assertEqual("内部思考", message.thinking)
        self.assertEqual("stop", message.finish_reason)
        self.assertEqual({"prompt_tokens": 7, "completion_tokens": 3, "total_tokens": 10}, message.usage)

    def test_should_reassemble_fragmented_tool_arguments(self) -> None:
        events = list(
            iter_openai_compatible_stream_events(
                [
                    'data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call-1","type":"function","function":{"name":"create_ticket","arguments":"{\\"sub"}}]}}]}',
                    'data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"ject\\":\\"refund"}}]}}]}',
                    'data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":" request\\"}"}}]},"finish_reason":"tool_calls"}]}',
                    "data: [DONE]",
                ]
            )
        )

        accumulator = OpenAiCompatibleStreamAccumulator()
        for event in events:
            accumulator.apply(event)
        message = accumulator.build_message()

        self.assertEqual("tool_calls", message.finish_reason)
        self.assertEqual(1, len(message.tool_calls))
        self.assertEqual("call-1", message.tool_calls[0].call_id)
        self.assertEqual("create_ticket", message.tool_calls[0].tool_name)
        self.assertEqual({"subject": "refund request"}, message.tool_calls[0].arguments)

    def test_should_raise_clear_error_for_malformed_stream(self) -> None:
        with self.assertRaisesRegex(OpenAiCompatibleStreamMalformedError, "malformed openai-compatible stream JSON"):
            list(iter_openai_compatible_stream_events(['data: {"choices":[']))

    def test_should_raise_clear_error_for_idle_timeout(self) -> None:
        ticks = iter([0.0, 5.0])

        with self.assertRaisesRegex(OpenAiCompatibleStreamIdleTimeoutError, "idle timeout"):
            list(
                iter_openai_compatible_stream_events(
                    ['data: {"choices":[{"delta":{"content":"late"}}]}'],
                    idle_timeout_seconds=1.0,
                    clock=lambda: next(ticks),
                )
            )


if __name__ == "__main__":
    unittest.main()
