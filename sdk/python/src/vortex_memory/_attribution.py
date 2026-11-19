"""Identify source passages that cannot support an automatic personal fact."""
import re


# A bare source introduction (unlike a delimited quote) can introduce several sentences.
_SOURCE = (
    r"(?:润色|翻译|改写|校对|续写|分析)[^。！？；\n:：]{0,32}|小说(?:开头|片段|正文|对白)?|原话|台词|例句|(?:客户|朋友|同事|角色|主人公|对方|他|她)(?:说|写道|表示)"
    r"|\b(?:translate|rewrite|proofread|polish)\b[^.!?;\n:]{0,48}|\b(?:excerpt|dialogue|quote|said|wrote)"
)
_START = re.compile(r"(?:" + _SOURCE + r")\s*[:：]\s*|" + r"""[“「『‘"]|(?<![\w])'""", re.IGNORECASE)
_PAIRS = {"“":"”","「":"」","『":"』","‘":"’","\"":"\"","'":"'"}


_SELF_NAME = re.compile(r"(?:我叫|请叫我|我的(?:昵称|名字|姓名)是|\bcall me|\bmy name is)\s*$", re.I)
_NAME = re.compile(r"[\w· -]{1,48}")


def author_segments(text: str) -> list[str]:
    segments = []
    cursor = kept_start = 0
    while match := _START.search(text, cursor):
        start = match.start() if match.group() in _PAIRS else match.end()
        if start == len(text) or text[start] not in _PAIRS:
            segments.append(text[kept_start:match.start()])
            return segments  # The supplied passage may contain several sentences.
        closing = [_PAIRS[text[start]]]
        cursor = start + 1
        while closing and cursor < len(text):
            char = text[cursor]
            cursor += 1
            if char == closing[-1]:
                if char == "'" and cursor < len(text) and text[cursor-2].isalnum() and text[cursor].isalnum():
                    continue
                closing.pop()
            elif char in '“「『‘':
                closing.append(_PAIRS[char])
        # A short quoted name can be the value of an explicit self-introduction.
        if (start == match.start() and not closing and _NAME.fullmatch(text[start+1:cursor-1])
                and _SELF_NAME.search(text[kept_start:start])):
            continue
        segments.append(text[kept_start:match.start()])
        kept_start = cursor
    segments.append(text[kept_start:])
    return segments
