@echo off
REM ==========================================
REM  leagAid 一键启动（含打包，使用 application-local.yml 真实配置）
REM  需管理员权限（JNativeHook 全局热键 + LCU 认证）
REM  前置：MySQL + Redis 已启动（先跑 start_env.bat）
REM ==========================================

setlocal
cd /d "%~dp0"

REM --- 自提权（热键需要管理员）---
net session >nul 2>&1
if errorlevel 1 (
    echo 请求管理员权限...
    powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
    exit /b
)

REM --- 查找 JDK 21（优先 IDEA 内置 JBR）---
set "JAVA_HOME=C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2025.2.5\jbr"
if not exist "%JAVA_HOME%\bin\java.exe" (
    echo [ERROR] 未找到 JDK 21，请在脚本中修改 JAVA_HOME
    pause
    exit /b 1
)
set "PATH=%JAVA_HOME%\bin;%PATH%"
echo [OK] Java: %JAVA_HOME%

REM --- 用 IDEA 内置 maven 打包（若存在），否则 mvn ---
set "MVN=C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2025.2.5\plugins\maven\lib\maven3\bin\mvn.cmd"
if not exist "%MVN%" set "MVN=mvn.cmd"
echo [1/2] 打包中...
call "%MVN%" -q -DskipTests package -f pom.xml
if errorlevel 1 (
    echo [ERROR] 打包失败
    pause
    exit /b 1
)

REM --- 启动（local profile = 真实密钥/密码，不入库）---
set "JAR=target\leagAid-1.0-SNAPSHOT.jar"
if not exist "%JAR%" (
    echo [ERROR] 未找到 %JAR%
    pause
    exit /b 1
)
echo [2/2] 启动应用（http://localhost:8080）...
java -jar "%JAR%" --spring.profiles.active=local

pause
