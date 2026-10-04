from __future__ import annotations

import os
from dataclasses import dataclass


def _bool(name: str, default: bool) -> bool:
    raw = os.environ.get(name)
    return default if raw is None else raw.strip().lower() in {"1", "true", "yes", "on"}


@dataclass(frozen=True)
class Settings:
    model: str = "small"
    model_dir: str = "/models"  # download cache; mount a volume here
    compute_type: str = "int8"  # best speed/quality trade-off on CPU
    cpu_threads: int = 0  # 0 = library default
    beam_size: int = 1  # greedy: voice notes are short, and this is much faster on CPU
    language: str = ""  # empty = auto-detect; a request can override
    max_bytes: int = 10 * 1024 * 1024
    max_seconds: float = 60.0
    concurrency: int = 1  # simultaneous transcriptions (CPU-bound)
    preload: bool = True  # load the model in the background at startup

    @classmethod
    def from_env(cls) -> Settings:
        d = cls()
        env = os.environ.get
        return cls(
            model=env("STT_MODEL", d.model),
            model_dir=env("STT_MODEL_DIR", d.model_dir),
            compute_type=env("STT_COMPUTE_TYPE", d.compute_type),
            cpu_threads=int(env("STT_CPU_THREADS", d.cpu_threads)),
            beam_size=int(env("STT_BEAM_SIZE", d.beam_size)),
            language=env("STT_LANGUAGE", d.language),
            max_bytes=int(env("STT_MAX_BYTES", d.max_bytes)),
            max_seconds=float(env("STT_MAX_SECONDS", d.max_seconds)),
            concurrency=int(env("STT_CONCURRENCY", d.concurrency)),
            preload=_bool("STT_PRELOAD", d.preload),
        )
