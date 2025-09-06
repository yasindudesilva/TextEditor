# 📱 Text Editor – Android Code Editor

A professional mobile text editor designed for developers.  
Supports file editing, syntax highlighting, compiler integration, and error handling.  

---

## ✨ Features
- **File Management**: Create, open, and save `.txt`, `.kt`, `.java`, `.py` files.  
- **Find & Replace**: Search with case-sensitive and whole-word options.  
- **Syntax Highlighting**: Kotlin syntax highlighting built-in. Custom **JSON configs** allow highlighting for Python and Java.  
- **Compile Code**: Seamlessly compile Kotlin, Python, and Java code using a **Flask server** and **ADB bridge**.  
- **Error Highlighting**: View compiler errors with line numbers directly in the editor.  
- **Undo/Redo**: Full history manager to revert or re-apply changes.  
- **Mobile-First**: Optimized for **Android 9+** with Material 3 UI and light/dark themes.  

---

## 🖥 Requirements

### Server-Side (PC)
- [Python 3](https://www.python.org/downloads/)  
- Kotlin Compiler (`kotlinc`) → [Download](https://kotlinlang.org/docs/command-line.html)  
- Java Runtime (JDK 17+ recommended)  
- ADB (Android Debug Bridge) → Included with [Android Studio](https://developer.android.com/studio)  
- Flask → install with:
  ```bash
  pip install flask
Client-Side (Phone)
Android 9 (API 28) or above

USB Debugging enabled (Developer Options)

⚡ Setup Instructions
1. Run Flask Server
On your PC, start the server:

bash
Copy code
python server.py
It will run at:

cpp
Copy code
http://127.0.0.1:8123
2. Connect Phone via ADB
Run:

bash
Copy code
adb reverse tcp:8123 tcp:8123
3. Open the App
Install the APK (app-debug.apk or app-release.apk) on your phone.

Write code in the editor.

Press Run ▶️ to send code to the server and view output/errors.


