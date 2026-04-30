from __future__ import annotations

__all__ = ["PrivacyPipeline", "build_privacy_pipeline"]


def __getattr__(name: str):
    if name in __all__:
        from .pipeline import PrivacyPipeline, build_privacy_pipeline

        exports = {
            "PrivacyPipeline": PrivacyPipeline,
            "build_privacy_pipeline": build_privacy_pipeline,
        }
        return exports[name]
    raise AttributeError(f"module {__name__!r} has no attribute {name!r}")
