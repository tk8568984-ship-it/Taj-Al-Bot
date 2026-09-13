package com.tajai.assistant

/**
 * The 30-guideline trading knowledge base, condensed as titles + short instruction
 * so it fits cleanly inside the Gemini system prompt as TAJ's "trading brain".
 * Expand any single line with more detail if you want TAJ to reason more deeply
 * about that specific guideline.
 */
object TradingKnowledge {

    val THIRTY_GUIDELINES = listOf(
        "1. Market Structure & Trend (HH/HL/LH/LL) — ट्रेंड के खिलाफ़ कभी trade मत लो।",
        "2. Support & Resistance Zones — जिस level पर price 2-3 बार react करे वही असली zone है।",
        "3. Candlestick Anatomy (Body vs Wick) — बड़ी wick मतलब उस तरफ़ का दबाव कमज़ोर पड़ रहा है।",
        "4. Trendlines & Channel Trading — channel के ऊपर/नीचे touch पर entry सोचो।",
        "5. Single Candlestick Patterns — Hammer, Shooting Star, Doji, Marubozu पहचानो।",
        "6. Double Candlestick Formations — Engulfing, Tweezer Top/Bottom।",
        "7. Triple Candlestick Reversals — Morning Star, Evening Star, Three Soldiers/Crows।",
        "8. Trend Continuation Candles — छोटे pullback के बाद फिर trend की दिशा में momentum।",
        "9. Real Breakout & Retest — breakout के बाद retest पर confirmation लो, जल्दी entry मत लो।",
        "10. Fake Breakout / Bull-Bear Trap — volume कम हो तो breakout पर शक करो।",
        "11. Institutional Order Blocks — बड़े players के entry zone से reaction।",
        "12. Fair Value Gaps — imbalance zones अक्सर price वापस fill करने आता है।",
        "13. Liquidity Sweep / Stop Hunt — price एक झटके में stop-loss zone साफ़ करके reverse करता है।",
        "14. Market Structure Shift (MSS/CHoCH) — trend बदलने का पहला संकेत।",
        "15. Fibonacci Golden Pocket (0.618-0.65) — pullback की सबसे मज़बूत retracement zone।",
        "16. EMA 20/50/200 — इन औसतों के crossover और support से दिशा तय होती है।",
        "17. RSI Divergence — price नया high बनाए पर RSI नहीं, तो reversal की चेतावनी।",
        "18. MACD Momentum — histogram बढ़ना यानी momentum मज़बूत हो रहा है।",
        "19. Bollinger Bands — squeeze के बाद बड़ी चाल आती है, band से bounce भी देखो।",
        "20. Volume Spread Analysis — बड़ा candle पर कम volume = false move का शक।",
        "21. Multi-Timeframe Analysis — बड़े timeframe की दिशा में छोटे timeframe पर entry लो।",
        "22. 1-min/5-min Expiry Rules (Binary Options) — expiry के हिसाब से entry timing अलग रखो।",
        "23. OTC Market Patterns — weekend/OTC market algorithm-driven होता है, अलग व्यवहार करता है।",
        "24. Micro Wick Rejection (5s/15s) — बहुत छोटे timeframe पर तुरंत rejection confirmation।",
        "25. Psychological Round Numbers — .000/.500 levels पर institutional reaction ज़्यादा होता है।",
        "26. High Impact News Windows — बड़ी news के समय trade से बचो, स्प्रेड/स्लिपेज बढ़ जाता है।",
        "27. 1-2% Capital Protection Rule — कभी भी एक trade में capital का 1-2% से ज़्यादा risk मत लो।",
        "28. Disciplined 1-Step Martingale — revenge trading से बचो, नियम से ही आगे बढ़ो।",
        "29. Emotional Mastery — एक session में ज़्यादा से ज़्यादा 3 trades का cap रखो।",
        "30. 5-Point Confluence Checklist — कम से कम 5 signals मिलें तभी high-confidence entry लो, और हर trade journal करो।"
    )

    fun asPromptBlock(): String =
        THIRTY_GUIDELINES.joinToString(separator = "\n")
}
