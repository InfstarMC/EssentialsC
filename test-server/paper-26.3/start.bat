@echo off
setlocal EnableExtensions

cd /d "%~dp0"
set "SERVER_DIR=%~dp0"
set "PROJECT_ROOT=%~dp0..\.."
set "PAPER_VERSION=26.3"
set "PAPER_JAR=%SERVER_DIR%paper.jar"

if defined JAVA_HOME_25 (
    set "JAVA_CMD=%JAVA_HOME_25%\bin\java.exe"
) else (
    set "JAVA_CMD=java"
)

echo [EssentialsC] Building and deploying the latest plugin...
pushd "%PROJECT_ROOT%"
call "%PROJECT_ROOT%\gradlew.bat" deployToPaper263
set "BUILD_EXIT=%ERRORLEVEL%"
popd
if not "%BUILD_EXIT%"=="0" (
    echo [EssentialsC] Plugin deployment failed.
    exit /b %BUILD_EXIT%
)

if not exist "%PAPER_JAR%" (
    echo [EssentialsC] Downloading Paper %PAPER_VERSION%...
    powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%SERVER_DIR%download-paper.ps1" -Version "%PAPER_VERSION%" -OutputPath "%PAPER_JAR%"
    if errorlevel 1 (
        echo [EssentialsC] Paper download failed.
        exit /b 1
    )
)

if not exist "%SERVER_DIR%eula.txt" (
    >"%SERVER_DIR%eula.txt" echo eula=true
)

if not exist "%SERVER_DIR%server.properties" (
    >"%SERVER_DIR%server.properties" echo online-mode=false
    >>"%SERVER_DIR%server.properties" echo server-port=25569
    >>"%SERVER_DIR%server.properties" echo enable-query=false
    >>"%SERVER_DIR%server.properties" echo motd=EssentialsC Paper 26.3 Test
)

if not exist "%JAVA_CMD%" if "%JAVA_CMD%" neq "java" (
    echo [EssentialsC] JAVA_HOME_25 does not point to a valid Java executable: %JAVA_CMD%
    exit /b 1
)

echo [EssentialsC] Starting Paper %PAPER_VERSION% on port 25569...
"%JAVA_CMD%" -Xms512M -Xmx1G -jar "%PAPER_JAR%" --nogui
set "EXIT_CODE=%ERRORLEVEL%"
echo [EssentialsC] Server stopped with exit code %EXIT_CODE%.
exit /b %EXIT_CODE%
