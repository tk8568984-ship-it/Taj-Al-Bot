# TAJ AI — Setup Guide (बिना Android Studio के)

यह एक native Android app है (Kotlin)। इसे APK में बदलने के लिए यहाँ **GitHub Actions**
इस्तेमाल कर रहे हैं — यानी build आपके कंप्यूटर पर नहीं, Google/GitHub के cloud
server पर होगी। आपको सिर्फ़ एक **free GitHub account** चाहिए, कुछ भी भारी
install नहीं करना।

---

## स्टेप 1 — GitHub account बनाएँ (अगर नहीं है)
https://github.com/signup पर जाकर free account बनाएँ।

## स्टेप 2 — नया repository बनाएँ
1. GitHub पर ऊपर right corner में **+** → **New repository**
2. Name दें: `taj-ai` (या कोई भी नाम)
3. **Private** रखें (क्योंकि इसमें आपकी API key जाएगी)
4. **Create repository** दबाएँ

## स्टेप 3 — यह पूरा project upload करें
1. इस ZIP को अपने कंप्यूटर में extract करें
2. GitHub repo के page पर **"uploading an existing file"** लिंक पर क्लिक करें
3. Extract की हुई सारी files/folders (TajAI फ़ोल्डर के अंदर की हर चीज़ — `app`, `gradle`,
   `.github`, `build.gradle`, आदि) को drag-and-drop करके upload करें
4. नीचे **Commit changes** दबाएँ

> Tip: अगर आपने VS Code install किया हुआ है, तो GitHub Desktop app (बहुत हल्का,
> सिर्फ़ एक installer) से भी push कर सकते हैं — पर web upload भी पूरी तरह काफ़ी है।

## स्टेप 4 — अपनी Gemini API key जोड़ें (secret के तौर पर)
1. https://aistudio.google.com/apikey से free Gemini API key लें (अगर पहले से नहीं है)
2. अपने GitHub repo में: **Settings** → **Secrets and variables** → **Actions**
3. **New repository secret**
   - Name: `GEMINI_API_KEY`
   - Value: अपनी key paste करें
4. **Add secret**

## स्टेप 5 — Build चलाएँ
1. Repo में **Actions** tab खोलें
2. **"Build TAJ AI APK"** workflow चुनें → **Run workflow** बटन दबाएँ
3. 3-5 मिनट रुकें (green ✅ tick आने तक)
4. उसी run के अंदर नीचे **Artifacts** में **TajAI-debug-apk** दिखेगा — क्लिक करके
   डाउनलोड करें (यह एक .zip होगा, अंदर `app-debug.apk` मिलेगी)

## स्टेप 6 — फ़ोन में install करें
1. `app-debug.apk` को अपने Android फ़ोन में भेजें (Google Drive / WhatsApp / cable से)
2. फ़ोन पर उस file पर टैप करें
3. पहली बार में Android पूछेगा "Install unknown apps" — allow करें
4. Install हो जाने पर app खोलें

## स्टेप 7 — App के अंदर एक बार की setup (सिर्फ़ एक बार!)
App खोलने पर चार बटन दिखेंगे, एक-एक करके दबाएँ:
1. **माइक्रोफ़ोन Permission** — Allow करें
2. **Call / Contacts Permission** — Allow करें
3. **Floating Orb (Display over apps)** — यह Settings खोलेगा, वहाँ TAJ AI को toggle ON करें, वापस app में आएँ
4. **Accessibility Service** — यह Settings खोलेगा, TAJ AI ढूँढकर ON करें, "Allow" confirm करें

फिर सबसे नीचे **"TAJ AI चालू करो"** दबाएँ। एक popup आएगा screen dekhne (chart vision) के लिए
अनुमति माँगते हुए — यह **सिर्फ़ यहीं एक बार** आता है; जब तक आप agent को बंद नहीं करते,
दोबारा नहीं पूछेगा (यह Android का एक सख़्त, unavoidable OS rule है — इसे कोई app bypass नहीं कर सकता,
पर एक बार allow करने के बाद जितनी देर agent चालू है उतनी देर दोबारा नहीं पूछता)।

बस — अब orb हर जगह ऊपर तैरता रहेगा, बोलते रहिए और TAJ सुनता/करता रहेगा।

---

## अभी क्या-क्या काम करता है
- Voice से बात करना (हिंदी) + trading सवालों के जवाब (30 guidelines के आधार पर)
- "WhatsApp खोलो", "YouTube खोलो", "Settings खोलो" जैसी commands से app launch
- "\_\_\_ नंबर पर call करो"
- WhatsApp पर pre-filled message भेजना (chat खुलकर auto-send की कोशिश करता है)
- Screen/chart देखकर trading signal (CALL/PUT/WAIT + confidence %)
- "ताज बंद हो जाओ" / "ताज उठो" से sleep/wake

## जो सीमाएँ ज़रूर जान लें
- **WhatsApp auto-send**: WhatsApp का internal UI बदलने पर "Send" बटन ढूँढने वाला हिस्सा
  (`TajAccessibilityService.kt` में `tapButtonByDescription`) कभी-कभी टूट सकता है — तब message
  chat में type होकर रुक जाएगा, आपको खुद Send दबाना होगा।
- **Wi-Fi ON/OFF**: Android 10+ में कोई भी app सीधे Wi-Fi toggle नहीं कर सकता (Google की तरफ़ से
  block है) — इसलिए यह सिर्फ़ Wi-Fi का quick-panel खोल देता है, आख़िरी टैप आपको करना होगा।
- **लगातार सुनना**: बैटरी बचाने के लिए Android कभी-कभी background listening को रोक सकता है —
  ऐसे में orb पर एक टैप करके फिर से "जगाया" जा सकता है।
- यह app सिर्फ़ आपके अपने फ़ोन में sideload होने के लिए बना है (Play Store पर publish करने पर
  Google ऐसी deep automation permissions को सख़्ती से review करता है)।

## आगे बदलाव करने हैं?
- **`TradingKnowledge.kt`** — 30 guidelines का text यहीं है, चाहें तो और detail जोड़ सकते हैं
- **`GeminiClient.kt`** — TAJ की personality/system prompt यहीं है
- **`ActionParser.kt`** — नए action (जैसे किसी और app के लिए) यहीं जोड़ें
- **`TajAccessibilityService.kt`** — असली फ़ोन actions (tap, type, call) यहीं होते हैं

कोई भी file बदलने के बाद बस दोबारा GitHub पर push/upload करें और **Actions → Run workflow**
दबाएँ — नई APK अपने आप बन जाएगी।
