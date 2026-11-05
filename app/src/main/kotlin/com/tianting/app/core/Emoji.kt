// 表情面板（PRD-ui F-UI-11）：固定的常用系统 emoji，只选 Unicode 13.0 以内的单个码点（没有 ZWJ 序列和变体选择符），
// 作为普通文本发送，用系统字体渲染。列表和 tianting-web 相同。
package com.tianting.app.core

private const val RAW = "😀😃😄😁😆😅😂🤣😊😇🙂🙃😉😌😍🥰😘😗😙😚😋😛😝😜🤪🤨🧐🤓😎🤩🥳😏😒😞😔😟😕🙁😣😖😫😩🥺😢😭😤😠😡🤬🤯😳🥵🥶😱😨😰😥😓🤗🤔🤭🤫🤥😶😐😑😬🙄😯😦😧😮😲🥱😴🤤😪😵🤐🥴🤢🤮🤧😷🤒🤕👍👎👌👏🙏🤝💪👋🎉🎂🌹🌈🔥💯💖💔⭐🍻☕🍉"

val EMOJI: List<String> = buildList {
    var i = 0
    while (i < RAW.length) {
        val cp = RAW.codePointAt(i)
        add(String(Character.toChars(cp)))
        i += Character.charCount(cp)
    }
}
