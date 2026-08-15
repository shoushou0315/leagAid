@echo off
setlocal
cd /d "%~dp0"

REM --- Self-elevate (MySQL start needs admin), then re-run ---
net session >nul 2>&1
if errorlevel 1 (
    powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
    exit /b
)

echo [1/4] Redis...
start /min "" powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0data\start-redis.ps1"

echo [2/4] MySQL...
net start MySQL80

set "JAR=%~dp0target\leagAid-1.0-SNAPSHOT.jar"
set "RUN=%~dp0data\run"

REM --- Build jar if missing ---
if not exist "%JAR%" (
    echo [INFO] jar missing, building...
    set "MVN=C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2025.2.5\plugins\maven\lib\maven3\bin\mvn.cmd"
    if not exist "%MVN%" set "MVN=mvn.cmd"
    call "%MVN%" -q -DskipTests package -f pom.xml
)

REM --- Explode fat jar (JNativeHook can't load dll from nested jar) ---
if not exist "%RUN%\BOOT-INF\classes\com\example\demo\DemoApplication.class" (
    echo [3/4] Extracting app to data\run...
    powershell -NoProfile -Command "Add-Type -AssemblyName System.IO.Compression.FileSystem; if(Test-Path '%RUN%'){Remove-Item '%RUN%' -Recurse -Force}; [System.IO.Compression.ZipFile]::ExtractToDirectory('%JAR%', '%RUN%')"
)

echo [4/4] Starting app...
start "leagAid" "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2025.2.5\jbr\bin\java.exe" -cp "%RUN%\BOOT-INF\classes;%RUN%\BOOT-INF\lib\*" com.example.demo.DemoApplication --spring.profiles.active=local

echo Waiting for app (15s)...
ping -n 15 127.0.0.1 >nul
start "" http://localhost:8080

echo.
echo Done. Closing this window; app logs stay in "leagAid" window.
endlocal
