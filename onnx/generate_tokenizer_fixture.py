#!/usr/bin/env python3
"""
Generates a minimal, self-contained CLIP tokenizer.json fixture for unit tests.

The unit tests for the Kotlin ClipTokenizer (in photosComponent) need a
tokenizer.json to load, but the real one (onnx/models/tokenizer.json) is ~1MB.
Embedding it in tests would be wasteful, and pointing tests at a file on disk
is fragile. Instead, this script extracts a tiny subset that is exactly enough
to tokenize a fixed set of test texts:

  - the vocab entries for every FINAL token those texts produce
  - the BPE merge pairs actually applied for those texts (in original order,
    so relative ranks are preserved)
  - the two special tokens (<|startoftext|> / <|endoftext|>)

It then verifies the fixture with the `tokenizers` library: encoding the same
texts with the fixture must produce the same IDs as the real tokenizer.

The Kotlin test embeds the printed JSON as a string constant. If the test texts
change, re-run this script and copy the new JSON into the test.

Usage:
    python3 generate_tokenizer_fixture.py [texts...]
(uses the venv at onnx/.venv; default texts are the current sample descriptions)
"""

import json
import sys
from pathlib import Path

from tokenizers import Tokenizer

HERE = Path(__file__).resolve().parent
REAL_TOKENIZER = HERE / "models" / "tokenizer.json"

DEFAULT_TEXTS = [
    "Turtle",
    "Cat",
    "a can of tuna",
    "a jar and a hand",
    "cat lying with lemons",
]

START = "<|startoftext|>"
END = "<|endoftext|>"


def merge_word(chars: list[str], merge_rank: dict[tuple[str, str], int]) -> list[str]:
    """
    GPT-2 byte-level BPE merge loop, mirroring ClipTokenizer.bpe():
    repeatedly merge the lowest-rank adjacent pair until no more merges apply.
    Returns the final tokens (some may still be single chars).
    """
    word = chars[:-1] + [chars[-1] + "</w>"]
    if len(word) == 1:
        return word
    while True:
        pairs = [(word[i], word[i + 1]) for i in range(len(word) - 1)]
        if not pairs:
            break
        best = min(pairs, key=lambda p: merge_rank.get(p, 10**9))
        if best not in merge_rank:
            break
        merged = []
        i = 0
        while i < len(word):
            if i < len(word) - 1 and word[i] == best[0] and word[i + 1] == best[1]:
                merged.append(best[0] + best[1])
                i += 2
            else:
                merged.append(word[i])
                i += 1
        word = merged
        if len(word) == 1:
            break
    return word


