package com.lesliefang.harmonytouping.key

/**
 * 桌面按键 -> OpenHarmony uinput 键值映射。
 *
 * 移植自官方 HOScrcpy 的 utils/KeyCodeUtil.java：
 * - 键码使用 Java/AWT KeyEvent 的 VK_ 常量值（Compose Desktop 的 Key.keyCode 在桌面端即 AWT 键码）
 * - 设备端通过 `uinput -K -d <key> -u <key>` 注入按键
 */
object KeyCodeMapper {
    /** OpenHarmony 的 Shift 键值，用于组合键（大写字母、Shift+符号） */
    const val OH_KEY_SHIFT = 2047

    // AWT VK_ 值 -> OpenHarmony 键值（与官方 KeyCodeUtil.KEY_CODE_MAPPER 一致）
    private val CODE_MAP = mapOf(
        8 to 2055,    // VK_BACK_SPACE
        10 to 2119,  // VK_ENTER
        38 to 2012,  // VK_UP
        40 to 2013,  // VK_DOWN
        37 to 2014,  // VK_LEFT
        39 to 2015,  // VK_RIGHT
        32 to 2050,  // VK_SPACE
        61 to 2058,  // VK_EQUALS '='
        521 to 2066, // VK_ADD（数字键盘 +）
        45 to 2057,  // VK_MINUS '-'
        27 to 2070,  // VK_ESCAPE
        59 to 2062,  // VK_SEMICOLON ';'
        91 to 2059,  // VK_OPEN_BRACKET '['
        93 to 2060,  // VK_CLOSE_BRACKET ']'
        47 to 2113,  // VK_SLASH '/'
        222 to 2063, // VK_QUOTE '\''
        44 to 2118,  // VK_COMMA ','
        46 to 2117,  // VK_PERIOD '.'
        92 to 2061,  // VK_BACK_SLASH '\\'
        9 to 2049,   // VK_TAB
        111 to 2064, // VK_DIVIDE（数字键盘 /）
        106 to 2010, // VK_MULTIPLY（数字键盘 *）
        109 to 2057, // VK_SUBTRACT（数字键盘 -）
        107 to 2066, // VK_ADD（数字键盘 +）
        110 to 2117, // VK_DECIMAL（数字键盘 .）
        192 to 2056, // VK_BACK_QUOTE '`'
    )

    // Shift 组合键（与官方 KeyCodeUtil.KEY_CODE_WITH_SHIFT_MAPPER 一致，主要是 Shift+数字/符号）
    private val SHIFT_MAP = mapOf(
        44 to 2118,  // Shift+',' => '<'
        46 to 2117,  // Shift+'.' => '>'
        47 to 2113,  // Shift+'/' => '?'
        192 to 2056, // Shift+'`' => '~'
        48 to 2000,  // Shift+'0' => ')'
        49 to 2001,  // Shift+'1' => '!'
        50 to 2002,
        51 to 2003,
        52 to 2004,
        53 to 2005,
        54 to 2006,
        55 to 2007,
        56 to 2008,
        57 to 2009,  // Shift+'9' => '('
        109 to 2057, // Shift+数字键盘 '-'
        107 to 2058, // Shift+数字键盘 '+'
    )

    /** 是否是 Ctrl+V（粘贴） */
    fun isPaste(ctrlPressed: Boolean, awtKeyCode: Int): Boolean =
        ctrlPressed && awtKeyCode == 86 // VK_V

    /** 该按键是否可注入到设备 */
    fun isSupported(awtKeyCode: Int, keyChar: Char?, shiftPressed: Boolean): Boolean {
        if (shiftPressed && SHIFT_MAP.containsKey(awtKeyCode)) return true
        if (CODE_MAP.containsKey(awtKeyCode)) return true
        val c = keyChar ?: return false
        return c.isLetterOrDigit()
    }

    /** 返回 OpenHarmony 键值；无法映射返回 -1 */
    fun ohKeyCode(awtKeyCode: Int, keyChar: Char?, shiftPressed: Boolean): Int {
        if (shiftPressed && SHIFT_MAP.containsKey(awtKeyCode)) return SHIFT_MAP.getValue(awtKeyCode)
        if (CODE_MAP.containsKey(awtKeyCode)) return CODE_MAP.getValue(awtKeyCode)
        val c = keyChar ?: return -1
        if (c.isLetter()) {
            return c.uppercaseChar().code - 65 + 2017
        }
        if (c.isDigit()) {
            return c.code - 48 + 2000
        }
        return -1
    }

    /**
     * 生成设备端注入命令，逻辑与官方 MainForm.onKeyBoardDown 一致：
     * - '<' '>' '?' '.' 走 uitest 文本输入
     * - 大写字母 / Shift 组合键需先按下再抬起 Shift(2047)
     * - 其余直接 `uinput -K -d <key> -u <key>`
     *
     * @param shiftPressed Compose 桌面端字母的 utf16CodePoint 恒为小写，大写需依赖此参数判断
     * @return 命令字符串；不支持返回 null
     */
    fun uinputCommand(awtKeyCode: Int, keyChar: Char?, shiftPressed: Boolean): String? {
        val keyCode = ohKeyCode(awtKeyCode, keyChar, shiftPressed)
        if (keyCode < 0) return null
        return if (keyChar != null && keyChar in "<>?.") {
            "uitest uiInput text '$keyChar'"
        } else if (keyChar?.isLetter() == true) {
            // Compose 桌面端拿不到大小写信息，靠 shift 判断
            if (shiftPressed) {
                "uinput -K -d $OH_KEY_SHIFT -d $keyCode -u $keyCode -u $OH_KEY_SHIFT"
            } else {
                "uinput -K -d $keyCode -u $keyCode"
            }
        } else if (shiftPressed) {
            "uinput -K -d $OH_KEY_SHIFT -d $keyCode -u $keyCode -u $OH_KEY_SHIFT"
        } else {
            "uinput -K -d $keyCode -u $keyCode"
        }
    }
}
