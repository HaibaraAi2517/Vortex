"""Chat with a real model; Vortex automatically recalls and maintains configured facts."""
import argparse
from dataclasses import asdict
import json
import os
from pathlib import Path

from vortex_memory import CompatibleChatModel, MemorySession, VortexClient


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--namespace", default=os.environ.get("VORTEX_NAMESPACE", "personal"))
    parser.add_argument("--message", help="One turn in a fresh conversation, then exit")
    parser.add_argument("--output", type=Path, help="Write the turn, retrieval and actual commit results as JSON")
    args = parser.parse_args()
    model = CompatibleChatModel.from_env()
    memory = VortexClient(os.environ.get("VORTEX_URL", "http://127.0.0.1:8080"), namespace=args.namespace,
                          token=os.environ.get("VORTEX_SECURITY_BEARER_TOKEN"))
    session = MemorySession(memory, model)

    def chat(message):
        turn = session.chat(message)
        report = {"namespace": memory.namespace, "model": model.model, "user_message": message, **asdict(turn)}
        if args.output:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        print(turn.answer)
        for update in turn.updates:
            print(f"[已提交] {update['key']} revision={update['revision']} {update['operation']}")
        for error in turn.errors:
            print("[记忆未完成] " + json.dumps(error, ensure_ascii=False))
        return turn

    if args.message:
        turn = chat(args.message)
        return 0 if turn.memory_ok else 2
    print(f"Vortex 自动记忆 · {memory.namespace} · {model.model}")
    print("直接聊天即可。/new 开始新会话（长期记忆保留），/exit 退出。")
    while True:
        try:
            message = input("你 > ").strip()
        except (EOFError, KeyboardInterrupt):
            print()
            break
        if message == "/exit":
            break
        if message == "/new":
            session = MemorySession(memory, model)
            print("已开始新会话，对话历史为空。")
        elif message:
            chat(message)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