def main() -> None:
    texts = sys.argv[1:] or DEFAULT_TEXTS

    # Parse the real tokenizer.json the same way ClipTokenizer does.
    root = json.loads(REAL_TOKENIZER.read_text())
    vocab = root["model"]["vocab"]
    merges = root["model"]["merges"]
    merge_rank = {}
    for idx, m in enumerate(merges):
        a, b = m.split(" ")
        merge_rank[(a, b)] = idx

    # The real tokenizer (which ClipTokenizer mirrors): pre-tokenize with the
    # CLIP regex + byte-level encoder, then BPE-merge. Use the `tokenizers`
    # library's pre_tokenizer here so our simulation matches it exactly and we
    # don't need to re-implement the regex in Python.
    real_tok = Tokenizer.from_file(str(REAL_TOKENIZER))

    needed_vocab: dict[str, int] = {}
    used_ranks: dict[tuple[str, str], int] = {}

    def add_tokens(chars: list[str]) -> None:
        # The `tokenizers` library needs every initial byte-level char in the
        # vocab to map ids; include the single chars too (the Kotlin tokenizer
        # only looks up final tokens, so these are harmless extra entries).
        for c in chars:
            needed_vocab[c] = vocab[c]
        for final in merge_word(chars, merge_rank):
            needed_vocab[final] = vocab[final]

    def record_merges(chars: list[str]) -> None:
        """Simulate the BPE loop to record the merge pairs this word applies."""
        word = chars[:-1] + [chars[-1] + "</w>"]
        if len(word) == 1:
            return
        while True:
            pairs = [(word[i], word[i + 1]) for i in range(len(word) - 1)]
            if not pairs:
                break
            best = min(pairs, key=lambda p: merge_rank.get(p, 10**9))
            if best not in merge_rank:
                break
            used_ranks[best] = merge_rank[best]
            merged = []
            i = 0
            while i < len(word):
                if i < len(word) - 1 and word[i] == best[0] and word[i + 1] == best[1]:
                    merged.append(best[0] + best[1])
                    i += 2
                else:
                    merged.append(word[i])
                    i += 1
            word = merged
            if len(word) == 1:
                break

    for text in texts:
        # The real pipeline is: normalizer (NFC + lowercase + whitespace
        # collapse) THEN pre-tokenizer. `pre_tokenize_str` skips the normalizer,
        # so apply it explicitly to mirror the full encode path.
        normalized = real_tok.normalizer.normalize_str(text)
        # `pre_tokenize_str` returns the byte-level-encoded pieces (each piece
        # is already a byte-level char string, e.g. "Ġtuna"), exactly what the
        # BPE merge step operates on.
        for piece, _ in real_tok.pre_tokenizer.pre_tokenize_str(normalized):
            chars = list(piece)
            record_merges(chars)
            add_tokens(chars)

    # Sort used merges by original rank (relative order is what BPE relies on),
    # then re-index 0..n so the subset is self-consistent.
    used_merges = sorted(used_ranks.items(), key=lambda kv: kv[1])
    fixture_merges = [" ".join(pair) for pair, _ in used_merges]

    # The `tokenizers` library also needs every INTERMEDIATE merge result in the
    # vocab (e.g. "in" when merging "lying"), not just the final tokens. Add
    # both sides of each used merge pair and each merged result.
    for (a, b), _ in used_merges:
        needed_vocab.setdefault(a, vocab[a])
        needed_vocab.setdefault(b, vocab[b])
        needed_vocab.setdefault(a + b, vocab[a + b])

    # Build the fixture from the REAL tokenizer.json structure (so the
    # `tokenizers` library can load it for verification), swapping in the
    # subset vocab/merges. Only model.vocab / model.merges / added_tokens
    # matter to the Kotlin ClipTokenizer; the rest is metadata the library
    # needs to parse the file.
    fixture = json.loads(REAL_TOKENIZER.read_text())
    fixture["model"]["vocab"] = needed_vocab
    fixture["model"]["merges"] = fixture_merges
    # Keep the FULL added_tokens objects from the real file (the `tokenizers`
    # library needs all fields, e.g. `single_word`), not just content+id.
    fixture["added_tokens"] = [
        at for at in fixture["added_tokens"]
        if at["content"] in (START, END)
    ]

    # Verify with the `tokenizers` library: fixture must produce the same IDs.
    fixture_path = HERE / "tokenizer_fixture.json"
    fixture_path.write_text(json.dumps(fixture))
    fixture_tok = Tokenizer.from_file(str(fixture_path))

    print("fixture vocab size:", len(fixture["model"]["vocab"]))
    print("fixture merges count:", len(fixture_merges))
    print()
    for text in texts:
        real_ids = real_tok.encode(text).ids
        fixture_ids = fixture_tok.encode(text).ids
        match = "OK" if real_ids == fixture_ids else "MISMATCH"
        print(f"{text!r:24} -> {real_ids}  [{match}]")
        if real_ids != fixture_ids:
            print("    fixture:", fixture_ids)
            sys.exit(1)

    print("\n--- EMBED THIS JSON IN THE KOTLIN TEST ---")
    print(json.dumps(fixture, separators=(",", ":")))


if __name__ == "__main__":
    main()
