package com.vortex.app.controller;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Opt in the five built-in profile fields for a task. Other schemas keep their retrieval semantics. */
final class PersonalContextPolicy {
    private PersonalContextPolicy() {}

    private static Pattern pattern(String expression) {
        return Pattern.compile(expression, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    // Match the subject and the field together. A mention of coffee or a city on its own is insufficient.
    private static final String SELF = "(?:我|用户)(?:现在|目前|平时|通常|最|比较|一般|的|长期|更|还){0,4}";
    private static final Map<String, Pattern> REQUESTS = Map.of(
            "user.name", pattern(SELF + "(?:叫(?:什么|啥|哪)|名字|姓名|昵称|称呼|取餐名|取餐称呼)"
                    + "|(?:怎么|如何|该|怎样)称呼我|\\bmy (?:name|nickname)\\b|what (?:do you|should you) call me"),
            "user.preference.coffee", pattern(SELF + "(?:常喝|爱喝|喜欢|爱点|偏好|惯喝|咖啡).{0,12}(?:咖啡|拿铁|美式|口味|偏好)"
                    + "|(?:给我|帮我|替我).{0,10}(?:点|选|推荐).{0,8}(?:咖啡|拿铁|美式)"
                    + "|\\bmy (?:usual |favorite |favourite |preferred )?coffee|(?:coffee|latte|espresso).{0,16}do i"),
            "user.home.city", pattern(SELF + "(?:住哪|住在|住的城市|家在哪|居住|定居|常住|住址|住所|所在的城市)"
                    + "|where (?:do i|am i currently) (?:live|living|based)|\\bmy (?:home city|city of residence|address)\\b"),
            "user.work.role", pattern(SELF + "(?:职业|工作(?:是什么|是做什么)|是做什么|做什么工作|从事|岗位)"
                    + "|what (?:do i do for (?:a living|work)|is my (?:job|occupation|profession))|\\bmy (?:occupation|job title|profession)\\b"),
            "user.preference.language", pattern(SELF + "(?:回复语言|语言偏好|偏好.{0,5}语言|希望.{0,12}语言)"
                    + "|(?:用|使用).{0,8}语言.{0,5}(?:回复我|回答我)|\\bmy (?:preferred |reply )language|language.{0,18}(?:i prefer|reply to me)"));

    private static final Pattern QUOTED = pattern("「[^」]*(?:」|$)|『[^』]*(?:』|$)|“[^”]*(?:”|$)|‘[^’]*(?:’|$)|\"[^\"]*(?:\"|$)|(?<![a-z0-9])'[^'\\n]*(?:'|$)|```[\\s\\S]*?(?:```|$)|`[^`]*(?:`|$)");
    private static final Pattern SOURCE_TASK = pattern("润色|改写|转述|校对|翻译|改得|改成|改一改|例句|小说|台词|原文|客户说|朋友说|\\b(?:polish|rewrite|translate|proofread|paraphrase|said|wrote)\\b");
    private static final Pattern OTHER = pattern("(?:替|帮|给|为)(?:我的?)?(?:同事|朋友|家人|客户|他|她|角色)|给(?:同事|朋友|客户)|\\bfor (?:my (?:friend|colleague)|him|her)\\b");
    private static final Pattern OPT_OUT = pattern("(?:别|不要|不用|无需).{0,4}(?:用|参考|读取|调用|根据)?.{0,5}(?:我的|个人|记忆|档案)|(?:without|do not use|don't use) (?:my |any )?(?:memories|memory|profile|personal)");
    private static final Pattern COFFEE = pattern("咖啡|美式|拿铁|意式|coffee|latte|espresso");
    private static final Pattern USUAL = pattern("(?:我|用户)(?:平时|通常|一贯|常喝|的口味|平常|的喜好)|\\bmy (?:usual|taste|preference)\\b");

    static Set<String> excludedKeys(String query) {
        Set<String> excluded = new HashSet<>(REQUESTS.keySet());
        String instructions = QUOTED.matcher(query).replaceAll(" ￰ ");
        if (OPT_OUT.matcher(instructions).find()) return excluded;
        // Keep the source payload out of the decision, including unquoted text after a colon.
        // An explicit follow-up outside a quoted passage can still ask for the user's own fact.
        for (String task : instructions.split("(?:另外[，,]?|[。！？!?;；]|\\bseparately\\b)")) {
            if (SOURCE_TASK.matcher(task).find()) {
                if (!task.contains("￰")) break;
                continue;
            }
            if (OTHER.matcher(task).find()) continue;
            REQUESTS.forEach((key, request) -> { if (request.matcher(task).find()) excluded.remove(key); });
            if (COFFEE.matcher(task).find() && USUAL.matcher(task).find()) excluded.remove("user.preference.coffee");
        }
        return excluded;
    }
}
