package com.example.demo.ai;

import dev.langchain4j.service.UserMessage;

/** 意图分类器：只返回英文枚举名（逗号分隔），由代码容错解析。重点在准确区分"选/已有/搭配/追问"。 */
public interface IntentClassifier {

    @UserMessage("""
            你是意图分类器。把用户游戏问题归为以下一个或多个意图，**只返回英文枚举名**，多个用英文逗号分隔，不要任何其他文字/引号/markdown/解释：

            HEX_PICK  用户想【选、该选哪个海克斯】："选哪个海克斯/选什么强化/再看一下/帮我选"
            SYNERGY   用户想【分析某海克斯或装备与已有对象/彼此的搭配、联动、是否适合】："X配Y会怎样/该不该出Z/这套组合/已有X再出Y/X和Y联动吗/出一个Z会怎样/适合吗"
            BUILD     用户想【出什么装备】："出什么装/怎么出装"
            COUNTER   用户想【针对对面、克制某英雄】："打对面/克制/怎么打对面"
            FREE_QUERY 用户想【查某对象胜率/排名/数据/排行】
            DESCRIPTIVE 用户想【按效果或机制描述找装备/海克斯】："克护盾的/把攻速转CD的/召唤机器人的"
            UPDATE_DB 用户要【更新知识库/刷新数据】
            CHAT      非游戏闲聊 / 拿不准

            【判定原则（最重要）】
            a. 意图=用户要的**结果**，不是答案涉及的数据。"出装时要考虑对面"仍归 BUILD；"出装看自己海克斯"仍归 BUILD。
            b. 【声明已有 → 不是选】用户说"我已经拿到/有了/选了 X"是**陈述既有事实**，归 SYNERGY（描述这个已有对象的联动），不要归 HEX_PICK。只有确实想【从候选里选】才归 HEX_PICK。
            c. 【提及对象+搭配语气 → SYNERGY】句中出现"与/配/和/加上/配合/联动/会怎样/该不该/适不适合/出一个X会怎样"且提及海克斯或装备名，优先 SYNERGY。
            d. 【追问承接上下文】以"那/这样/呢/那呢/会怎样/为什么/该不该/如果"开头的追问，是承接上文主题，按上文主题归 SYNERGY/BUILD/COUNTER，**不要归 CHAT**。
            e. 多意图仅当用户**明确要多个结果**（如"告诉我X+顺便出装"→SYNERGY,BUILD）。

            【返回示例】
            选哪个海克斯 -> HEX_PICK
            我已经拿到物理转魔法和由心及物 -> SYNERGY
            出一个霸王血铠会怎样 -> SYNERGY
            我该出什么装备 -> BUILD
            对面有坦克我出什么克制 -> COUNTER
            瑞兹胜率多少 -> FREE_QUERY
            克护盾的装备 -> DESCRIPTIVE
            更新知识库 -> UPDATE_DB
            今晚吃什么 -> CHAT

            只返回枚举名，不要其他。问题：{{it}}
            """)
    String classify(String question);
}
