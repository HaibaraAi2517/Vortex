# Non-oracle answer evaluation

`ops/eval-longmemeval.py` produces hypotheses for the official LongMemEval-S dataset through
the public Python client. It indexes **every supplied session and turn**, including distractors,
preserves session/question dates, and does not place answers or evidence labels in retrieval metadata
or the generation prompt. Each question uses a new namespace; its inserted fragments are removed afterwards.

The dataset is not bundled. Obtain `longmemeval_s_cleaned.json` from the
[LongMemEval project](https://github.com/xiaowu0162/LongMemEval), record its upstream revision and license,
and use a separate local Vortex data directory for evaluation. The runner records the input SHA-256,
corpus policy, counts, limits, errors and whether the run covers only a sample. A filename check rejects
obvious oracle input; it cannot certify that a renamed/custom file is the official full corpus.

The answer model is an explicit external command: stdin is the complete prompt, stdout must be the answer.
For example, with Ollama installed and the selected model already downloaded:

```bash
python -m pip install ./sdk/python
python ops/eval-longmemeval.py \
  --dataset /path/to/longmemeval_s_cleaned.json \
  --output target/longmemeval-s-hypotheses.jsonl \
  --limit 3 \
  --answer-command ollama run qwen3:8b
```

This is a small pipeline check. Omit `--limit` for the complete dataset. Arguments after
`--answer-command` belong to that command. Freeze the model version/digest and report it alongside
the runner's report. If API authentication is enabled, authorize `eval-lme-*` and set
`VORTEX_SECURITY_BEARER_TOKEN`; the runner never borrows a personal memory namespace.

The output contains the official evaluator's `question_id` and `hypothesis` fields. Run the upstream
answer evaluator against these hypotheses and publish its configuration, cost, raw judgements and
failure breakdown. The runner deliberately emits `answer_accuracy: null` until that judging step is done;
substring matching is not treated as answer accuracy. Failed generations remain in the output with
empty hypotheses, are counted separately, and make the process exit nonzero.

No answer-quality result is claimed for this change: the real-model smoke test exercises **embedding,
retrieval, updates and durability**, while the generation harness tests use a deterministic command
only to verify protocol/data handling. An actual LongMemEval-S generation and official judge run is
still required before publishing an answer-accuracy claim. English semantic results also need evaluation
because the default embedding model is BGE-Small-ZH.
