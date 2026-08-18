# Third-Party Notices

Vortex source code and project documentation are licensed under Apache-2.0. The model assets listed
below retain their upstream licenses. Their inclusion does not change the license of Vortex itself.

## BGE Small Chinese v1.5

- Upstream model: `BAAI/bge-small-zh-v1.5`
- Upstream base revision: `4bf3c54884c552e68da7eb27f3e9bdc5a32e32d4`
- Direct ONNX artifact repository: `Xenova/bge-small-zh-v1.5`
- Direct ONNX artifact revision: `fcecc3c5fef6becfa2b2bdda15c1c938857be534`
- Upstream license: MIT
- Commercial use and redistribution: permitted under the MIT license
- Model card: https://huggingface.co/BAAI/bge-small-zh-v1.5
- ONNX artifact source: https://huggingface.co/Xenova/bge-small-zh-v1.5

Distributed files:

| File | SHA-256 |
| --- | --- |
| `models/bge-small-zh/model.onnx` | `69a0b846f4f116b5e6aabf9546ea6754d02264f3211a13a1bd69b31b8040749a` |
| `models/bge-small-zh/tokenizer.json` | `48cea5d44424912a6fd1ea647bf4fe50b55ab8b1e5879c3275f80e339e8fae26` |

The ONNX file SHA-256 matches the Git LFS object referenced by the direct artifact revision. The
tokenizer SHA-256 matches the upstream base revision.

## MS MARCO MiniLM Cross Encoder

- Upstream model: `cross-encoder/ms-marco-MiniLM-L6-v2`
- Upstream revision: `c5ee24cb16019beea0893ab7796b1df96625c6b8`
- Source artifact: `onnx/model_quint8_avx2.onnx`
- Upstream license: Apache-2.0
- Model card: https://huggingface.co/cross-encoder/ms-marco-MiniLM-L6-v2

Distributed files:

| File | SHA-256 |
| --- | --- |
| `models/ms-marco-MiniLM-L6-v2-c5ee24cb/config.json` | `380e02c93f431831be65d99a4e7e5f67c133985bf2e77d9d4eba46847190bacc` |
| `models/ms-marco-MiniLM-L6-v2-c5ee24cb/model.onnx` | `c80a8b34256ea453093d612e3ac48d3d965a0c0a48c7906709af8b8e28461bf9` |
| `models/ms-marco-MiniLM-L6-v2-c5ee24cb/tokenizer.json` | `d241a60d5e8f04cc1b2b3e9ef7a4921b27bf526d9f6050ab90f9267a1f9e5c66` |

The model SHA-256 matches the Git LFS object referenced by the pinned upstream revision.

## Evaluation Data

The JSON workloads under `vortex-app/src/main/resources/` are project-authored synthetic evaluation
fixtures. Generated conversions of external datasets under ignored local directories are not part of
the release artifacts. Any future externally sourced dataset must add its own provenance, revision,
license, and checksum record here before distribution.
