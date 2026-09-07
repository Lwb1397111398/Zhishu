@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"

echo [1/2] 构建 Debug APK（首次会慢一些，之后增量很快）...
call "%~dp0gradlew.bat" --offline :app:assembleDebug
if errorlevel 1 (
  echo.
  echo 构建失败。把上面的报错发给我就行。
  pause
  exit /b 1
)

echo [2/2] 构建完成：
echo   %~dp0app\build\outputs\apk\debug\app-debug.apk
start "" explorer /select,"%~dp0app\build\outputs\apk\debug\app-debug.apk"
pause
exit /b 0
