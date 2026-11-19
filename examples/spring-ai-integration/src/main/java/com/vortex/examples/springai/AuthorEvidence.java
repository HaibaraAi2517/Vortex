package com.vortex.examples.springai;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Mirrors the Python learner's source-passage boundaries. */
final class AuthorEvidence {
    private static final String SOURCE = "(?:润色|翻译|改写|校对|续写|分析)[^。！？；\\n:：]{0,32}|小说(?:开头|片段|正文|对白)?|原话|台词|例句|(?:客户|朋友|同事|角色|主人公|对方|他|她)(?:说|写道|表示)"
            + "|\\b(?:translate|rewrite|proofread|polish)\\b[^.!?;\\n:]{0,48}|\\b(?:excerpt|dialogue|quote|said|wrote)";
    private static final Pattern START = Pattern.compile("(?:" + SOURCE + ")\\s*[:：]\\s*|[“「『‘\"]|(?<![\\w])'",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern SELF_NAME = Pattern.compile("(?:我叫|请叫我|我的(?:昵称|名字|姓名)是|\\bcall me|\\bmy name is)\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern NAME = Pattern.compile("[\\w· -]{1,48}", Pattern.UNICODE_CHARACTER_CLASS);
    private static final String OPEN = "“「『‘\"'", CLOSE = "”」』’\"'";

    private AuthorEvidence() {}

    static List<String> segments(String text) {
        var result = new ArrayList<String>();
        var match = START.matcher(text);
        int cursor = 0, keptStart = 0;
        while (match.find(cursor)) {
            int start = OPEN.contains(match.group()) ? match.start() : match.end();
            if (start == text.length() || OPEN.indexOf(text.charAt(start)) < 0) {
                result.add(text.substring(keptStart, match.start()));
                return result;
            }
            var closing = new StringBuilder().append(CLOSE.charAt(OPEN.indexOf(text.charAt(start))));
            cursor = start + 1;
            while (!closing.isEmpty() && cursor < text.length()) {
                char c = text.charAt(cursor++);
                if (c == closing.charAt(closing.length() - 1)) {
                    if (c == '\'' && cursor < text.length() && Character.isLetterOrDigit(text.charAt(cursor-2))
                            && Character.isLetterOrDigit(text.charAt(cursor))) continue;
                    closing.setLength(closing.length() - 1);
                } else if ("“「『‘".indexOf(c) >= 0) {
                    closing.append(CLOSE.charAt(OPEN.indexOf(c)));
                }
            }
            if (start == match.start() && closing.isEmpty() && NAME.matcher(text.substring(start+1, cursor-1)).matches()
                    && SELF_NAME.matcher(text.substring(keptStart, start)).find()) continue;
            result.add(text.substring(keptStart, match.start()));
            keptStart = cursor;
        }
        result.add(text.substring(keptStart));
        return result;
    }
}
