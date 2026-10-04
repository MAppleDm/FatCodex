"""Text normalisation shared by the importers, the FTS index and the scorer."""

from __future__ import annotations

import re
import unicodedata

_TOKEN_RE = re.compile(r"[^\W_]+", re.UNICODE)

# Words that carry no signal in USDA-style names ("Egg, whole, raw" / "meat only").
STOPWORDS = frozenset({"a", "an", "and", "or", "of", "the", "with", "in", "on", "for", "to", "only", "from"})


def fold(text: str) -> str:
    """Lowercase and strip diacritics (also maps ё->е, й->и, matching FTS5 remove_diacritics=2)."""
    decomposed = unicodedata.normalize("NFKD", text).lower()
    return "".join(ch for ch in decomposed if not unicodedata.combining(ch))


def tokenize(text: str) -> list[str]:
    """Folded alphanumeric tokens without stopwords, order preserved, duplicates removed."""
    seen: dict[str, None] = {}
    for token in _TOKEN_RE.findall(fold(text)):
        if token not in STOPWORDS:
            seen.setdefault(token, None)
    return list(seen)


def stem(token: str) -> str:
    """Very light stemmer: English plurals, and Russian endings via a fixed-length cut.

    Only used for scoring and for Russian prefix queries; it is deliberately crude.
    """
    if token.isascii():
        if len(token) > 4 and token.endswith("ies"):
            return token[:-3] + "y"
        if len(token) > 4 and token.endswith(("oes", "ches", "shes", "xes", "sses")):
            return token[:-2]  # potatoes, peaches, radishes, boxes, glasses
        if len(token) > 3 and token.endswith("s") and not token.endswith(("ss", "us")):
            return token[:-1]
        return token
    cut = 2 if len(token) >= 5 else 1 if len(token) == 4 else 0
    return token[: len(token) - cut]


def stem_set(text: str) -> frozenset[str]:
    return frozenset(stem(t) for t in tokenize(text))


def search_text(name: str, brand: str | None) -> str:
    """What gets indexed in FTS5 for a food."""
    return " ".join(tokenize(f"{name} {brand or ''}"))
